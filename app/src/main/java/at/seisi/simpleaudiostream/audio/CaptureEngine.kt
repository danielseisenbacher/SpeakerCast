package at.seisi.simpleaudiostream.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import at.seisi.simpleaudiostream.stream.WavFormat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Captures what other apps play (Android 10+ AudioPlaybackCapture) as 16-bit stereo PCM,
 * either from all apps or only from the app with [onlyUid]. The caller must hold RECORD_AUDIO.
 */
@SuppressLint("MissingPermission")
class CaptureEngine(projection: MediaProjection, onlyUid: Int?, private val sink: (ByteArray) -> Unit) {
    private val record: AudioRecord
    @Volatile private var running = false
    private var worker: Thread? = null
    private val peak = AtomicInteger(0)

    init {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection).apply {
            if (onlyUid != null) {
                addMatchingUid(onlyUid)
            } else {
                addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                addMatchingUsage(AudioAttributes.USAGE_GAME)
                addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            }
        }.build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(WavFormat.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            WavFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, WavFormat.CHUNK_BYTES) * 4)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord could not be initialized" }
    }

    fun start() {
        running = true
        record.startRecording()
        worker = thread(name = "capture") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ByteArray(WavFormat.CHUNK_BYTES)
            while (running) {
                val n = record.read(buffer, 0, buffer.size)
                when {
                    n > 0 -> {
                        trackPeak(buffer, n)
                        sink(buffer.copyOf(n))
                    }
                    n < 0 -> break
                }
            }
        }
    }

    /** Highest absolute sample since the last call (0 = silence). */
    fun takePeak(): Int = peak.getAndSet(0)

    private fun trackPeak(buffer: ByteArray, length: Int) {
        var max = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val abs = if (sample < 0) -sample else sample
            if (abs > max) max = abs
            i += 2
        }
        peak.accumulateAndGet(max) { a, b -> maxOf(a, b) }
    }

    fun stop() {
        running = false
        runCatching { record.stop() }
        worker?.join(500)
        record.release()
    }
}
