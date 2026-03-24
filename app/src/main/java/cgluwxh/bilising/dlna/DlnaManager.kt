package cgluwxh.bilising.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.TimeUnit

class DlnaManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private var multicastLock: WifiManager.MulticastLock? = null

    companion object {
        private const val TAG = "DlnaManager"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SSDP_LISTEN_MS = 12000
        private val MANUAL_SCAN_PORTS = listOf(1234, 7676, 49152, 8200, 8080, 52235, 9000)
        private val MANUAL_SCAN_PATHS = listOf(
            "/description.xml",
            "/rootDesc.xml",
            "/device.xml",
            "/xml/device_description.xml",
            "/MediaRenderer/desc.xml",
            "/DeviceDescription.xml",
            "/"
        )
    }

    // ── Device discovery via SSDP ──────────────────────────────────────────

    /**
     * Streams DLNA devices as they are discovered.
     * [onDeviceFound] is called on the IO thread whenever a new device is confirmed.
     * The function returns (or is cancelled) after [SSDP_LISTEN_MS] ms or when the
     * caller's coroutine is cancelled (e.g. user selected a device).
     */
    suspend fun discoverDevices(onDeviceFound: (DlnaDevice) -> Unit) = withContext(Dispatchers.IO) {
        acquireMulticastLock()
        val sockets = mutableListOf<DatagramSocket>()
        try {
            supervisorScope {
                val seenLocations = mutableSetOf<String>()
                val group = InetAddress.getByName(SSDP_ADDRESS)
                val localAddrs = getAllLocalAddresses()

                if (localAddrs.isEmpty()) {
                    Log.w(TAG, "No usable network interfaces found")
                    return@supervisorScope
                }

                val stValues = listOf(
                    "urn:schemas-upnp-org:service:AVTransport:1",
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "ssdp:all"
                )

                // Open one socket per interface and fire M-SEARCH packets
                for ((addr, name) in localAddrs) {
                    try {
                        val s = DatagramSocket(InetSocketAddress(addr, 0))
                        s.soTimeout = 1500
                        sockets.add(s)
                        Log.d(TAG, "Sending M-SEARCH on $name (${addr.hostAddress})")
                        for (st in stValues) {
                            val msearch = "M-SEARCH * HTTP/1.1\r\n" +
                                    "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                                    "MAN: \"ssdp:discover\"\r\n" +
                                    "MX: 3\r\n" +
                                    "ST: $st\r\n\r\n"
                            val data = msearch.toByteArray(Charsets.UTF_8)
                            repeat(2) {
                                try { s.send(DatagramPacket(data, data.size, group, SSDP_PORT)); Thread.sleep(30) }
                                catch (e: Exception) { Log.w(TAG, "Send on $name: ${e.message}") }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Socket setup failed for $name: ${e.message}")
                    }
                }

                // Round-robin receive; launch a description fetch immediately per new LOCATION
                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + SSDP_LISTEN_MS
                while (isActive && System.currentTimeMillis() < deadline) {
                    var anyReceived = false
                    for (s in sockets) {
                        if (!isActive) break
                        try {
                            val pkt = DatagramPacket(buf, buf.size)
                            s.receive(pkt)
                            val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                            Log.d(TAG, "SSDP response from ${pkt.address}: ${text.take(200)}")
                            val location = extractHeader(text, "LOCATION") ?: continue
                            if (seenLocations.add(location)) {
                                // Fetch description in parallel – don't block the receive loop
                                launch {
                                    try {
                                        val device = fetchDeviceDescription(location)
                                        if (device != null) {
                                            Log.d(TAG, "Device found: ${device.name} @ ${device.ipAddress}")
                                            onDeviceFound(device)
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to fetch $location: ${e.message}")
                                    }
                                }
                            }
                            anyReceived = true
                        } catch (e: SocketTimeoutException) { /* normal */ }
                    }
                    if (!anyReceived) Thread.sleep(50)
                }
                Log.d(TAG, "SSDP listen finished, seen ${seenLocations.size} location(s)")
            }
        } finally {
            sockets.forEach { try { it.close() } catch (_: Exception) {} }
            releaseMulticastLock()
        }
    }

    private fun acquireMulticastLock() {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("bilising_dlna").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        try { multicastLock?.release() } catch (e: Exception) { Log.w(TAG, "releaseMulticastLock", e) }
        multicastLock = null
    }

    /**
     * Returns ALL active IPv4 addresses across every non-loopback, non-cellular interface.
     * No name filter – covers wlan0, ap0, swlan0, softap0, bridge0, eth0, rndis0, usb0, …
     */
    private fun getAllLocalAddresses(): List<Pair<InetAddress, String>> {
        val result = mutableListOf<Pair<InetAddress, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                // Only skip cellular data (rmnet*) and VPN tunnels (tun*, ppp*)
                if (name.startsWith("rmnet") || name.startsWith("tun") || name.startsWith("ppp")) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        Log.d(TAG, "Found interface: $name  addr: ${addr.hostAddress}")
                        result.add(addr to name)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getAllLocalAddresses failed", e)
        }
        return result
    }


    // ── Manual IP scan ────────────────────────────────────────────────────

    /**
     * Given a user-supplied address ("192.168.1.100" or "192.168.1.100:1234"):
     * 1. Sends a unicast UDP M-SEARCH to ip:1900 – most DLNA devices reply with LOCATION
     * 2. Falls back to HTTP GET on common ports/paths if no UDP reply
     */
    suspend fun addDeviceByIp(input: String): DlnaDevice? = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        val host: String
        val fixedPort: Int?
        if (trimmed.contains(":")) {
            val parts = trimmed.split(":", limit = 2)
            host = parts[0]; fixedPort = parts[1].toIntOrNull()
        } else {
            host = trimmed; fixedPort = null
        }

        Log.d(TAG, "Manual add: host=$host fixedPort=$fixedPort")

        // ── Step 1: unicast SSDP M-SEARCH → ip:1900 ──────────────────────
        if (fixedPort == null) {
            val location = unicastSsdpSearch(host)
            if (location != null) {
                Log.d(TAG, "Unicast SSDP returned LOCATION: $location")
                try {
                    val device = fetchDeviceDescription(location)
                    if (device != null) return@withContext device
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch description from $location: ${e.message}")
                }
            }
        }

        // ── Step 2: HTTP port scan ────────────────────────────────────────
        val ports = if (fixedPort != null) listOf(fixedPort) else MANUAL_SCAN_PORTS
        for (port in ports) {
            for (path in MANUAL_SCAN_PATHS) {
                val url = "http://$host:$port$path"
                try {
                    val device = fetchDeviceDescription(url)
                    if (device != null) {
                        Log.d(TAG, "HTTP scan found device at $url: ${device.name}")
                        return@withContext device
                    }
                } catch (e: Exception) {
                    Log.v(TAG, "No device at $url: ${e.message}")
                }
            }
        }

        Log.w(TAG, "Manual scan found no DLNA device at $host")
        null
    }

    /**
     * Sends M-SEARCH to ip:1900 (unicast UDP).  Returns the LOCATION header value
     * from the first valid SSDP response, or null on timeout.
     */
    private fun unicastSsdpSearch(host: String): String? {
        val stValues = listOf(
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "ssdp:all"
        )
        try {
            val target = InetAddress.getByName(host)
            val socket = DatagramSocket()
            socket.soTimeout = 3000
            try {
                for (st in stValues) {
                    val msearch = "M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: $host:$SSDP_PORT\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 2\r\n" +
                            "ST: $st\r\n" +
                            "\r\n"
                    val data = msearch.toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(data, data.size, target, SSDP_PORT))
                }
                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + 6000
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        socket.receive(pkt)
                        val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                        Log.d(TAG, "Unicast SSDP reply: ${text.take(300)}")
                        val loc = extractHeader(text, "LOCATION")
                        if (loc != null) return loc
                    } catch (e: SocketTimeoutException) { /* retry */ }
                }
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "unicastSsdpSearch failed: ${e.message}")
        }
        return null
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun extractHeader(response: String, header: String): String? {
        val lines = response.split("\r\n", "\n")
        for (line in lines) {
            if (line.uppercase().startsWith("${header.uppercase()}:")) {
                return line.substring(header.length + 1).trim()
            }
        }
        return null
    }

    private fun fetchDeviceDescription(location: String): DlnaDevice? {
        val request = Request.Builder().url(location).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return null
        val ipAddress = try { java.net.URL(location).host } catch (e: Exception) { "unknown" }
        return parseDeviceXml(body, location, ipAddress)
    }

    private fun parseDeviceXml(xml: String, location: String, ipAddress: String): DlnaDevice? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var friendlyName = ""
            var udn = ""
            var avTransportControlUrl = ""
            var currentServiceType = ""
            var currentControlUrl = ""
            var inService = false

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name?.lowercase()) {
                        "service" -> { inService = true; currentServiceType = ""; currentControlUrl = "" }
                        "friendlyname" -> if (!inService && friendlyName.isEmpty()) friendlyName = parser.nextText()
                        "udn" -> if (!inService && udn.isEmpty()) udn = parser.nextText()
                        "servicetype" -> if (inService) currentServiceType = parser.nextText()
                        "controlurl" -> if (inService) currentControlUrl = parser.nextText()
                    }
                    XmlPullParser.END_TAG -> if (parser.name?.lowercase() == "service" && inService) {
                        if (currentServiceType.contains("AVTransport") && currentControlUrl.isNotEmpty()) {
                            avTransportControlUrl = resolveUrl(location, currentControlUrl)
                        }
                        inService = false
                    }
                }
                event = parser.next()
            }

            if (avTransportControlUrl.isEmpty()) return null

            DlnaDevice(
                name = friendlyName.ifEmpty { ipAddress },
                ipAddress = ipAddress,
                controlUrl = avTransportControlUrl,
                udn = udn
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse device XML", e)
            null
        }
    }

    private fun resolveUrl(base: String, path: String): String {
        if (path.startsWith("http")) return path
        val baseUrl = java.net.URL(base)
        return if (path.startsWith("/")) "${baseUrl.protocol}://${baseUrl.authority}$path"
        else "${baseUrl.protocol}://${baseUrl.authority}/$path"
    }

    // ── Playback control ──────────────────────────────────────────────────

    suspend fun setAVTransportURI(device: DlnaDevice, uri: String, title: String) = withContext(Dispatchers.IO) {
        val metadata = buildDIDL(title, uri)
        val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <CurrentURI>${escapeXml(uri)}</CurrentURI>
      <CurrentURIMetaData>${escapeXml(metadata)}</CurrentURIMetaData>
    </u:SetAVTransportURI>
  </s:Body>
</s:Envelope>"""
        sendSoapAction(device, "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", soapBody)
    }

    suspend fun play(device: DlnaDevice) = withContext(Dispatchers.IO) {
        val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <Speed>1</Speed>
    </u:Play>
  </s:Body>
</s:Envelope>"""
        sendSoapAction(device, "urn:schemas-upnp-org:service:AVTransport:1#Play", soapBody)
    }

    suspend fun stop(device: DlnaDevice) = withContext(Dispatchers.IO) {
        val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
    </u:Stop>
  </s:Body>
</s:Envelope>"""
        sendSoapAction(device, "urn:schemas-upnp-org:service:AVTransport:1#Stop", soapBody)
    }

    suspend fun getTransportState(device: DlnaDevice): String = withContext(Dispatchers.IO) {
        val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetTransportInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
    </u:GetTransportInfo>
  </s:Body>
</s:Envelope>"""
        val response = sendSoapAction(device, "urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo", soapBody)
        extractXmlValue(response, "CurrentTransportState") ?: "UNKNOWN"
    }

    suspend fun pause(device: DlnaDevice) = withContext(Dispatchers.IO) {
        val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Pause xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
    </u:Pause>
  </s:Body>
</s:Envelope>"""
        sendSoapAction(device, "urn:schemas-upnp-org:service:AVTransport:1#Pause", soapBody)
    }

    data class PositionInfo(val positionMs: Long, val durationMs: Long)

    suspend fun getPositionInfo(device: DlnaDevice): PositionInfo = withContext(Dispatchers.IO) {
        val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetPositionInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
    </u:GetPositionInfo>
  </s:Body>
</s:Envelope>"""
        val response = sendSoapAction(device, "urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo", soapBody)
        PositionInfo(
            positionMs = parseTime(extractXmlValue(response, "RelTime")),
            durationMs = parseTime(extractXmlValue(response, "TrackDuration"))
        )
    }

    suspend fun seek(device: DlnaDevice, positionMs: Long) = withContext(Dispatchers.IO) {
        val target = formatTimeHms(positionMs)
        val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Seek xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <Unit>REL_TIME</Unit>
      <Target>$target</Target>
    </u:Seek>
  </s:Body>
</s:Envelope>"""
        sendSoapAction(device, "urn:schemas-upnp-org:service:AVTransport:1#Seek", soapBody)
    }

    /** Parse "H:MM:SS" or "HH:MM:SS" → milliseconds.  Returns 0 for missing/invalid values. */
    private fun parseTime(time: String?): Long {
        if (time == null || time.startsWith("NOT_IMPL")) return 0L
        val parts = time.trim().split(":")
        if (parts.size != 3) return 0L
        val h = parts[0].toLongOrNull() ?: return 0L
        val m = parts[1].toLongOrNull() ?: return 0L
        val s = parts[2].toLongOrNull() ?: return 0L
        return (h * 3600 + m * 60 + s) * 1000L
    }

    /** Format milliseconds → "HH:MM:SS" for DLNA Seek Target. */
    private fun formatTimeHms(ms: Long): String {
        val sec = ms / 1000
        return "%02d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)
    }

    private fun sendSoapAction(device: DlnaDevice, action: String, body: String): String {
        val requestBody = body.toRequestBody("text/xml; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(device.controlUrl)
            .post(requestBody)
            .addHeader("SOAPACTION", "\"$action\"")
            .addHeader("Content-Type", "text/xml; charset=utf-8")
            .build()
        val response = client.newCall(request).execute()
        return response.body?.string() ?: ""
    }

    private fun buildDIDL(title: String, uri: String) =
        """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="false"><dc:title>${escapeXml(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:*:*">${escapeXml(uri)}</res></item></DIDL-Lite>"""

    private fun escapeXml(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun extractXmlValue(xml: String, tagName: String): String? =
        Regex("<$tagName>(.*?)</$tagName>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
}
