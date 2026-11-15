package top.mambabot.drone

import android.content.Context
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.common.flightcontroller.FlightControllerState
import dji.common.util.CommonCallbacks
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.camera.Camera
import dji.sdk.flightcontroller.FlightController
import dji.sdk.gimbal.Gimbal
import dji.sdk.products.Aircraft
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

class DroneEx(msg: String) : Exception(msg)

/** Local ENU point in metres relative to a mission origin. */
data class P(val e: Double, val n: Double)

object Drone {
    /** Legal ceiling for micro drones in uncontrolled airspace (真高 50 m). */
    const val MAX_ALT = 50.0

    var onLog: ((String) -> Unit)? = null
    var onEvent: ((JSONObject) -> Unit)? = null

    @Volatile var registerMsg = "SDK 注册中"
    @Volatile var productName: String? = null
    @Volatile var state: FlightControllerState? = null
    @Volatile var stateAt = 0L
    @Volatile var battery = -1
    @Volatile var landingRequested = false

    val aircraft: Aircraft? get() = DJISDKManager.getInstance().product as? Aircraft
    fun fc(): FlightController = aircraft?.flightController ?: throw DroneEx("飞机未连接")
    fun cam(): Camera = aircraft?.camera ?: throw DroneEx("相机未连接")
    fun gimbal(): Gimbal = aircraft?.gimbal ?: throw DroneEx("云台未连接")

    fun log(s: String) {
        android.util.Log.i("MambaDrone", s)
        onLog?.invoke(s)
        onEvent?.invoke(JSONObject().put("type", "log").put("msg", s))
    }

    fun register(ctx: Context) {
        thread { DJISDKManager.getInstance().registerApp(ctx.applicationContext, callback) }
    }

    private val callback = object : DJISDKManager.SDKManagerCallback {
        override fun onRegister(e: DJIError?) {
            if (e == DJISDKError.REGISTRATION_SUCCESS) {
                registerMsg = "SDK 已注册"
                log("DJI SDK 注册成功，等待连接遥控器")
                DJISDKManager.getInstance().startConnectionToProduct()
            } else {
                registerMsg = "SDK 注册失败: ${e?.description}"
                log(registerMsg)
            }
        }
        override fun onProductDisconnect() {
            productName = null; state = null
            log("飞机/遥控器断开")
        }
        override fun onProductConnect(p: BaseProduct?) {
            productName = p?.model?.displayName
            log("已连接: $productName")
            bind()
        }
        override fun onProductChanged(p: BaseProduct?) { bind() }
        override fun onComponentChange(k: BaseProduct.ComponentKey?, old: BaseComponent?, new: BaseComponent?) {
            new?.setComponentListener { bind() }
            bind()
        }
        override fun onInitProcess(e: DJISDKInitEvent?, total: Int) {}
        override fun onDatabaseDownloadProgress(cur: Long, total: Long) {}
    }

    private fun bind() {
        val a = aircraft ?: return
        productName = a.model?.displayName
        a.flightController?.setStateCallback { s ->
            state = s
            stateAt = System.currentTimeMillis()
            if (landingRequested && s.isLandingConfirmationNeeded) {
                landingRequested = false
                a.flightController?.confirmLanding { err -> log("确认降落: ${err?.description ?: "OK"}") }
            }
            if (!s.isFlying) landingRequested = false
        }
        a.battery?.setStateCallback { b -> battery = b.chargeRemainingInPercent }
    }

    fun fresh(): FlightControllerState {
        val s = state ?: throw DroneEx("没有飞控数据")
        if (System.currentTimeMillis() - stateAt > 1500) throw DroneEx("飞控数据中断")
        return s
    }

    /** Current position in the local frame of [o] (lat, lon). */
    fun local(s: FlightControllerState, oLat: Double, oLon: Double): P {
        val l = s.aircraftLocation
        return toLocal(l.latitude, l.longitude, oLat, oLon)
    }

    fun toLocal(lat: Double, lon: Double, oLat: Double, oLon: Double): P {
        val r = 6378137.0
        return P((lon - oLon) * PI / 180 * r * cos(oLat * PI / 180), (lat - oLat) * PI / 180 * r)
    }

    fun distHome(s: FlightControllerState): Double? {
        val h = s.homeLocation ?: return null
        val l = s.aircraftLocation ?: return null
        if (!h.latitude.isFinite() || !l.latitude.isFinite() || !s.isHomeLocationSet) return null
        val p = toLocal(l.latitude, l.longitude, h.latitude, h.longitude)
        return hypot(p.e, p.n)
    }

