package top.mambabot.drone

import android.content.Context
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.flightcontroller.simulator.InitializationData
import dji.common.model.LocationCoordinate2D
import dji.sdk.media.DownloadListener
import dji.sdk.media.MediaFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Command dispatch + long-running missions. One mission at a time. */
object Missions {
    lateinit var ctx: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var current: String? = null
    @Volatile private var job: Job? = null

    private val prefs get() = ctx.getSharedPreferences("missions", Context.MODE_PRIVATE)

    fun handle(m: JSONObject, reply: (JSONObject) -> Unit) {
        val id = m.optInt("id")
        val cmd = m.optString("cmd")
        val a = m.optJSONObject("args") ?: JSONObject()
        scope.launch {
            val r = JSONObject().put("type", "result").put("id", id).put("cmd", cmd)
            try {
                r.put("ok", true).put("data", run(cmd, a))
            } catch (e: CancellationException) {
                r.put("ok", false).put("msg", "已取消")
            } catch (e: Exception) {
                r.put("ok", false).put("msg", e.message ?: e.toString())
            }
            reply(r)
        }
    }

    private suspend fun run(cmd: String, a: JSONObject): Any = when (cmd) {
        "status" -> Drone.telemetry()
        "stop" -> { stop(); "已停止任务并悬停" }
        "takeoff" -> takeoff()
        "land" -> { stop(); Drone.landingRequested = true; dj { Drone.fc().startLanding(it) }; "降落中" }
        "rth" -> { stop(); dj { Drone.fc().startGoHome(it) }; "返航中" }
        "photo" -> { Fly.photoMode(); Fly.shoot(); "已拍照" }
        "gimbal" -> { Fly.gimbalPitch(a.optDouble("pitch", 0.0)); "云台已转到 ${a.optDouble("pitch", 0.0)}°" }
        "sim" -> sim(a)
        "move" -> mission("move") { move(a) }
        "orbit" -> mission("orbit") { orbit(a, it) }
        "grid" -> mission("grid") { grid(a, it) }
        "simtest" -> simtest()
        "snap" -> snap()
        "nudge" -> nudge(a)
        "vdump" -> vdump()
        "diag" -> diag()
        "look" -> look()
        "record" -> record(a)
        "download" -> download(a)
        "missions" -> JSONObject(prefs.all.mapValues { it.value.toString() })
        else -> throw DroneEx("未知命令 $cmd")
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun takeoff(): String {
        val s = Drone.fresh()
        if (s.isFlying) return "已经在空中"
        if (!Live.recording) Live.startRecording(File(ctx.getExternalFilesDir(null), "rec"))
        dj { Drone.fc().startTakeoff(it) }
        repeat(150) {
            val st = Drone.fresh()
            if (st.isFlying && st.aircraftLocation.altitude > 1.0) return "已起飞，高度 %.1f m".format(st.aircraftLocation.altitude)
            delay(100)
        }
        throw DroneEx("起飞 15 秒后仍未离地")
    }

    /** Run [block] as the single active mission (replacing any running one); takes off if needed. */
    private suspend fun mission(kind: String, block: suspend (String) -> JSONObject): JSONObject {
        job?.let { it.cancel(); it.join() }
        if (!Drone.fresh().isFlying) { takeoff(); delay(1500) }
        val id = kind + "-" + SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())
        val j = scope.launch {
            current = id
            Drone.log("任务开始 $id")
            try {
                Fly.enable()
                val r = block(id)
                Drone.onEvent?.invoke(JSONObject().put("type", "mission_done").put("mission", id).put("data", r))
            } catch (e: CancellationException) {
                Drone.log("任务 $id 已取消，悬停")
            } catch (e: Exception) {
                Drone.log("任务 $id 失败: ${e.message}")
                Drone.onEvent?.invoke(JSONObject().put("type", "mission_failed").put("mission", id).put("msg", e.message))
            } finally {
                Fly.release()
                current = null
            }
        }
        job = j
        return JSONObject().put("mission", id).put("msg", "任务已开始，进度看 events / status")
    }

    private fun checkAlt(alts: List<Double>) =
        alts.forEach { if (it < 0.5 || it > Drone.MAX_ALT) throw DroneEx("高度 $it m 超出 0.5–${Drone.MAX_ALT} m") }

    private fun origin(): Triple<Double, Double, Double> {
        val s = Drone.fresh()
        val l = s.aircraftLocation
        if (!l.latitude.isFinite() || s.satelliteCount < 6) throw DroneEx("GPS 星数 ${s.satelliteCount}，太少")
        return Triple(l.latitude, l.longitude, s.attitude.yaw)
    }

