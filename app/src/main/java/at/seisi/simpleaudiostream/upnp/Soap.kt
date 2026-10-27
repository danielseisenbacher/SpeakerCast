package at.seisi.simpleaudiostream.upnp

import at.seisi.simpleaudiostream.log.AppLog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class UpnpException(action: String, httpCode: Int, errorCode: String?, description: String?) :
    IOException("$action failed (HTTP $httpCode${errorCode?.let { ", UPnP error $it" } ?: ""}${description?.let { ": $it" } ?: ""})")

/** Minimal SOAP client for UPnP actions. */
object Soap {
    fun envelope(serviceType: String, action: String, args: List<Pair<String, String>>): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:$action xmlns:u=\"$serviceType\">")
            for ((name, value) in args) append("<$name>${escape(value)}</$name>")
            append("</u:$action></s:Body></s:Envelope>")
        }

    fun call(
        controlUrl: String,
        serviceType: String,
        action: String,
        vararg args: Pair<String, String>,
        readTimeoutMs: Int = 6000,
    ): String {
        val body = envelope(serviceType, action, args.toList()).toByteArray(Charsets.UTF_8)
        // The watchdog polls these every few seconds; don't flood the log with them.
        if (action !in QUIET_ACTIONS) AppLog.i("Soap", "$action ${args.joinToString { "${it.first}=${it.second.take(120)}" }}")
        val conn = URL(controlUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4000
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                AppLog.w("Soap", "$action -> HTTP $code: ${text.take(500)}")
                throw UpnpException(action, code, value(text, "errorCode"), value(text, "errorDescription"))
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private val QUIET_ACTIONS = setOf("GetTransportInfo", "GetMediaInfo")

    /** Extracts the (unescaped) text of the first element named [tag], ignoring namespace prefixes. */
    fun value(xml: String, tag: String): String? =
        Regex("<(?:\\w+:)?$tag(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?$tag>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.let(::unescape)

    fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
