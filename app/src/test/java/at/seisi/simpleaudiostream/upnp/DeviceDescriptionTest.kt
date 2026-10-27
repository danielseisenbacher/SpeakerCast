package at.seisi.simpleaudiostream.upnp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceDescriptionTest {
    // Trimmed from a Sonos Play:1 /xml/device_description.xml
    private val sonos = """
        <?xml version="1.0" encoding="utf-8" ?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
            <friendlyName>192.168.1.23 - Sonos Play:1 - RINCON_000E58AABBCC01400</friendlyName>
            <manufacturer>Sonos, Inc.</manufacturer>
            <modelName>Sonos Play:1</modelName>
            <UDN>uuid:RINCON_000E58AABBCC01400</UDN>
            <roomName>Wohnzimmer</roomName>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AlarmClock:1</serviceType>
                <controlURL>/AlarmClock/Control</controlURL>
              </service>
            </serviceList>
            <deviceList>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
                <UDN>uuid:RINCON_000E58AABBCC01400_MS</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                    <controlURL>/MediaServer/ContentDirectory/Control</controlURL>
                  </service>
                </serviceList>
              </device>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <UDN>uuid:RINCON_000E58AABBCC01400_MR</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <controlURL>/MediaRenderer/RenderingControl/Control</controlURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>/MediaRenderer/AVTransport/Control</controlURL>
                  </service>
                </serviceList>
              </device>
            </deviceList>
          </device>
        </root>
    """.trimIndent().toByteArray()

    @Test
    fun parsesSonosDescription() {
        val renderer = DeviceDescription.parse(sonos, "http://192.168.1.23:1400/xml/device_description.xml")
        assertNotNull(renderer)
        renderer!!
        assertEquals("uuid:RINCON_000E58AABBCC01400", renderer.udn)
        assertEquals("Wohnzimmer", renderer.name)
        assertEquals("Sonos Play:1", renderer.model)
        assertEquals("http://192.168.1.23:1400/MediaRenderer/AVTransport/Control", renderer.avTransportUrl)
        assertEquals("http://192.168.1.23:1400/MediaRenderer/RenderingControl/Control", renderer.renderingControlUrl)
        assertEquals("192.168.1.23", renderer.host)
    }

    @Test
    fun ignoresDevicesWithoutAvTransport() {
        val xml = """
            <root><device><friendlyName>NAS</friendlyName><UDN>uuid:nas</UDN>
            <serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
            <controlURL>/cd</controlURL></service></serviceList></device></root>
        """.trimIndent().toByteArray()
        assertNull(DeviceDescription.parse(xml, "http://10.0.0.2:8200/desc.xml"))
    }

    @Test
    fun parsesSsdpLocation() {
        val response = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age = 1800\r\n" +
            "Location: http://192.168.1.23:1400/xml/device_description.xml\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        assertEquals("http://192.168.1.23:1400/xml/device_description.xml", SsdpDiscovery.parseLocation(response))
    }
}
