package at.seisi.simpleaudiostream

import android.app.Application
import android.os.Build
import at.seisi.simpleaudiostream.log.AppLog

class SpeakerCastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i("App", "Started on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
    }
}
