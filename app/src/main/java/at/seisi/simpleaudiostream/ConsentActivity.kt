package at.seisi.simpleaudiostream

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Invisible activity that asks Android for capture permission (the "start recording or casting"
 * dialog, required every session on Android 14+) and then hands the token to [CastService].
 */
class ConsentActivity : ComponentActivity() {
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                CastService.start(this, result.resultCode, data)
            } else {
                CastState.status.value = CastState.Status.Error(getString(R.string.error_projection))
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (Prefs(this).renderer == null || !hasPermission) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        if (savedInstanceState == null) {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Entire screen only: single-app sharing would only capture that one app's audio.
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
            projectionLauncher.launch(intent)
        }
    }
}
