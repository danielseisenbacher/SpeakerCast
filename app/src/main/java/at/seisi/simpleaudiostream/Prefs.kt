package at.seisi.simpleaudiostream

import android.content.Context
import androidx.core.content.edit
import at.seisi.simpleaudiostream.upnp.Renderer

class Prefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** The last speaker the user picked; the tile casts to it. */
    var renderer: Renderer?
        get() {
            val udn = prefs.getString(KEY_UDN, null) ?: return null
            return Renderer(
                udn = udn,
                name = prefs.getString(KEY_NAME, null) ?: return null,
                model = prefs.getString(KEY_MODEL, "") ?: "",
                location = prefs.getString(KEY_LOCATION, null) ?: return null,
                avTransportUrl = prefs.getString(KEY_AV_TRANSPORT, null) ?: return null,
                renderingControlUrl = prefs.getString(KEY_RENDERING_CONTROL, null),
            )
        }
        set(value) = prefs.edit {
            if (value == null) {
                listOf(KEY_UDN, KEY_NAME, KEY_MODEL, KEY_LOCATION, KEY_AV_TRANSPORT, KEY_RENDERING_CONTROL)
                    .forEach(::remove)
            } else {
                putString(KEY_UDN, value.udn)
                putString(KEY_NAME, value.name)
                putString(KEY_MODEL, value.model)
                putString(KEY_LOCATION, value.location)
                putString(KEY_AV_TRANSPORT, value.avTransportUrl)
                putString(KEY_RENDERING_CONTROL, value.renderingControlUrl)
            }
        }

    var mutePhone: Boolean
        get() = prefs.getBoolean(KEY_MUTE_PHONE, false)
        set(value) = prefs.edit { putBoolean(KEY_MUTE_PHONE, value) }

    private companion object {
        const val KEY_UDN = "renderer_udn"
        const val KEY_NAME = "renderer_name"
        const val KEY_MODEL = "renderer_model"
        const val KEY_LOCATION = "renderer_location"
        const val KEY_AV_TRANSPORT = "renderer_av_transport"
        const val KEY_RENDERING_CONTROL = "renderer_rendering_control"
        const val KEY_MUTE_PHONE = "mute_phone"
    }
}
