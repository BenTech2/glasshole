package com.glasshole.glass.sdk

import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID

/**
 * Single-stream MJPEG (multipart/x-mixed-replace) HTTP server. The
 * producer (camera or screen capturer) calls [pushFrame] with each
 * fresh JPEG; connected clients see the latest frame on their next
 * write. Phone fetches at `http://<ip>:<port>/stream?token=<token>`.
 *
 *   GET /stream?token=…    — start a long-lived MJPEG response
 *   GET /still?token=…     — single JPEG frame, useful for poll-based
 *                            clients that can't hold an HTTP stream
 *
 * The server holds at most [latestFrame], not a queue — slow clients
 * just see frame drops, never backpressure the producer. One client
 * at a time is fine; debug feature, no need for fancier sharing.
 */
class MjpegHttpServer {
    companion object {
        private const val TAG = "MjpegHttpServer"
        private const val BOUNDARY = "glassholeframe"
    }

    val token: String = UUID.randomUUID().toString().replace("-", "").take(16)

    @Volatile private var latestFrame: ByteArray? = null
    @Volatile private var frameSeq: Long = 0L
    private val frameLock = Object()

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    val port: Int get() = serverSocket?.localPort ?: 0

    @Synchronized
    fun start() {
        if (serverSocket != null) return
        val ss = ServerSocket(0)
        serverSocket = ss
        acceptThread = Thread {
            while (!ss.isClosed) {
                val client = try {
                    ss.accept()
                } catch (_: SocketException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "accept failed: ${e.message}")
                    continue
                }
                Thread { handleRequest(client) }.apply {
                    isDaemon = true
                    name = "MjpegHttpServer-worker"
                    start()
                }
            }
        }.apply { isDaemon = true; name = "MjpegHttpServer-accept"; start() }
        Log.i(TAG, "Started on port ${ss.localPort}")
    }

    @Synchronized
    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread = null
        synchronized(frameLock) {
            latestFrame = null
            // Wake any waiters so they exit.
            frameLock.notifyAll()
        }
    }

    fun streamUrl(ip: String) = "http://$ip:$port/stream?token=$token"

    /** Producer hook — store this frame and wake any waiting clients. */
    fun pushFrame(jpegBytes: ByteArray) {
        synchronized(frameLock) {
            latestFrame = jpegBytes
            frameSeq++
            frameLock.notifyAll()
        }
    }

    private fun handleRequest(socket: Socket) {
        socket.use {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val out = socket.getOutputStream()

                val requestLine = reader.readLine() ?: run {
                    writeStatus(out, 400, "Bad Request"); return
                }
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                }
                if (!requestLine.startsWith("GET ")) {
                    writeStatus(out, 405, "Method Not Allowed"); return
                }
                if (!requestLine.contains("token=$token")) {
                    Log.w(TAG, "Bad token: $requestLine")
                    writeStatus(out, 401, "Unauthorized"); return
                }

                val path = requestLine
                    .substringAfter(" ")
                    .substringBefore(" ")
                    .substringBefore("?")

                when (path) {
                    "/stream" -> serveStream(out)
                    "/still" -> serveStill(out)
                    else -> writeStatus(out, 404, "Not Found")
                }
            } catch (e: Exception) {
                Log.w(TAG, "request handling failed: ${e.message}")
            }
        }
    }

    private fun serveStream(out: OutputStream) {
        try {
            // No cache headers, no keep-alive — phone just keeps reading
            // until it (or we) close the socket.
            out.write((
                "HTTP/1.1 200 OK\r\n" +
                "Cache-Control: no-store, no-cache\r\n" +
                "Pragma: no-cache\r\n" +
                "Connection: close\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n\r\n"
            ).toByteArray())
            out.flush()

            var lastSeq = -1L
            while (serverSocket?.isClosed == false) {
                val (frame, seq) = synchronized(frameLock) {
                    while (frameSeq == lastSeq && serverSocket?.isClosed == false) {
                        try {
                            // Long-poll until producer pushes a new frame.
                            // 5s timeout → idle keep-alive write so the
                            // socket doesn't get killed by NAT.
                            (frameLock as Object).wait(5_000)
                        } catch (_: InterruptedException) { return }
                        if (frameSeq == lastSeq) {
                            // Idle — bail out and let the loop check
                            // serverSocket.isClosed.
                            break
                        }
                    }
                    Pair(latestFrame, frameSeq)
                }
                if (frame == null) continue
                if (seq == lastSeq) continue
                lastSeq = seq

                out.write((
                    "--$BOUNDARY\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${frame.size}\r\n\r\n"
                ).toByteArray())
                out.write(frame)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } catch (_: IOException) {
            // Client disconnected — totally fine.
        }
    }

    private fun serveStill(out: OutputStream) {
        val frame = synchronized(frameLock) { latestFrame }
        if (frame == null) {
            writeStatus(out, 503, "No Frame Yet")
            return
        }
        try {
            out.write((
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${frame.size}\r\n" +
                "Cache-Control: no-store, no-cache\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray())
            out.write(frame)
            out.flush()
        } catch (_: IOException) {}
    }

    private fun writeStatus(out: OutputStream, code: Int, msg: String) {
        try {
            out.write((
                "HTTP/1.1 $code $msg\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray())
            out.flush()
        } catch (_: Exception) {}
    }
}
