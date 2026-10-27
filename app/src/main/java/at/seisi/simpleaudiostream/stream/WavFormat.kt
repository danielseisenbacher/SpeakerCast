package at.seisi.simpleaudiostream.stream

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The PCM format we capture and stream: 44.1 kHz, stereo, 16-bit little-endian. */
object WavFormat {
    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2
    const val BITS_PER_SAMPLE = 16
    const val MIME_TYPE = "audio/wav"

    const val BYTES_PER_FRAME = CHANNELS * BITS_PER_SAMPLE / 8
    const val BYTES_PER_SECOND = SAMPLE_RATE * BYTES_PER_FRAME

    /** 40 ms of audio. */
    const val CHUNK_BYTES = BYTES_PER_SECOND / 25

    /** WAV header for a stream of unknown length: the RIFF and data sizes are set to the maximum. */
    fun streamingHeader(): ByteArray =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(-1) // 0xFFFFFFFF
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE)
            putInt(BYTES_PER_SECOND)
            putShort(BYTES_PER_FRAME.toShort())
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(-1) // 0xFFFFFFFF
        }.array()
}
