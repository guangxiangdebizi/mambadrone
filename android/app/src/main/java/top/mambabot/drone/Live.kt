package top.mambabot.drone

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Keeps a software decoder running on the live feed so a snapshot is just "JPEG the newest frame",
 * and optionally records the raw stream to a file.
 *
 * Hardware decoders wait for an IDR frame and DJI's feed never sends one (rolling intra refresh),
 * so we use Google's software decoders, which decode from the SPS/VPS onward.
 */
object Live {
    private val nals = LinkedBlockingQueue<ByteArray>(2000)
    private val pending = ByteArrayOutputStream(256 * 1024)
    private var tail = -1

    @Volatile private var want = false
    @Volatile private var frame: ByteArray? = null
    @Volatile private var frameW = 0
    @Volatile private var frameH = 0
    private val lock = Object()

    @Volatile var frames = 0L
    private var rec: FileOutputStream? = null
    var recFile: File? = null
        private set
    val recording get() = rec != null

    init {
        Thread(::decodeLoop, "live-decoder").apply { isDaemon = true; start() }
    }

    /** Called with every chunk of the raw feed. */
    fun feed(buf: ByteArray, size: Int) {
        synchronized(this) { rec?.runCatching { write(buf, 0, size) } }
        for (i in 0 until size) {
            val b = buf[i].toInt() and 0xff
            pending.write(b)
            tail = ((tail shl 8) or b) and 0xffffff
            if (tail == 1 && pending.size() > 3) {
                val all = pending.toByteArray()
                if (all.size > 3) nal(all.copyOf(all.size - 3))  // previous NAL, starts with 00 00 01
                pending.reset()
                pending.write(0); pending.write(0); pending.write(1)
            }
        }
    }

    // The software decoders want one whole picture per input buffer, but the feed splits each
    // picture into several slice NALs; regroup them at access unit delimiters.
    private val au = ByteArrayOutputStream(256 * 1024)
    private var auHasSlice = false
    private var hevc = false

    private fun nal(n: ByteArray) {
        if (n.size < 4) return
        val b = n[3].toInt() and 0xff
        if (b == 0x40) hevc = true else if ((b and 0x9f) == 0x07) hevc = false
        val t = if (hevc) (b shr 1) and 0x3f else b and 0x1f
        val aud = if (hevc) t == 35 else t == 9
        if (aud && auHasSlice) {
            nals.offer(au.toByteArray())
            au.reset(); auHasSlice = false
        }
        au.write(n)
        if (if (hevc) t < 32 else t in 1..5) auHasSlice = true
    }

    @Synchronized fun startRecording(dir: File): File {
        stopRecording()
        dir.mkdirs()
        val f = File(dir, "rec-" + java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".h264")
        rec = FileOutputStream(f); recFile = f
        return f
    }

    @Synchronized fun stopRecording(): File? {
        rec?.runCatching { close() }
        rec = null
        return recFile
    }

    /** JPEG of the next decoded frame (waits at most [timeoutMs]). */
    fun jpeg(quality: Int = 75, timeoutMs: Long = 3000): ByteArray {
        val yuv: ByteArray
        synchronized(lock) {
            frame = null; want = true
            val end = System.currentTimeMillis() + timeoutMs
            while (frame == null) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) { want = false; throw DroneEx("没有解出画面（已解 $frames 帧）") }
                lock.wait(left)
            }
            yuv = frame!!
        }
        val out = ByteArrayOutputStream()
        YuvImage(yuv, ImageFormat.NV21, frameW, frameH, null).compressToJpeg(Rect(0, 0, frameW, frameH), quality, out)
        return out.toByteArray()
    }

    /** An access unit is decodable from scratch if it carries an SPS/VPS. */
    private fun header(au: ByteArray): String? {
        var i = 0
        while (i + 3 < au.size && i < 512) {
            if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1) {
                headerAt(au, i)?.let { return it }
                i += 3
            } else i++
        }
        return null
    }

    private fun headerAt(a: ByteArray, i: Int): String? {
        val n = a.copyOfRange(i, minOf(i + 4, a.size))
        return when {
        n.size < 4 -> null
        n[3].toInt() == 0x40 -> MediaFormat.MIMETYPE_VIDEO_HEVC
        (n[3].toInt() and 0x9f) == 0x07 -> MediaFormat.MIMETYPE_VIDEO_AVC
        else -> null
        }
    }

    private fun decodeLoop() {
        var codec: MediaCodec? = null
        var mime: String? = null
        val info = MediaCodec.BufferInfo()
        var pts = 0L
        while (true) {
            try {
                val n = nals.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val h = header(n)
                if (codec == null || (h != null && h != mime)) {
                    if (h == null) continue  // wait for a start point
                    codec?.runCatching { stop(); release() }
                    mime = h
                    codec = MediaCodec.createByCodecName(
                        if (h == MediaFormat.MIMETYPE_VIDEO_HEVC) "c2.android.hevc.decoder" else "c2.android.avc.decoder")
                    codec.configure(MediaFormat.createVideoFormat(h, 1280, 720), null, null, 0)
                    codec.start()
                }
                val c = codec!!
                var ib = c.dequeueInputBuffer(10_000)
                while (ib < 0) { drain(c, info); ib = c.dequeueInputBuffer(10_000) }
                c.getInputBuffer(ib)!!.apply { clear(); put(n) }
                c.queueInputBuffer(ib, 0, n.size, pts, 0)
                pts += 33_000
                drain(c, info)
            } catch (e: Exception) {
                Drone.log("解码器重启: ${e.message}")
                codec?.runCatching { release() }
                codec = null
            }
        }
    }

    private fun drain(c: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val ob = c.dequeueOutputBuffer(info, 0)
            if (ob < 0) return
            if (info.size > 0) {
                frames++
                if (want) {
                    c.getOutputImage(ob)?.let { img ->
                        val y = nv21(img); val w = img.width; val h = img.height
                        img.close()
                        synchronized(lock) { frame = y; frameW = w; frameH = h; want = false; lock.notifyAll() }
                    }
                }
            }
            c.releaseOutputBuffer(ob, false)
        }
    }

    private fun nv21(img: Image): ByteArray {
        val w = img.width; val h = img.height
        val out = ByteArray(w * h * 3 / 2)
        val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
        var p = 0
        val yb = y.buffer
        for (r in 0 until h) { yb.position(r * y.rowStride); yb.get(out, p, w); p += w }
        val ub = u.buffer; val vb = v.buffer
        for (r in 0 until h / 2) for (c in 0 until w / 2) {
            out[p++] = vb.get(r * v.rowStride + c * v.pixelStride)
            out[p++] = ub.get(r * u.rowStride + c * u.pixelStride)
        }
        return out
    }
}