    @Volatile var videoBytes = 0L
    /**
     * Last ~4 MB of the raw HEVC feed. The stream has no IDR frames (rolling intra refresh), so a
     * clean picture needs the data from a VPS through one full refresh period; we remember where
     * each VPS starts so a snapshot uploads only that span.
     */
    private val ring = ByteArray(4 shl 20)
    private var ringPos = 0
    private var ringFull = false
    private val vps = ArrayDeque<Long>()  // absolute stream offsets of recent VPS start codes
    private var tail = 0                  // last 3 bytes seen, for start codes split across chunks

    /** H.264 SPS (nal type 7) or HEVC VPS (0x40): a point decoding can start from. */
    private fun isHeader(b: Int) = b == 0x40 || (b and 0x9f) == 0x07

    @Synchronized fun feed(buf: ByteArray, size: Int) {
        for (i in 0 until size) {
            tail = ((tail shl 8) or (buf[i].toInt() and 0xff)) and 0xffffff
            val at = when {
                tail == 1 && i + 1 < size && isHeader(buf[i + 1].toInt() and 0xff) -> i - 2
                i == 0 && (tail shr 8) == 1 && isHeader(tail and 0xff) -> i - 3
                else -> continue
            }
            vps.addLast(videoBytes + at)
            if (vps.size > 8) vps.removeFirst()
        }
        videoBytes += size
        var off = 0
        while (off < size) {
            val n = minOf(size - off, ring.size - ringPos)
            System.arraycopy(buf, off, ring, ringPos, n)
            off += n; ringPos += n
            if (ringPos == ring.size) { ringPos = 0; ringFull = true }
        }
    }

    @Synchronized fun videoDebug() = "starts=${vps.toList()} bytes=$videoBytes ringPos=$ringPos full=$ringFull"

    /** Bytes from the newest decodable start point (needs a little data after it) up to now. */
    @Synchronized fun recentVideo(): ByteArray {
        val held = if (ringFull) ring.size.toLong() else ringPos.toLong()
        val avail = vps.filter { it >= videoBytes - held }
        if (avail.isEmpty()) return ByteArray(0)
        val start = avail.lastOrNull { videoBytes - it >= 64 * 1024 } ?: avail.first()
        val len = (videoBytes - start).toInt()
        val from = ((ringPos - len) % ring.size + ring.size) % ring.size
        return if (from + len <= ring.size) ring.copyOfRange(from, from + len)
        else ring.copyOfRange(from, ring.size) + ring.copyOf(from + len - ring.size)
    }
    /** Grabs the current FPV frame; set by MainActivity, call on the main thread. */
    var snap: (() -> android.graphics.Bitmap?)? = null

    fun telemetry(): JSONObject {
        val o = JSONObject().put("type", "telemetry")
            .put("sdk", registerMsg).put("product", productName ?: JSONObject.NULL)
            .put("battery", battery)
            .put("video_kb", videoBytes / 1024)
            .put("mission", Missions.current ?: JSONObject.NULL)
        val s = state ?: return o
        val l = s.aircraftLocation
        fun put(k: String, v: Double) { if (v.isFinite()) o.put(k, Math.round(v * 1e7) / 1e7) }
        if (l != null) { put("lat", l.latitude); put("lon", l.longitude); put("alt", l.altitude.toDouble()) }
        put("yaw", s.attitude.yaw)
        put("vx", s.velocityX.toDouble()); put("vy", s.velocityY.toDouble()); put("vz", s.velocityZ.toDouble())
        distHome(s)?.let { put("dist_home", it) }
        o.put("flying", s.isFlying).put("mode", s.flightModeString)
            .put("sats", s.satelliteCount).put("gps", s.gpsSignalLevel?.name)
            .put("going_home", s.isGoingHome).put("flight_time_s", s.flightTimeInSeconds)
            .put("sim", aircraft?.flightController?.simulator?.isSimulatorActive == true)
        return o
    }
}

/** Await a DJI CompletionCallback. */
suspend fun dj(timeoutMs: Long = 15000, block: (CommonCallbacks.CompletionCallback<DJIError>) -> Unit) =
    withTimeout(timeoutMs) {
        suspendCancellableCoroutine<Unit> { c ->
            block(CommonCallbacks.CompletionCallback<DJIError> { e ->
                if (!c.isActive) return@CompletionCallback
                if (e == null) c.resume(Unit) else c.resumeWithException(DroneEx(e.description))
            })
        }
    }
