package at.seisi.simpleaudiostream.stream

import at.seisi.simpleaudiostream.log.AppLog
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Tiny HTTP server that serves the live capture as an endless WAV stream at [PATH].
 * Only one listener at a time: a new request replaces the previous one.
 */
class StreamServer {
    private val serverSocket = ServerSocket(0)
    @Volatile private var listener: Listener? = null

    val port: Int get() = serverSocket.localPort

    fun start() {
        thread(name = "stream-accept", isDaemon = true) {
            while (!serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    break
                }
                thread(name = "stream-client", isDaemon = true) { handle(socket) }
            }
        }
    }

    /** Called from the capture thread with each chunk of PCM. */
    fun push(chunk: ByteArray) {
        listener?.offer(chunk)
    }

    fun stop() {
        runCatching { serverSocket.close() }
        listener?.close()
        listener = null
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = 5000
                val request = readRequest(socket.getInputStream()) ?: return
                AppLog.i(TAG, "${socket.inetAddress.hostAddress}: ${request.method} ${request.path}")
                val out = socket.getOutputStream()
                when {
                    request.path != PATH -> {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        return
                    }
                    request.method == "HEAD" -> {
                        out.write(responseHeaders().toByteArray())
                        return
                    }
                    request.method != "GET" -> {
                        out.write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        return
                    }
                }
                socket.soTimeout = 0
                socket.tcpNoDelay = true
                out.write(responseHeaders().toByteArray())
                out.write(WavFormat.streamingHeader())
                out.flush()

                val current = Listener(socket)
                listener?.close()
                listener = current
                current.pump()
                AppLog.i(TAG, "Stream to ${socket.inetAddress.hostAddress} ended")
            } catch (e: IOException) {
                AppLog.i(TAG, "client disconnected: ${e.message}")
            }
        }
    }

    private inner class Listener(private val socket: Socket) {
        private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CHUNKS)
        @Volatile private var closed = false

        fun offer(chunk: ByteArray) {
            // If the speaker reads too slowly, drop the oldest audio instead of growing latency.
            while (!queue.offer(chunk)) queue.poll()
        }

        fun pump() {
            val out = socket.getOutputStream()
            try {
                while (!closed) {
                    // Keep the stream alive with silence while nothing is being captured,
                    // otherwise the speaker treats the stall as end of stream.
                    val chunk = queue.poll(SILENCE_AFTER_MS, TimeUnit.MILLISECONDS) ?: SILENCE
                    out.write(chunk)
                    out.flush()
                }
            } finally {
                if (listener === this) listener = null
            }
        }

        fun close() {
            closed = true
            runCatching { socket.close() }
        }
    }

    data class Request(val method: String, val path: String)

    companion object {
        private const val TAG = "StreamServer"
        const val PATH = "/stream.wav"
        private const val QUEUE_CHUNKS = 50 // 2 s
        private const val SILENCE_AFTER_MS = 200L
        private val SILENCE = ByteArray(WavFormat.BYTES_PER_SECOND / 5) // 200 ms

        fun responseHeaders(): String =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: ${WavFormat.MIME_TYPE}\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Accept-Ranges: none\r\n" +
                "Connection: close\r\n" +
                "transferMode.dlna.org: Streaming\r\n" +
                "contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n" +
                "Server: SimpleAudioStream\r\n\r\n"

        /** Reads the request line and headers; returns null on a malformed or oversized request. */
        fun readRequest(input: InputStream): Request? {
            val bytes = StringBuilder()
            while (!bytes.endsWith("\r\n\r\n") && !bytes.endsWith("\n\n")) {
                val b = input.read()
                if (b < 0 || bytes.length > 8192) return null
                bytes.append(b.toChar())
            }
            return parseRequestLine(bytes.lineSequence().first())
        }

        fun parseRequestLine(line: String): Request? {
            val parts = line.trim().split(' ')
            if (parts.size < 2) return null
            return Request(parts[0].uppercase(), parts[1].substringBefore('?'))
        }
    }
}