    private suspend fun move(a: JSONObject): JSONObject {
        val (oLat, oLon, yaw0) = origin()
        val s = Drone.fresh()
        val f = a.optDouble("fwd", 0.0); val r = a.optDouble("right", 0.0)
        val alt = s.aircraftLocation.altitude + a.optDouble("up", 0.0)
        val psi = yaw0 * PI / 180
        val t = P(f * sin(psi) + r * cos(psi), f * cos(psi) - r * sin(psi))
        checkAlt(listOf(alt))
        Fly.goTo(t, alt, Fly.wrap(yaw0 + a.optDouble("yaw", 0.0)), oLat, oLon, maxV = 1.5)
        return JSONObject().put("msg", "到位")
    }

    /**
     * Stop-and-shoot orbit around the point [radius] m straight ahead of the aircraft.
     * rings="alt:pitch,alt:pitch" adds extra loops (default: one loop at current altitude).
     */
    private suspend fun orbit(a: JSONObject, id: String): JSONObject {
        val (oLat, oLon, yaw0) = origin()
        val r = a.optDouble("radius", 6.0)
        val n = a.optInt("photos", 24)
        val shoot = a.optBoolean("shoot", true)
        val curAlt = Drone.fresh().aircraftLocation.altitude.toDouble()
        val rings = a.optString("rings", "").split(",").filter { it.contains(":") }.map {
            val (h, p) = it.split(":"); h.toDouble() to p.toDouble()
        }.ifEmpty { listOf(a.optDouble("alt", curAlt) to a.optDouble("pitch", -15.0)) }
        val y = yaw0 * PI / 180
        val c = P(r * sin(y), r * cos(y))
        val start = yaw0 + 180
        fun pt(k: Int): Pair<P, Double> {
            val th = start + k * 360.0 / n
            val t = th * PI / 180
            return P(c.e + r * sin(t), c.n + r * cos(t)) to Fly.wrap(th + 180)
        }
        checkAlt(rings.map { it.first })
        if (shoot) Fly.photoMode()
        var shots = 0
        for ((ri, ring) in rings.withIndex()) {
            Fly.gimbalPitch(ring.second)
            for (k in 0 until n) {
                val (p, yaw) = pt(k)
                Fly.goTo(p, ring.first, yaw, oLat, oLon)
                if (shoot) { Fly.shoot(); shots++ }
                Drone.onEvent?.invoke(JSONObject().put("type", "progress").put("mission", id)
                    .put("ring", ri + 1).put("of_rings", rings.size).put("point", k + 1).put("of_points", n))
            }
        }
        Fly.goTo(pt(0).first, rings.last().first, pt(0).second, oLat, oLon)
        prefs.edit().putInt(id, shots).apply()
        return JSONObject().put("mission", id).put("photos", shots)
    }

    /** Lawn-mower nadir survey: [length] m forward, [width] m to the right, photo every [spacing] m. */
    private suspend fun grid(a: JSONObject, id: String): JSONObject {
        val (oLat, oLon, yaw0) = origin()
        val len = a.optDouble("length", 20.0); val w = a.optDouble("width", 20.0)
        val sp = a.optDouble("spacing", 5.0)
        val alt = a.optDouble("alt", 15.0)
        val psi = yaw0 * PI / 180
        fun body(f: Double, r: Double) = P(f * sin(psi) + r * cos(psi), f * cos(psi) - r * sin(psi))
        val rows = (w / sp).toInt() + 1; val cols = (len / sp).toInt() + 1
        val pts = (0 until rows).flatMap { i ->
            val fs = (0 until cols).map { it * sp }
            (if (i % 2 == 0) fs else fs.reversed()).map { body(it, i * sp) }
        }
        checkAlt(listOf(alt))
        Fly.photoMode()
        Fly.gimbalPitch(-90.0)
        pts.forEachIndexed { k, p ->
            Fly.goTo(p, alt, yaw0, oLat, oLon)
            Fly.shoot()
            Drone.onEvent?.invoke(JSONObject().put("type", "progress").put("mission", id).put("point", k + 1).put("of_points", pts.size))
        }
        Fly.goTo(pts[0], alt, yaw0, oLat, oLon)
        prefs.edit().putInt(id, pts.size).apply()
        return JSONObject().put("mission", id).put("photos", pts.size)
    }

