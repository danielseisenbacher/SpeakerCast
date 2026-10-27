package at.seisi.simpleaudiostream.upnp

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/** Fetches and parses a UPnP device description (the XML behind an SSDP LOCATION header). */
object DeviceDescription {
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"

    fun fetch(location: String): Renderer? {
        val conn = URL(location).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val xml = conn.inputStream.use { it.readBytes() }
            return parse(xml, location)
        } finally {
            conn.disconnect()
        }
    }

    /** Returns null if the device has no AVTransport service, i.e. it can't play a URL. */
    fun parse(xml: ByteArray, location: String): Renderer? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val root = doc.documentElement

        // The first occurrence in document order belongs to the root device.
        fun first(tag: String): String? =
            root.getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

        var avTransport: String? = null
        var renderingControl: String? = null
        val services = root.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val service = services.item(i) as Element
            val type = service.childText("serviceType")
            val control = service.childText("controlURL") ?: continue
            when (type) {
                AV_TRANSPORT -> if (avTransport == null) avTransport = control
                RENDERING_CONTROL -> if (renderingControl == null) renderingControl = control
            }
        }
        if (avTransport == null) return null

        val base = first("URLBase")?.let { URL(it) } ?: URL(location)
        return Renderer(
            udn = first("UDN") ?: location,
            // Sonos exposes the user-facing room name ("Living Room") separately.
            name = first("roomName") ?: first("friendlyName") ?: base.host,
            model = first("modelName") ?: "",
            location = location,
            avTransportUrl = URL(base, avTransport).toString(),
            renderingControlUrl = renderingControl?.let { URL(base, it).toString() },
        )
    }

    private fun Element.childText(tag: String): String? =
        getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}
