package top.mambabot.drone

import android.Manifest
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var codec: DJICodecManager? = null
    private val video = VideoFeeder.VideoDataListener { buf, size ->
        Drone.feed(buf, size)
        Live.feed(buf, size)
        codec?.sendDataToDecoder(buf, size)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.READ_PHONE_STATE), 1)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        val w = resources.displayMetrics.widthPixels - 32
        val fpv = TextureView(this)
        Drone.snap = { fpv.bitmap }
        root.addView(fpv.apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                    codec = DJICodecManager(this@MainActivity, s, w, h)
                }
                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                    codec?.cleanSurface(); codec = null; return false
                }
                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
            }
        }, LinearLayout.LayoutParams(w, w * 9 / 16))

        status = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "停止任务 / 悬停"
            textSize = 20f
            setBackgroundColor(Color.rgb(200, 30, 30)); setTextColor(Color.WHITE)
            setOnClickListener { Missions.stop(); Drone.log("手动停止，悬停") }
        }, LinearLayout.LayoutParams(MATCH_PARENT, 180))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, cmd: String, args: JSONObject = JSONObject()) = row.addView(Button(this).apply {
            text = label
            setOnClickListener {
                Missions.handle(JSONObject().put("cmd", cmd).put("args", args)) { r ->
                    Drone.log("$label: ${if (r.optBoolean("ok")) r.opt("data") else r.optString("msg")}")
                }
            }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        btn("开模拟器", "sim")
        btn("模拟测试", "simtest")
        btn("降落", "land")
        btn("返航", "rth")
        root.addView(row)

        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f }
        root.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        Drone.onLog = { s -> ui.post { logView.text = (s + "\n" + logView.text).take(6000) } }
        tick()
    }

    private var resumed = false
    private var feedAttached = false

    /** The feed only delivers data if the listener is added after the aircraft connects. */
    private fun syncFeed() {
        val want = resumed && Drone.aircraft != null
        if (want == feedAttached) return
        val feed = VideoFeeder.getInstance()?.primaryVideoFeed ?: return
        if (want) feed.addVideoDataListener(video) else feed.removeVideoDataListener(video)
        feedAttached = want
    }

    private fun tick() {
        syncFeed()
        val t = Drone.telemetry()
        status.text = buildString {
            append("${t.optString("sdk")} | ${t.opt("product")} | 电量 ${t.optInt("battery")}%\n")
            append("链路 ${if (Link.up) "已连 MambaBot" else "未连"} | 任务 ${t.opt("mission")}\n")
            if (t.has("alt")) append("高 %.1fm 离家 %.0fm 星 %d %s %s\n".format(t.optDouble("alt"),
                t.optDouble("dist_home", 0.0), t.optInt("sats"), t.optString("mode"),
                if (t.optBoolean("sim")) "[模拟器]" else ""))
        }
        ui.postDelayed({ tick() }, 500)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        syncFeed()
    }

    override fun onPause() {
        resumed = false
        syncFeed()
        super.onPause()
    }
}
