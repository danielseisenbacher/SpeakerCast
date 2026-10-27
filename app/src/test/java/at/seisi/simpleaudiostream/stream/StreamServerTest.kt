package at.seisi.simpleaudiostream.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StreamServerTest {
    @Test
    fun wavHeaderDescribesEndless44k16StereoPcm() {
        val header = WavFormat.streamingHeader()
        assertEquals(44, header.size)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertArrayEquals("RIFF".toByteArray(), header.copyOfRange(0, 4))
        assertEquals(-1, b.getInt(4))
        assertArrayEquals("WAVEfmt ".toByteArray(), header.copyOfRange(8, 16))
        assertEquals(1, b.getShort(20).toInt())
        assertEquals(2, b.getShort(22).toInt())
        assertEquals(44100, b.getInt(24))
        assertEquals(176400, b.getInt(28))
        assertEquals(4, b.getShort(32).toInt())
        assertEquals(16, b.getShort(34).toInt())
        assertArrayEquals("data".toByteArray(), header.copyOfRange(36, 40))
        assertEquals(-1, b.getInt(40))
    }

    @Test
    fun silenceOnlyFillsUpToRealTime() {
        val second = 1_000_000_000L
        assertEquals(0, StreamServer.silenceDue(second, WavFormat.BYTES_PER_SECOND.toLong()))
        assertEquals(0, StreamServer.silenceDue(second, 2L * WavFormat.BYTES_PER_SECOND))
        assertEquals(WavFormat.BYTES_PER_SECOND / 2, StreamServer.silenceDue(second, WavFormat.BYTES_PER_SECOND / 2L))
        assertEquals(0, StreamServer.silenceDue(second / 3, 0) % WavFormat.BYTES_PER_FRAME)
        // Never more than one second at once.
        assertEquals(WavFormat.BYTES_PER_SECOND, StreamServer.silenceDue(10 * second, 0))
    }

    @Test
    fun parsesRequests() {
        val request = StreamServer.readRequest(
            "GET /stream.wav?session=3 HTTP/1.1\r\nHost: x\r\nIcy-MetaData: 1\r\n\r\n".byteInputStream(),
        )
        assertEquals(StreamServer.Request("GET", "/stream.wav"), request)
        assertNull(StreamServer.readRequest("GET /stream.wav".byteInputStream()))
    }
}
