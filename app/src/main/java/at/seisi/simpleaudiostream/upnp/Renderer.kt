package at.seisi.simpleaudiostream.upnp

/** A UPnP MediaRenderer (e.g. a Sonos speaker) and the control endpoints we need. */
data class Renderer(
    val udn: String,
    val name: String,
    val model: String,
    val location: String,
    val avTransportUrl: String,
    val renderingControlUrl: String?,
) {
    val host: String get() = java.net.URL(location).host
}