    private suspend fun sim(a: JSONObject): String {
        val fc = Drone.fc()
        val on = a.optJSONArray("_")?.optString(0) != "off"
        return if (on) {
            if (Drone.fresh().isFlying) throw DroneEx("飞行中不能开模拟器")
            val ll = LocationCoordinate2D(a.optDouble("lat", 31.2035), a.optDouble("lon", 121.5900))
            dj { fc.simulator.start(InitializationData.createInstance(ll, 10, 10), it) }
            "模拟器已开（务必卸下桨叶）"
        } else {
            dj { fc.simulator.stop(it) }
            "模拟器已关"
        }
    }

    /** Upload the current FPV frame as snap/<time>.jpg. */
    private suspend fun snap(): JSONObject {
        val bm = withContext(Dispatchers.Main) { Drone.snap?.invoke() } ?: throw DroneEx("没有画面")
        val name = "snap-" + SimpleDateFormat("HHmmss-SSS", Locale.US).format(Date()) + ".jpg"
        val f = File(ctx.cacheDir, name)
        f.outputStream().use { bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
        try { Link.upload(f, "snap") } finally { f.delete() }
        return JSONObject().put("name", name).put("video_kb", Drone.videoBytes / 1024)
    }

    /** JPEG of the newest live frame, uploaded as snap/look-*.jpg. */
    private fun look(): JSONObject {
        val t0 = System.currentTimeMillis()
        val jpg = Live.jpeg()
        val t1 = System.currentTimeMillis()
        val name = "look-" + SimpleDateFormat("HHmmss-SSS", Locale.US).format(Date()) + ".jpg"
        val f = File(ctx.cacheDir, name)
        f.writeBytes(jpg)
        try { Link.upload(f, "snap") } finally { f.delete() }
        return JSONObject().put("name", name).put("jpg", jpg.size)
            .put("decode_ms", t1 - t0).put("upload_ms", System.currentTimeMillis() - t1)
    }

    /** record on|off: raw feed to <external files>/rec/rec-*.h264 on the phone. */
    private fun record(a: JSONObject): JSONObject {
        val off = a.optJSONArray("_")?.optString(0) == "off"
        val f = if (off) Live.stopRecording() else Live.startRecording(File(ctx.getExternalFilesDir(null), "rec"))
        return JSONObject().put("recording", !off).put("file", f?.absolutePath).put("bytes", f?.length() ?: 0)
    }

    /** Why won't it take off: DJI diagnostics, activation/binding, sensor state. */
    private suspend fun diag(): JSONObject {
        val o = JSONObject()
        val m = dji.sdk.sdkmanager.DJISDKManager.getInstance()
        runCatching {
            val am = m.appActivationManager
            o.put("app_activation", am.appActivationState?.name).put("aircraft_binding", am.aircraftBindingState?.name)
        }.onFailure { o.put("activation_err", it.toString()) }
        runCatching {
            val u = dji.sdk.useraccount.UserAccountManager.getInstance()
            o.put("dji_account", u.userAccountState?.name)
        }
        val ac = Drone.aircraft ?: return o.put("aircraft", "未连接")
        val diags = JSONArray()
        val got = kotlinx.coroutines.CompletableDeferred<Unit>()
        ac.setDiagnosticsInformationCallback { list ->
            list?.forEach { d -> diags.put("${d.code}: ${d.reason} | ${d.solution}") }
            got.complete(Unit)
        }
        kotlinx.coroutines.withTimeoutOrNull(4000) { got.await() }
        o.put("diagnostics", diags)
        val fc = ac.flightController
        val s = fc.state
        o.put("flight_mode", s.flightModeString).put("imu_preheating", s.isIMUPreheating)
            .put("motors_on", s.areMotorsOn()).put("landing_confirm", s.isLandingConfirmationNeeded)
            .put("ultrasonic", s.isUltrasonicBeingUsed).put("ultrasonic_h", s.ultrasonicHeightInMeters.toDouble())
            .put("vision_pos", s.isVisionPositioningSensorBeingUsed)
            .put("compass_err", runCatching { fc.compass?.hasError() }.getOrNull())
            .put("fw", runCatching { ac.firmwarePackageVersion }.getOrNull())
        return o
    }

    /** Upload the last ~2 MB of raw H.264 as snap/<time>.h264 (decoded server-side). */
    private fun vdump(): JSONObject {
        val data = Drone.recentVideo()
        if (data.isEmpty()) throw DroneEx("还没收到完整的视频起始帧，稍等几秒 " + Drone.videoDebug())
        val name = "v-" + SimpleDateFormat("HHmmss-SSS", Locale.US).format(Date()) + ".h264"
        val f = File(ctx.cacheDir, name)
        f.writeBytes(data)
        try { Link.upload(f, "snap") } finally { f.delete() }
        return JSONObject().put("name", name).put("bytes", data.size)
    }

    /**
     * GPS-free nudge: hold a body-frame velocity for [secs], then brake to a hover.
     * fwd/right/up in m/s (|v| <= 0.5), yaw in deg/s (|yaw| <= 30), secs <= 2.
     */
    private suspend fun nudge(a: JSONObject): String {
        if (!Drone.fresh().isFlying) throw DroneEx("还没起飞")
        stop()
        fun c(k: String, m: Double) = a.optDouble(k, 0.0).coerceIn(-m, m)
        val f = c("fwd", 0.5); val r = c("right", 0.5); val u = c("up", 0.5); val y = c("yaw", 30.0)
        val secs = a.optDouble("secs", 1.0).coerceIn(0.1, 2.0)
        Fly.enable()
        try {
            val end = System.currentTimeMillis() + (secs * 1000).toLong()
            while (System.currentTimeMillis() < end) { Fly.send(f, r, y, u); delay(100) }
        } finally { Fly.release() }
        val s = Drone.fresh()
        return "点动完成，高度 %.1f m".format(s.aircraftLocation.altitude)
    }

    /** Full self-test inside the DJI simulator: takeoff, small orbit, land. */
    private suspend fun simtest(): JSONObject {
        if (Drone.aircraft?.flightController?.simulator?.isSimulatorActive != true)
            throw DroneEx("先开模拟器: drone sim on")
        takeoff()
        delay(2000)
        mission("simtest") { orbit(JSONObject().put("radius", 5).put("photos", 8).put("alt", 5).put("shoot", false), it) }
        while (job?.isActive == true) delay(500)
        val s = Drone.fresh()
        Drone.landingRequested = true
        dj { Drone.fc().startLanding(it) }
        return JSONObject().put("msg", "模拟环绕完成，已降落").put("sats", s.satelliteCount)
    }

    /** Pull the newest JPEGs (default: the photos of [mission]) off the SD card and upload them. */
    private suspend fun download(a: JSONObject): JSONObject {
        val mid = a.optString("mission", "misc")
        val count = a.optInt("count", prefs.getInt(mid, 0))
        if (count <= 0) throw DroneEx("不知道要下载几张：给 count= 或一个已完成的 mission=")
        val c = Drone.cam()
        val flat = c.isFlatCameraModeSupported
        if (flat) dj { c.enterPlayback(it) } else dj { c.setMode(SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD, it) }
        val dir = File(ctx.getExternalFilesDir(null), "missions/$mid").apply { mkdirs() }
        var up = 0
        try {
            val mm = c.mediaManager ?: throw DroneEx("这台飞机不支持 MediaManager")
            dj(30000) { mm.refreshFileListOfStorageLocation(SettingsDefinitions.StorageLocation.SDCARD, it) }
            val files = mm.sdCardFileListSnapshot.orEmpty()
                .filter { it.mediaType == MediaFile.MediaType.JPEG }
                .sortedBy { it.index }.takeLast(count)
            Drone.log("下载 ${files.size} 张到手机并上传")
            for ((i, f) in files.withIndex()) {
                val base = f.fileName.substringBeforeLast('.')
                val out = fetch(f, dir, base)
                Link.upload(out, mid)
                up++
                Drone.onEvent?.invoke(JSONObject().put("type", "progress").put("mission", mid)
                    .put("uploaded", i + 1).put("of", files.size))
            }
        } finally {
            if (flat) runCatching { dj { c.exitPlayback(it) } }
            else runCatching { dj { c.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, it) } }
        }
        return JSONObject().put("mission", mid).put("uploaded", up)
    }

    private suspend fun fetch(f: MediaFile, dir: File, base: String): File =
        suspendCancellableCoroutine { k ->
            f.fetchFileData(dir, base, object : DownloadListener<String> {
                override fun onStart() {}
                override fun onRateUpdate(total: Long, current: Long, perSize: Long) {}
                override fun onRealtimeDataUpdate(data: ByteArray?, pos: Long, last: Boolean) {}
                override fun onProgress(total: Long, current: Long) {}
                override fun onSuccess(path: String?) {
                    val p = File(path ?: "")
                    val file = if (p.isFile) p else (dir.listFiles { x -> x.name.startsWith(base) }?.firstOrNull()
                        ?: File(dir, f.fileName))
                    if (k.isActive) k.resume(file)
                }
                override fun onFailure(e: DJIError?) {
                    if (k.isActive) k.resumeWithException(DroneEx("下载 ${f.fileName} 失败: ${e?.description}"))
                }
            })
        }
}
