package com.example.camstream

import android.content.Context
import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

interface CameraController {
    fun setZoom(ratio: Float)
    /** [min, max, current] */
    fun zoomInfo(): FloatArray
    /** x, y — нормалізовані координати (0..1) на зображенні. */
    fun focusAt(x: Float, y: Float)
    fun autoFocus()
    /** Фото в максимальній роздільності (блокує до завершення). */
    fun takePhoto(): File?
}

class MjpegServer(
    private val ctx: Context,
    port: Int,
    user: String,
    pass: String,
    private val camera: CameraController
) : NanoHTTPD(port) {

    private val expected = "Basic " + Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)

    private fun authorized(s: IHTTPSession): Boolean {
        val h = s.headers["authorization"] ?: return false
        return MessageDigest.isEqual(h.toByteArray(), expected.toByteArray())
    }

    private fun text(st: Response.Status, t: String) = newFixedLengthResponse(st, "text/plain", t)

    override fun serve(s: IHTTPSession): Response {
        if (!authorized(s)) {
            Thread.sleep(500) // притормажуємо підбір пароля
            return text(Response.Status.UNAUTHORIZED, "Unauthorized").apply {
                addHeader("WWW-Authenticate", "Basic realm=\"CamStream\", charset=\"UTF-8\"")
            }
        }
        fun p(n: String) = s.parameters[n]?.firstOrNull()?.toFloatOrNull()

        return when (s.uri) {
            "/" -> {
                val html = ctx.assets.open("index.html").bufferedReader().use { it.readText() }
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
            }
            "/stream" -> newChunkedResponse(
                Response.Status.OK, "multipart/x-mixed-replace; boundary=frame", MjpegStream()
            ).apply {
                addHeader("Cache-Control", "no-cache, no-store")
                addHeader("Connection", "close")
            }
            "/snapshot" -> {
                val j = FrameHub.latest()
                if (j == null) text(Response.Status.NOT_FOUND, "no frame")
                else newFixedLengthResponse(Response.Status.OK, "image/jpeg", j.inputStream(), j.size.toLong())
            }
            "/photo" -> {
                val f = camera.takePhoto()
                if (f == null) text(Response.Status.SERVICE_UNAVAILABLE, "photo unavailable")
                else newFixedLengthResponse(Response.Status.OK, "image/jpeg", FileInputStream(f), f.length()).apply {
                    addHeader("Content-Disposition", "attachment; filename=\"photo.jpg\"")
                }
            }
            "/api/zoom" -> {
                val r = p("ratio")
                if (r == null) text(Response.Status.BAD_REQUEST, "ratio?") else { camera.setZoom(r); info() }
            }
            "/api/focus" -> {
                val x = p("x"); val y = p("y")
                if (x == null || y == null) text(Response.Status.BAD_REQUEST, "x,y?")
                else { camera.focusAt(x, y); text(Response.Status.OK, "ok") }
            }
            "/api/autofocus" -> { camera.autoFocus(); text(Response.Status.OK, "ok") }
            "/api/info" -> info()
            else -> text(Response.Status.NOT_FOUND, "Not found")
        }
    }

    private fun info(): Response {
        val z = camera.zoomInfo()
        val json = """{"min":${z[0]},"max":${z[1]},"cur":${z[2]}}"""
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }
}

/** Нескінченний multipart-потік JPEG-кадрів (MJPEG). */
private class MjpegStream : InputStream() {
    private var buf = ByteArray(0)
    private var pos = 0
    private var seq = -1L

    private fun fill(): Boolean {
        while (true) {
            if (FrameHub.stopped) return false
            val f = FrameHub.await(seq, 3000) ?: continue
            seq = f.first
            val head = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.second.size}\r\n\r\n".toByteArray()
            buf = head + f.second + "\r\n".toByteArray()
            pos = 0
            return true
        }
    }

    override fun read(): Int {
        if (pos >= buf.size && !fill()) return -1
        return buf[pos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= buf.size && !fill()) return -1
        val n = minOf(len, buf.size - pos)
        System.arraycopy(buf, pos, b, off, n)
        pos += n
        return n
    }
}
