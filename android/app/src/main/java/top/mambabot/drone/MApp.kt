package top.mambabot.drone

import android.app.Application
import android.content.Context

class MApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        com.cySdkyc.clx.Helper.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        Missions.ctx = this
        Drone.register(this)
        Link.start()
    }
}
