package dev.sukbeomh.beta7compact.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import dev.sukbeomh.beta7compact.core.CameraObject
import dev.sukbeomh.beta7compact.remote.PtpIp
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.net.SocketFactory

internal data class WifiCardPhoto(
    val id: String,
    val sourceName: String,
    val name: String,
    val cameraTime: String?,
    val originalSize: Long?,
    val download: URI,
    val thumbnail: URI?,
    val converted: Boolean,
) {
    val captured: Long get() = CameraObject.ptpTime(cameraTime.orEmpty())
}

/** Sony's smartphone-selection mode exposes UPnP ContentDirectory, separate from PC Remote. */
internal class SonyWifiCard(private val address: InetAddress, private val sockets: SocketFactory,
                            private val wifiSource: InetAddress? = null) {
    private val host = checkNotNull(address.hostAddress)
    private val directory = URI("http://$host:64321/upnp/control/ContentDirectory")

    private fun verifySonyDirectory() {
        val description = URI("http://$host:64321/DmsDescPush.xml")
        val xml = request(description, 64L * 1024) { response, length ->
            response.readExact(length.toInt()).toString(Charsets.UTF_8)
        }
        var manufacturer: String? = null
        var photoRoot: String? = null
        parse(xml) {
            if (eventType == XmlPullParser.START_TAG) when (name) {
                "manufacturer" -> manufacturer = nextText()
                "photoRoot" -> photoRoot = nextText()
            }
        }
        check(manufacturer == "Sony Corporation" && photoRoot == "PhotoRoot") {
            "카메라의 스마트폰에서 선택 모드를 확인하세요"
        }
    }

    fun list(active: () -> Boolean): List<WifiCardPhoto> {
        val photos = linkedMapOf<String, WifiCardPhoto>()
        val folders = ArrayDeque(listOf("PhotoRoot"))
        val seenFolders = mutableSetOf<String>()
        while (folders.isNotEmpty()) {
            check(active()) { "사진 목록 조회를 중단했습니다" }
            val folder = folders.removeFirst()
            if (!seenFolders.add(folder)) continue
            check(seenFolders.size <= 10_000) { "카메라 폴더가 너무 많습니다" }
            var start = 0
            do {
                check(active()) { "사진 목록 조회를 중단했습니다" }
                val page = browse(folder, start)
                page.folders.forEach(folders::addLast)
                page.photos.forEach { photos.putIfAbsent(it.id, it) }
                start += page.returned
                check(start <= 100_000 && photos.size <= 100_000) { "카메라 사진이 너무 많습니다" }
                if (page.returned == 0 && start < page.total) throw IOException("카메라 목록이 변경되었습니다. 목록을 다시 읽으세요")
            } while (start < page.total)
        }
        return photos.values.sortedWith(compareByDescending<WifiCardPhoto> { it.captured }.thenByDescending { it.sourceName })
    }

    fun thumbnail(photo: WifiCardPhoto): ByteArray? = photo.thumbnail?.let { url ->
        request(url, 512L * 1024) { reply, length -> reply.readExact(length.toInt()) }
    }

    fun download(photo: WifiCardPhoto, destination: File, active: () -> Boolean): Long =
        request(photo.download, 256L * 1024 * 1024) { reply, length ->
            check(length > 2 && (photo.originalSize == null || photo.originalSize == length)) {
                "카메라가 보고한 사진 크기와 전송 크기가 다릅니다"
            }
            val signature = reply.readExact(2)
            check(signature[0] == 0xff.toByte() && signature[1] == 0xd8.toByte()) { "카메라가 JPEG 사진을 보내지 않았습니다" }
            destination.outputStream().buffered().use { out ->
                out.write(signature)
                val buffer = ByteArray(64 * 1024)
                var remaining = length - signature.size
                while (remaining > 0) {
                    check(active()) { "사진 전송을 중단했습니다" }
                    val count = reply.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (count < 0) throw IOException("사진 전송이 끝나기 전에 연결이 끊겼습니다")
                    out.write(buffer, 0, count)
                    remaining -= count
                }
            }
            length
        }

    private data class Page(val folders: List<String>, val photos: List<WifiCardPhoto>, val returned: Int, val total: Int)
    private data class Resource(val uri: URI, val size: Long?, val protocol: String)

    private fun browse(id: String, start: Int): Page {
        val service = "urn:schemas-upnp-org:service:ContentDirectory:1"
        val envelope = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body><u:Browse xmlns:u="$service"><ObjectID>${xmlEscape(id)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag>
            <Filter>*</Filter><StartingIndex>$start</StartingIndex><RequestedCount>64</RequestedCount><SortCriteria/></u:Browse></s:Body></s:Envelope>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val xml = request(directory, 4L * 1024 * 1024, envelope, "$service#Browse") { response, length ->
            response.readExact(length.toInt()).toString(Charsets.UTF_8)
        }
        var result: String? = null
        var returned: Int? = null
        var total: Int? = null
        parse(xml) {
            if (eventType == XmlPullParser.START_TAG) when (name) {
                "Result" -> result = nextText()
                "NumberReturned" -> returned = nextText().toIntOrNull()
                "TotalMatches" -> total = nextText().toIntOrNull()
            }
        }
        val count = requireNotNull(returned).also { require(it in 0..64) }
        val matched = requireNotNull(total).also { require(it in count..100_000) }
        val folders = mutableListOf<String>()
        val photos = mutableListOf<WifiCardPhoto>()
        var item = ""
        var title = ""
        var date = ""
        val candidates = mutableListOf<Resource>()
        parse(requireNotNull(result)) {
            if (eventType == XmlPullParser.START_TAG) when (name) {
                "container" -> getAttributeValue(null, "id")?.takeIf { it.isNotBlank() && it.length <= 256 }?.let(folders::add)
                "item" -> { item = getAttributeValue(null, "id").orEmpty(); title = ""; date = ""; candidates.clear() }
                "title" -> if (item.isNotEmpty()) title = nextText()
                "date" -> if (item.isNotEmpty()) date = nextText()
                "res" -> if (item.isNotEmpty()) {
                    val protocol = getAttributeValue(null, "protocolInfo").orEmpty()
                    val size = getAttributeValue(null, "size")?.toLongOrNull()?.takeIf { it > 0 }
                    val url = runCatching { URI(nextText().trim()).also(::verifyEndpoint) }.getOrNull()
                    if (url != null && protocol.startsWith("http-get:*:image/jpeg:", ignoreCase = true)) {
                        candidates += Resource(url, size, protocol)
                    }
                }
            }
            if (eventType == XmlPullParser.END_TAG && name == "item") {
                makePhoto(item, title, date, candidates)?.let(photos::add)
                item = ""
            }
        }
        return Page(folders, photos, count, matched)
    }

    private fun makePhoto(id: String, title: String, date: String, resources: List<Resource>): WifiCardPhoto? {
        if (id.isBlank() || id.length > 256 || title.length !in 1..240 || title.any { it == '/' || it == '\\' || it.code < 32 }) return null
        val extension = title.substringAfterLast('.', "").lowercase()
        val converted = extension == "arw"
        val target = when (extension) {
            "jpg", "jpeg" -> resources.filter { it.size != null && "DLNA.ORG_CI=1" !in it.protocol }.maxByOrNull { it.size ?: 0 }
            "arw" -> resources.firstOrNull { "DLNA.ORG_PN=JPEG_LRG" in it.protocol }
            else -> null
        } ?: return null
        val timestamp = runCatching { LocalDateTime.parse(date.take(19)).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")) }.getOrNull()
        val thumbnail = resources.firstOrNull { "DLNA.ORG_PN=JPEG_TN" in it.protocol }?.uri
        return WifiCardPhoto(id, title, if (converted) title.substringBeforeLast('.') + "-raw.jpg" else title,
            timestamp, target.size, target.uri, thumbnail, converted)
    }

    private fun verifyEndpoint(uri: URI) {
        require(uri.scheme == "http" && uri.host == host && uri.userInfo == null && uri.fragment == null && uri.port in 1..65535)
        require(target(uri).all { it.code in 33..126 })
    }

    private fun target(uri: URI): String = uri.rawPath.orEmpty().ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")

    private fun <T> request(uri: URI, limit: Long, body: ByteArray? = null, action: String? = null,
                            receive: (InputStream, Long) -> T): T {
        verifyEndpoint(uri)
        return openSocket().use { socket ->
            socket.connect(InetSocketAddress(address, uri.port), 5_000)
            socket.soTimeout = 10_000
            val request = buildString {
                append(if (body == null) "GET " else "POST ").append(target(uri)).append(" HTTP/1.0\r\n")
                append("Host: $host:${uri.port}\r\nConnection: close\r\n")
                if (body != null) append("SOAPAction: \"$action\"\r\nContent-Type: text/xml; charset=utf-8\r\nContent-Length: ${body.size}\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII)
            socket.getOutputStream().apply { write(request); if (body != null) write(body); flush() }
            val input = BufferedInputStream(socket.getInputStream())
            val status = input.headerLine().split(' ').getOrNull(1)?.toIntOrNull()
            if (status != 200) throw IOException("카메라가 요청을 받아들이지 않았습니다 (HTTP ${status ?: "응답 오류"})")
            var length: Long? = null
            var complete = false
            for (ignored in 0 until 64) {
                val line = input.headerLine()
                if (line.isEmpty()) { complete = true; break }
                if (line.substringBefore(':').equals("Content-Length", ignoreCase = true)) {
                    val current = line.substringAfter(':', "").trim().toLongOrNull()
                    check(current != null && (length == null || length == current)) { "카메라 응답 크기를 확인할 수 없습니다" }
                    length = current
                }
                check(!line.substringBefore(':').equals("Transfer-Encoding", ignoreCase = true)) { "카메라가 지원하지 않는 응답 형식을 보냈습니다" }
            }
            check(complete && length != null && length in 1..limit) { "카메라 응답 크기를 확인할 수 없습니다" }
            receive(input, length)
        }
    }

    private fun openSocket(): Socket {
        try {
            return sockets.createSocket()
        } catch (denied: SocketException) {
            // Some phones reject network-marked sockets on manually joined camera networks.
            // Use only the verified Wi-Fi link's local IPv4 address for the fallback.
            val source = wifiSource ?: throw denied
            val socket = Socket()
            try {
                socket.bind(InetSocketAddress(source, 0))
                return socket
            } catch (failure: IOException) {
                socket.close()
                throw failure
            }
        }
    }

    private fun InputStream.headerLine(): String {
        val line = StringBuilder()
        repeat(8192) {
            val byte = read()
            if (byte == 10) return line.toString().removeSuffix("\r")
            if (byte < 0) throw IOException("카메라 응답이 중단되었습니다")
            line.append(byte.toChar())
        }
        throw IOException("카메라 HTTP 응답이 너무 깁니다")
    }

    private fun InputStream.readExact(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(bytes, offset, size - offset)
            if (count < 0) throw IOException("카메라 전송이 중단되었습니다")
            offset += count
        }
        return bytes
    }

    private fun parse(xml: String, element: XmlPullParser.() -> Unit) {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(xml.reader())
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            require(parser.eventType != XmlPullParser.DOCDECL) { "카메라 XML 형식을 지원하지 않습니다" }
            parser.element()
            parser.nextToken()
        }
    }

    private fun xmlEscape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        @Suppress("DEPRECATION")
        fun connected(context: Context, localAddress: String): SonyWifiCard {
            val address = InetAddress.getByAddress(requireNotNull(PtpIp.localAddress(localAddress)) { "카메라의 사설 IPv4 주소를 입력하세요" })
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val network = manager.allNetworks.firstOrNull { candidate ->
                manager.getNetworkCapabilities(candidate)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                    manager.getLinkProperties(candidate)?.routes?.any { !it.isDefaultRoute && it.matches(address) } == true
            } ?: throw IOException("Android Wi-Fi 설정에서 먼저 카메라 네트워크에 연결하세요")
            val wifiSource = manager.getLinkProperties(network)?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address }?.address
            return SonyWifiCard(address, network.socketFactory, wifiSource)
        }

        @Suppress("DEPRECATION")
        fun discover(context: Context): SonyWifiCard {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val addresses = manager.allNetworks.flatMap { network ->
                if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return@flatMap emptyList()
                val link = manager.getLinkProperties(network) ?: return@flatMap emptyList()
                val address = if (Build.VERSION.SDK_INT >= 30) link.dhcpServerAddress else null
                listOfNotNull(address, link.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway)
                    .mapNotNull { it.hostAddress?.takeIf { host -> PtpIp.localAddress(host) != null } }
            }.distinct()
            for (candidate in addresses) {
                try {
                    return connected(context, candidate).also { it.verifySonyDirectory() }
                } catch (_: Exception) {
                    // Only try addresses advertised by the Wi-Fi link. Manual entry remains available.
                }
            }
            throw IOException("카메라 주소를 찾지 못했습니다. 카메라 Wi-Fi와 스마트폰에서 선택 화면을 확인하세요")
        }
    }
}
