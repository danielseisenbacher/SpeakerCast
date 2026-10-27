package at.seisi.simpleaudiostream

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import at.seisi.simpleaudiostream.log.AppLog
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.media.VolumeProviderCompat
import at.seisi.simpleaudiostream.audio.CaptureEngine
import at.seisi.simpleaudiostream.stream.StreamServer
import at.seisi.simpleaudiostream.stream.WavFormat
import at.seisi.simpleaudiostream.upnp.Renderer
import at.seisi.simpleaudiostream.upnp.SsdpDiscovery
import at.seisi.simpleaudiostream.upnp.UpnpControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Foreground service that captures playback audio, serves it over HTTP and keeps the speaker
 * playing it. There is deliberately no time limit: it runs until the user stops it.
 */
class CastService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var session: Job? = null
    private var projection: MediaProjection? = null
    private var capture: CaptureEngine? = null
    private var server: StreamServer? = null
    @Volatile private var control: UpnpControl? = null
    private var mediaSession: MediaSessionCompat? = null
    private var volumeProvider: VolumeProviderCompat? = null
    private val volumeTarget = MutableStateFlow<Int?>(null)
    private var wakeLock: PowerManager.WakeLock? = null
    private val wifiLocks = mutableListOf<WifiManager.WifiLock>()
    private var savedPhoneVolume: Int? = null
    private var speaker = ""
    private var streamCounter = 0

    /**
     * Android sends volume keys to the most recently started media session, so YouTube Music
     * reclaims them whenever it starts playing or skips a track. Re-assert ours periodically.
     */
    private val reclaimVolumeKeys = object : Runnable {
        override fun run() {
            mediaSession?.let {
                it.setPlaybackState(playbackState(PlaybackStateCompat.STATE_PAUSED))
                it.setPlaybackState(playbackState(PlaybackStateCompat.STATE_PLAYING))
            }
            mainHandler.postDelayed(this, RECLAIM_VOLUME_KEYS_MS)
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        // Fired when the user revokes capture, e.g. from the status bar chip.
        override fun onStop() {
            AppLog.i(TAG, "Capture permission revoked by the system/user")
            stopCasting(stopSpeaker = true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stopCasting(stopSpeaker = true)
            ACTION_VOLUME_UP -> changeVolume(VOLUME_STEP)
            ACTION_VOLUME_DOWN -> changeVolume(-VOLUME_STEP)
            ACTION_SET_VOLUME -> setVolume(intent.getIntExtra(EXTRA_VOLUME, 0))
        }
        if (projection == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (projection != null) stopCasting(stopSpeaker = true)
        scope.cancel()
        super.onDestroy()
    }

    private fun start(intent: Intent) {
        if (projection != null) {
            AppLog.i(TAG, "Start ignored, already casting")
            return
        }
        val renderer = Prefs(this).renderer
        speaker = renderer?.name.orEmpty()
        // Must be called promptly after startForegroundService(), even if we bail out below.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(getString(R.string.notification_connecting, speaker)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (renderer == null || data == null) {
            fail(getString(R.string.error_projection))
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(getString(R.string.error_permission))
            return
        }
        AppLog.i(TAG, "Starting capture for ${renderer.name} (${renderer.location})")
        setStatus(CastState.Status.Connecting(speaker))

        val srv: StreamServer
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val mp = manager.getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), data)
                ?: throw IllegalStateException(getString(R.string.error_projection))
            projection = mp
            mp.registerCallback(projectionCallback, mainHandler)
            srv = StreamServer().also { it.start() }
            server = srv
            capture = CaptureEngine(mp, srv::push).also { it.start() }
        } catch (e: Exception) {
            AppLog.e(TAG, "Could not start capture", e)
            fail(e.message ?: e.toString())
            return
        }
        acquireLocks()
        mutePhone()
        session = scope.launch { runSession(renderer, srv) }
    }

    private suspend fun runSession(saved: Renderer, srv: StreamServer) {
        try {
            val ctl = UpnpControl(resolve(saved))
            control = ctl
            startPlayback(ctl, srv)
            val initialVolume = runCatching { ctl.volume() }.getOrNull()
            AppLog.i(TAG, "Casting, speaker volume $initialVolume")
            withContext(Dispatchers.Main) { onCasting(initialVolume) }
            coroutineScope {
                launch { volumeTarget.filterNotNull().collect { v -> runCatching { ctl.setVolume(v) } } }
                watchdog(ctl, srv)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "Casting failed", e)
            mainHandler.post { fail(e.message ?: e.toString()) }
        }
    }

    /** Uses the saved endpoints if they still answer, otherwise finds the speaker again by UDN (its IP may have changed). */
    private suspend fun resolve(saved: Renderer): Renderer {
        val check = runCatching { UpnpControl(saved).transportState() }
        if (check.isSuccess) return saved
        AppLog.w(TAG, "Saved speaker not reachable (${check.exceptionOrNull()?.message}), searching by UDN")
        val found = SsdpDiscovery.discover(this).firstOrNull { it.udn == saved.udn }
            ?: throw IOException(getString(R.string.error_not_found, saved.name))
        Prefs(this).renderer = found
        return found
    }

    private fun startPlayback(ctl: UpnpControl, srv: StreamServer) {
        val ip = localAddressTowards(ctl.renderer.host)
        // A fresh query string per attempt makes the speaker reconnect instead of reusing a dead stream.
        val url = "http://$ip:${srv.port}${StreamServer.PATH}?session=${++streamCounter}"
        AppLog.i(TAG, "Playing $url on ${ctl.renderer.name}")
        ctl.setAvTransportUri(url, UpnpControl.streamMetadata(url, WavFormat.MIME_TYPE, getString(R.string.app_name)))
        try {
            ctl.play()
        } catch (e: SocketTimeoutException) {
            // The speaker may still start; the watchdog retries if it doesn't.
            AppLog.w(TAG, "Play timed out, continuing")
        }
    }

    /** The phone's IP on the interface that routes to the speaker. */
    private fun localAddressTowards(host: String): String =
        DatagramSocket().use { socket ->
            socket.connect(InetAddress.getByName(host), 1900)
            socket.localAddress?.takeUnless { it.isAnyLocalAddress }?.hostAddress
                ?: throw IOException("No network route to $host")
        }

    /** Auto-reconnect: if the speaker stops playing our stream (Wi-Fi hiccup etc.), start it again. */
    private suspend fun watchdog(ctl: UpnpControl, srv: StreamServer) {
        val ours = ":${srv.port}${StreamServer.PATH}"
        var lastState: String? = null
        var tick = 0
        while (true) {
            delay(WATCHDOG_INTERVAL_MS)
            if (++tick % 6 == 0) logAudioLevel()
            val state: String?
            val uri: String?
            try {
                state = ctl.transportState()
                uri = ctl.currentUri()
            } catch (e: IOException) {
                AppLog.w(TAG, "Speaker not reachable: ${e.message}")
                continue
            }
            if (state != lastState) {
                AppLog.i(TAG, "Speaker state: $state, uri: $uri")
                lastState = state
            }
            if (!uri.isNullOrEmpty() && !uri.contains(ours)) {
                AppLog.i(TAG, "Speaker switched to another source ($uri), ending session")
                mainHandler.post { stopCasting(stopSpeaker = false) }
                return
            }
            if (state == "STOPPED" || state == "NO_MEDIA_PRESENT") {
                AppLog.i(TAG, "Speaker is $state, restarting stream")
                mainHandler.post { updateNotification(getString(R.string.notification_reconnecting, speaker)) }
                try {
                    startPlayback(ctl, srv)
                    mainHandler.post { updateNotification(getString(R.string.notification_casting, speaker)) }
                } catch (e: IOException) {
                    AppLog.w(TAG, "Reconnect failed: ${e.message}")
                }
            }
        }
    }

    private fun onCasting(volume: Int?) {
        if (projection == null) return // stopped in the meantime
        setStatus(CastState.Status.Casting(speaker))
        updateNotification(getString(R.string.notification_casting, speaker))
        CastState.volume.value = volume
        if (volume != null) {
            setupMediaSession(volume)
            mainHandler.postDelayed(reclaimVolumeKeys, RECLAIM_VOLUME_KEYS_MS)
        }
    }

    /** Diagnostics for shared logs: tells "the app captured nothing" apart from network problems. */
    private fun logAudioLevel() {
        val peak = capture?.takePeak() ?: return
        val dbfs = if (peak == 0) "silence" else "%.0f dBFS".format(20 * kotlin.math.log10(peak / 32768.0))
        AppLog.i(TAG, "Captured audio peak over the last minute: $dbfs")
    }

    private fun playbackState(state: Int): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build()

    /** A remote-playback media session routes the phone's volume keys to the speaker. */
    private fun setupMediaSession(volume: Int) {
        val provider = object : VolumeProviderCompat(VOLUME_CONTROL_ABSOLUTE, 100, volume) {
            override fun onSetVolumeTo(volume: Int) = setVolume(volume)
            override fun onAdjustVolume(direction: Int) {
                if (direction in -1..1) changeVolume(direction * VOLUME_STEP)
            }
        }
        volumeProvider = provider
        mediaSession = MediaSessionCompat(this, "SpeakerCast").apply {
            setPlaybackToRemote(provider)
            setPlaybackState(playbackState(PlaybackStateCompat.STATE_PLAYING))
            isActive = true
        }
    }

    private fun setVolume(volume: Int) {
        if (control == null) return
        val v = volume.coerceIn(0, 100)
        CastState.volume.value = v
        volumeProvider?.currentVolume = v
        volumeTarget.value = v
    }

    private fun changeVolume(delta: Int) {
        val current = CastState.volume.value ?: return
        setVolume(current + delta)
    }

    private fun stopCasting(stopSpeaker: Boolean) {
        if (projection != null) AppLog.i(TAG, "Stopping (stopSpeaker=$stopSpeaker)")
        session?.cancel()
        session = null
        val ctl = control
        control = null
        if (stopSpeaker && ctl != null) thread { runCatching { ctl.stop() } }
        capture?.stop()
        capture = null
        server?.stop()
        server = null
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null
        mainHandler.removeCallbacks(reclaimVolumeKeys)
        mediaSession?.release()
        mediaSession = null
        volumeProvider = null
        volumeTarget.value = null
        restorePhoneVolume()
        releaseLocks()
        CastState.volume.value = null
        setStatus(CastState.Status.Idle)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        AppLog.e(TAG, "Failed: $message")
        stopCasting(stopSpeaker = false)
        setStatus(CastState.Status.Error(message))
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun setStatus(status: CastState.Status) {
        CastState.status.value = status
        CastTileService.refresh(this)
    }

    /**
     * The speaker plays the audio, so start with the phone silent. Capture is unaffected by the phone's
     * volume, and the user may turn the phone up again at any time.
     */
    private fun mutePhone() {
        val audio = getSystemService(AudioManager::class.java)
        savedPhoneVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    }

    private fun restorePhoneVolume() {
        val saved = savedPhoneVolume ?: return
        savedPhoneVolume = null
        val audio = getSystemService(AudioManager::class.java)
        // Leave it alone if the user turned the phone up on purpose in the meantime.
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
        }
    }

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpeakerCast:cast")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val modes = listOf(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WifiManager.WIFI_MODE_FULL_LOW_LATENCY)
        for (mode in modes) {
            wifiLocks += wifi.createWifiLock(mode, "SpeakerCast:cast:$mode").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.release()
        wakeLock = null
        wifiLocks.forEach { it.release() }
        wifiLocks.clear()
    }

    private fun notification(title: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast_audio)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.action_volume_down), serviceAction(ACTION_VOLUME_DOWN))
            .addAction(0, getString(R.string.action_volume_up), serviceAction(ACTION_VOLUME_UP))
            .addAction(0, getString(R.string.action_stop), serviceAction(ACTION_STOP))
            .build()
    }

    private fun updateNotification(title: String) {
        if (projection == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title))
    }

    private fun serviceAction(action: String): PendingIntent =
        PendingIntent.getService(
            this, action.hashCode(), Intent(this, CastService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private const val TAG = "CastService"
        private const val CHANNEL_ID = "cast"
        private const val NOTIFICATION_ID = 1
        private const val VOLUME_STEP = 2
        private const val WATCHDOG_INTERVAL_MS = 10_000L
        private const val RECLAIM_VOLUME_KEYS_MS = 3_000L


        private const val ACTION_START = "at.seisi.simpleaudiostream.START"
        private const val ACTION_STOP = "at.seisi.simpleaudiostream.STOP"
        private const val ACTION_VOLUME_UP = "at.seisi.simpleaudiostream.VOLUME_UP"
        private const val ACTION_VOLUME_DOWN = "at.seisi.simpleaudiostream.VOLUME_DOWN"
        private const val ACTION_SET_VOLUME = "at.seisi.simpleaudiostream.SET_VOLUME"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_VOLUME = "volume"

        fun start(context: Context, resultCode: Int, data: Intent) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CastService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data),
            )
        }

        fun stop(context: Context) = send(context, Intent(context, CastService::class.java).setAction(ACTION_STOP))

        fun setVolume(context: Context, volume: Int) = send(
            context,
            Intent(context, CastService::class.java).setAction(ACTION_SET_VOLUME).putExtra(EXTRA_VOLUME, volume),
        )

        private fun send(context: Context, intent: Intent) {
            if (CastState.isActive) context.startService(intent)
        }
    }
}
