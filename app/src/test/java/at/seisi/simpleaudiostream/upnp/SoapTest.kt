package at.seisi.simpleaudiostream.upnp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoapTest {
    @Test
    fun buildsEnvelopeWithEscapedArguments() {
        val xml = Soap.envelope(
            DeviceDescription.AV_TRANSPORT, "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURIMetaData" to "<DIDL-Lite a=\"b\">&</DIDL-Lite>"),
        )
        assertTrue(xml.contains("<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        assertTrue(xml.contains("<InstanceID>0</InstanceID>"))
        assertTrue(xml.contains("<CurrentURIMetaData>&lt;DIDL-Lite a=&quot;b&quot;&gt;&amp;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>"))
    }

    @Test
    fun extractsResponseValues() {
        val response = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <CurrentTransportState>PLAYING</CurrentTransportState>
            <CurrentTransportStatus>OK</CurrentTransportStatus>
            </u:GetTransportInfoResponse></s:Body></s:Envelope>
        """.trimIndent()
        assertEquals("PLAYING", Soap.value(response, "CurrentTransportState"))
        assertEquals("a&b", Soap.value("<CurrentURI>a&amp;b</CurrentURI>", "CurrentURI"))
    }

    @Test
    fun metadataEscapesUrl() {
        val didl = UpnpControl.streamMetadata("http://1.2.3.4:5/stream.wav?a=1&b=2", "audio/wav", "Test")
        assertTrue(didl.contains("<res protocolInfo=\"http-get:*:audio/wav:*\">http://1.2.3.4:5/stream.wav?a=1&amp;b=2</res>"))
    }
}
