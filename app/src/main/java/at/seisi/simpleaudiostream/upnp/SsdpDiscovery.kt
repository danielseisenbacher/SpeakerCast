package at.seisi.simpleaudiostream.upnp

import android.content.Context
import android.net.wifi.WifiManager
import at.seisi.simpleaudiostream.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Finds UPnP MediaRenderers on the local network via SSDP M-SEARCH. */
object SsdpDiscovery {
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private val SEARCH_TARGETS = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:device:ZonePlayer:1",
    )

    suspend fun discover(context: Context, timeoutMs: Long = 3000): List<Renderer> =
        withContext(Dispatchers.IO) {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            val lock = wifi.createMulticastLock("SpeakerCast:ssdp").apply {
                setReferenceCounted(false)
                acquire()
            }
            val locations = try {
                collectLocations(timeoutMs)
            } finally {
                lock.release()
            }
            val renderers = coroutineScope {
                locations.map { location ->
                    async {
                        runCatching { DeviceDescription.fetch(location) }
                            .onFailure { AppLog.w("Discovery", "Could not read $location: $it") }
                            .getOrNull()
                    }
                }.awaitAll()
            }.filterNotNull().distinctBy { it.udn }.sortedBy { it.name.lowercase() }
            AppLog.i(
                "Discovery",
                "${locations.size} devices answered, renderers: " +
                    renderers.joinToString { "${it.name} (${it.model}, ${it.location})" },
            )
            renderers
        }

    private fun collectLocations(timeoutMs: Long): Set<String> {
        val locations = linkedSetOf<String>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 300
            val group = InetAddress.getByName(SSDP_ADDRESS)
            repeat(2) {
                for (target in SEARCH_TARGETS) {
                    val msg = searchMessage(target).toByteArray()
                    socket.send(DatagramPacket(msg, msg.size, group, SSDP_PORT))
                }
            }
            val buffer = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                parseLocation(String(packet.data, 0, packet.length))?.let { locations += it }
            }
        }
        return locations
    }

    fun searchMessage(target: String): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $target\r\n\r\n"

    fun parseLocation(response: String): String? =
        response.lineSequence()
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.startsWith("http") }
}
