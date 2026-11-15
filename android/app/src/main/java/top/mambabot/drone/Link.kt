package top.mambabot.drone

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** WebSocket to the MambaBot relay: commands in, telemetry/events/results out. */
object Link {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private val uploadClient = OkHttpClient.Builder().writeTimeout(5, TimeUnit.MINUTES).build()
    @Volatile private var ws: WebSocket? = null
    @Volatile var up = false
    private val base = BuildConfig.RELAY_URL.trimEnd('/')
    private val token = URLEncoder.encode(BuildConfig.RELAY_TOKEN, "UTF-8")

    fun start() {
        Drone.onEvent = { send(it) }
        connect()
        Missions.scope.launch {
            while (true) {
                delay(1000)
                send(Drone.telemetry())
            }
        }
    }

    private fun connect() {
        val req = Request.Builder().url("$base/drone/ws?token=$token").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                up = true
                Drone.log("已连上 MambaBot")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = JSONObject(text)
                Drone.onLog?.invoke("← ${m.optString("cmd")} ${m.optJSONObject("args") ?: ""}")
                Missions.handle(m) { r ->
                    Drone.onLog?.invoke("→ ${r.optString("cmd")}: ${if (r.optBoolean("ok")) "OK" else r.optString("msg")}")
                    send(r)
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket, t.message)
        })
    }

    private fun lost(w: WebSocket, why: String?) {
        if (w !== ws) return
        if (up) Drone.onLog?.invoke("与 MambaBot 断开: $why，3 秒后重连")
        up = false
        Missions.scope.launch { delay(3000); if (ws === w) connect() }
    }

    fun send(o: JSONObject) { ws?.takeIf { up }?.send(o.toString()) }

    fun upload(f: File, mission: String) {
        val url = "$base/drone/upload?token=$token&mission=${URLEncoder.encode(mission, "UTF-8")}" +
            "&name=${URLEncoder.encode(f.name, "UTF-8")}"
        val req = Request.Builder().url(url).post(f.asRequestBody("image/jpeg".toMediaType())).build()
        uploadClient.newCall(req).execute().use {
            if (!it.isSuccessful) throw DroneEx("上传 ${f.name} 失败: HTTP ${it.code}")
        }
    }
}
