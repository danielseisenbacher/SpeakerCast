package at.seisi.simpleaudiostream.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import at.seisi.simpleaudiostream.stream.WavFormat
import kotlin.concurrent.thread

/**
 * Captures what other apps play (Android 10+ AudioPlaybackCapture) as 16-bit stereo PCM.
 * The caller must hold RECORD_AUDIO.
 */
@SuppressLint("MissingPermission")
class CaptureEngine(projection: MediaProjection, private val sink: (ByteArray) -> Unit) {
    private val record: AudioRecord
    @Volatile private var running = false
    private var worker: Thread? = null

    init {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
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
                    n > 0 -> sink(buffer.copyOf(n))
                    n < 0 -> break
                }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { record.stop() }
        worker?.join(500)
        record.release()
    }
}
