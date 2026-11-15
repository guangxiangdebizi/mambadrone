package top.mambabot.drone

import dji.common.camera.SettingsDefinitions
import dji.common.flightcontroller.virtualstick.FlightControlData
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem
import dji.common.flightcontroller.virtualstick.RollPitchControlMode
import dji.common.flightcontroller.virtualstick.VerticalControlMode
import dji.common.flightcontroller.virtualstick.YawControlMode
import dji.common.gimbal.Rotation
import dji.common.gimbal.RotationMode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Closed-loop flight on top of virtual sticks. Every axis runs in VELOCITY mode with our own
 * P-loops on GPS position / altitude / heading, so we only rely on the most widely supported
 * virtual-stick modes.
 */
object Fly {
    private const val PERIOD_MS = 100L

    fun wrap(a: Double): Double { var x = (a + 180) % 360; if (x < 0) x += 360; return x - 180 }
    private fun clamp(v: Double, m: Double) = v.coerceIn(-m, m)

    suspend fun enable() {
        val fc = Drone.fc()
        fc.rollPitchControlMode = RollPitchControlMode.VELOCITY
        fc.yawControlMode = YawControlMode.ANGULAR_VELOCITY
        fc.verticalControlMode = VerticalControlMode.VELOCITY
        fc.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
        dj { fc.setVirtualStickModeEnabled(true, it) }
    }

    /** Brake to a hover and hand the sticks back. Safe to call from a cancelled coroutine. */
    suspend fun release() = withContext(NonCancellable) {
        runCatching {
            repeat(5) { send(0.0, 0.0, 0.0, 0.0); delay(PERIOD_MS) }
            dj { Drone.fc().setVirtualStickModeEnabled(false, it) }
        }
    }

    /**
     * Body-frame velocity command. In MSDK v4 VELOCITY+BODY mode the "pitch" field is the
     * rightward velocity and "roll" is the forward velocity.
     */
    fun send(fwd: Double, right: Double, yawRate: Double, up: Double) {
        Drone.fc().sendVirtualStickFlightControlData(
            FlightControlData(right.toFloat(), fwd.toFloat(), yawRate.toFloat(), up.toFloat()), null)
    }

    private suspend fun safetyCheck() {
        coroutineContext.ensureActive()
        if (Drone.fresh().isGoingHome) throw DroneEx("飞机正在返航，任务中止")
    }

    /**
     * Fly to [t] at altitude [alt] (m above takeoff) and turn to [yaw] (deg, north=0, cw+),
     * then hold until settled. Aborts if the distance stops shrinking (wrong-axis guard).
     */
    suspend fun goTo(t: P, alt: Double, yaw: Double?, oLat: Double, oLon: Double,
                     maxV: Double = 2.0, tol: Double = 0.7) {
        var best = Double.MAX_VALUE
        var bestAt = System.currentTimeMillis()
        var settledAt = 0L
        val deadline = System.currentTimeMillis() + 120_000
        while (true) {
            safetyCheck()
            val s = Drone.fresh()
            val cur = Drone.local(s, oLat, oLon)
            val de = t.e - cur.e; val dn = t.n - cur.n
            val dist = hypot(de, dn)
            val dAlt = alt - s.aircraftLocation.altitude
            val psi = s.attitude.yaw
            val dYaw = if (yaw == null) 0.0 else wrap(yaw - psi)
            val speed = hypot(s.velocityX.toDouble(), s.velocityY.toDouble())

            val now = System.currentTimeMillis()
            if (dist < tol && abs(dAlt) < 0.5 && abs(dYaw) < 6 && speed < 0.4) {
                if (settledAt == 0L) settledAt = now
                if (now - settledAt > 800) { send(0.0, 0.0, 0.0, 0.0); return }
            } else settledAt = 0L

            if (dist < best - 0.3) { best = dist; bestAt = now }
            else if (dist > tol + 0.5 && now - bestAt > 6000)
                throw DroneEx("6 秒内没有接近目标（距离 %.1f m），疑似方向异常，已悬停".format(dist))
            if (now > deadline) throw DroneEx("飞向目标超时")

            val k = 0.6
            var vE = k * de; var vN = k * dn
            val v = hypot(vE, vN)
            if (v > maxV) { vE *= maxV / v; vN *= maxV / v }
            val r = psi * PI / 180
            val fwd = vN * cos(r) + vE * sin(r)
            val right = -vN * sin(r) + vE * cos(r)
            send(fwd, right, clamp(1.5 * dYaw, 45.0), clamp(0.8 * dAlt, 1.2))
            delay(PERIOD_MS)
        }
    }

    suspend fun gimbalPitch(p: Double) {
        dj { Drone.gimbal().rotate(Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE)
            .pitch(p.toFloat()).time(1.0).build(), it) }
        hold(1200)
    }

    suspend fun photoMode() {
        val c = Drone.cam()
        if (c.isFlatCameraModeSupported) dj { c.setFlatMode(SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE, it) }
        else dj { c.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, it) }
    }

    suspend fun shoot() {
        dj { Drone.cam().startShootPhoto(it) }
        hold(1500)
    }

    /** Keep streaming zero sticks (hover) while waiting; ignored when virtual sticks are off. */
    suspend fun hold(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { runCatching { send(0.0, 0.0, 0.0, 0.0) }; delay(PERIOD_MS) }
    }
}
