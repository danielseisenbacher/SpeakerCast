package at.seisi.simpleaudiostream.stream

import at.seisi.simpleaudiostream.log.AppLog
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Tiny HTTP server that serves the live capture as an endless WAV stream at [PATH].
 * Every open connection gets the stream: Sonos sometimes opens two connections a few ms apart and
 * keeps whichever it likes, so we must not guess. Connections it doesn't want are closed by Sonos.
 */
class StreamServer {
    private val serverSocket = ServerSocket(0)
    private val listeners = CopyOnWriteArrayList<Listener>()

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
        for (l in listeners) l.offer(chunk)
    }

    fun stop() {
        runCatching { serverSocket.close() }
        listeners.forEach { it.close() }
        listeners.clear()
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
                val current = Listener(socket)
                listeners += current
                // Safety net against connections that are left open but never read.
                while (listeners.size > MAX_LISTENERS) listeners.removeAt(0).close()

                out.write(responseHeaders().toByteArray())
                out.write(WavFormat.streamingHeader())
                out.flush()
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
            val start = System.nanoTime()
            var sent = 0L
            try {
                while (!closed) {
                    val chunk = queue.poll(SILENCE_AFTER_MS, TimeUnit.MILLISECONDS)
                    if (chunk != null) {
                        out.write(chunk)
                        out.flush()
                        sent += chunk.size
                        continue
                    }
                    // Nothing captured: keep the stream alive with silence, otherwise the speaker treats
                    // the stall as end of stream. Only fill up to real time, so the padding never turns
                    // into extra latency once audio resumes.
                    val due = silenceDue(System.nanoTime() - start, sent)
                    if (due > 0) {
                        out.write(ByteArray(due))
                        out.flush()
                        sent += due
                    }
                }
            } finally {
                listeners.remove(this)
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
        private const val MAX_LISTENERS = 4
        private const val SILENCE_AFTER_MS = 200L

        /** Bytes of silence needed to bring the stream back up to real time (frame-aligned, never negative). */
        fun silenceDue(elapsedNanos: Long, sentBytes: Long): Int {
            val expected = elapsedNanos * WavFormat.BYTES_PER_SECOND / 1_000_000_000L
            val due = expected - sentBytes
            if (due <= 0) return 0
            return (minOf(due, WavFormat.BYTES_PER_SECOND.toLong()) / WavFormat.BYTES_PER_FRAME * WavFormat.BYTES_PER_FRAME).toInt()
        }

        fun responseHeaders(): String =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: ${WavFormat.MIME_TYPE}\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Accept-Ranges: none\r\n" +
                "Connection: close\r\n" +
                "transferMode.dlna.org: Streaming\r\n" +
                "contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n" +
                "Server: SpeakerCast\r\n\r\n"

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
