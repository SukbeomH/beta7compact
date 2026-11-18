package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.transfer.SonyWifiCard
import dev.sukbeomh.beta7compact.transfer.WifiCardPhoto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import javax.net.SocketFactory
import kotlin.test.*
import org.junit.Test

class SonyWifiCardTest {
    private val address = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
    private val image = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())

    private class CameraSocket(body: ByteArray, length: Int) : Socket() {
        val sent = ByteArrayOutputStream()
        private val response = ByteArrayInputStream(
            "HTTP/1.1 200 OK\r\nContent-Length: $length\r\n\r\n".toByteArray() + body)
        override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit
        override fun setSoTimeout(timeout: Int) = Unit
        override fun getOutputStream() = sent
        override fun getInputStream() = response
        override fun close() = Unit
    }

    private fun client(socket: CameraSocket) = SonyWifiCard(address, object : SocketFactory() {
        override fun createSocket(): Socket = socket
        override fun createSocket(host: String?, port: Int): Socket = error("unexpected")
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = error("unexpected")
        override fun createSocket(host: InetAddress?, port: Int): Socket = error("unexpected")
        override fun createSocket(host: InetAddress?, port: Int, localHost: InetAddress?, localPort: Int): Socket = error("unexpected")
    })

    private fun photo(url: String = "http://10.0.0.1:60151/photo.jpg", length: Long? = 4) =
        WifiCardPhoto("1", "test.jpg", "test.jpg", null, length, URI(url), null, false)

    @Test fun copiesExactBytesFromCameraAndNeverFollowsCrossHostUrls() {
        val socket = CameraSocket(image, 4)
        val camera = client(socket)
        val path = File.createTempFile("wifi-camera-test", ".part")
        try {
            assertEquals(4L, camera.download(photo(), path) { true })
            assertContentEquals(image, path.readBytes())
            assertContains(socket.sent.toString(Charsets.US_ASCII.name()), "GET /photo.jpg HTTP/1.0")
            assertFailsWith<IllegalArgumentException> {
                camera.download(photo("http://10.0.0.2:60151/foreign.jpg"), path) { true }
            }
        } finally { path.delete() }
    }

    @Test fun rejectsChangedLengthAndPrematureEof() {
        val path = File.createTempFile("wifi-camera-test", ".part")
        try {
            assertFailsWith<IllegalStateException> { client(CameraSocket(image, 4)).download(photo(length = 5), path) { true } }
            assertFailsWith<IOException> { client(CameraSocket(image, 5)).download(photo(length = null), path) { true } }
        } finally { path.delete() }
    }
}
