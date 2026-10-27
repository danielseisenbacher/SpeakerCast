package at.seisi.simpleaudiostream.upnp

import at.seisi.simpleaudiostream.upnp.DeviceDescription.AV_TRANSPORT
import at.seisi.simpleaudiostream.upnp.DeviceDescription.RENDERING_CONTROL

/** Blocking AVTransport / RenderingControl calls against one renderer. Call from a background thread. */
class UpnpControl(val renderer: Renderer) {

    fun setAvTransportUri(uri: String, metadata: String) {
        avTransport("SetAVTransportURI", "InstanceID" to "0", "CurrentURI" to uri, "CurrentURIMetaData" to metadata)
    }

    fun play() {
        avTransport("Play", "InstanceID" to "0", "Speed" to "1")
    }

    fun stop() {
        avTransport("Stop", "InstanceID" to "0")
    }

    /** PLAYING, STOPPED, PAUSED_PLAYBACK, TRANSITIONING or NO_MEDIA_PRESENT. */
    fun transportState(): String? =
        Soap.value(avTransport("GetTransportInfo", "InstanceID" to "0"), "CurrentTransportState")

    fun currentUri(): String? =
        Soap.value(avTransport("GetMediaInfo", "InstanceID" to "0"), "CurrentURI")

    fun volume(): Int? {
        val url = renderer.renderingControlUrl ?: return null
        val response = Soap.call(url, RENDERING_CONTROL, "GetVolume", "InstanceID" to "0", "Channel" to "Master")
        return Soap.value(response, "CurrentVolume")?.toIntOrNull()
    }

    fun setVolume(volume: Int) {
        val url = renderer.renderingControlUrl ?: return
        Soap.call(
            url, RENDERING_CONTROL, "SetVolume",
            "InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to volume.coerceIn(0, 100).toString(),
        )
    }

    private fun avTransport(action: String, vararg args: Pair<String, String>): String =
        Soap.call(renderer.avTransportUrl, AV_TRANSPORT, action, *args)

    companion object {
        /** DIDL-Lite metadata describing our live stream. */
        fun streamMetadata(url: String, mimeType: String, title: String): String =
            "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
                "<dc:title>${Soap.escape(title)}</dc:title>" +
                "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
                "<res protocolInfo=\"http-get:*:$mimeType:*\">${Soap.escape(url)}</res>" +
                "</item></DIDL-Lite>"
    }
}
