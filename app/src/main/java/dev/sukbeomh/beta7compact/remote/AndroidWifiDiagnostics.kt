package dev.sukbeomh.beta7compact.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** How the diagnostic reaches the camera. Neither the network name nor the passphrase is stored or logged. */
sealed interface WifiDiagnosticTarget {
    /** Wi-Fi Direct: the camera made the network; the phone joins it for this run. */
    data class CameraNetwork(val ssid: String, val passphrase: String) : WifiDiagnosticTarget
    /** Camera and phone already share a network (router or the phone's hotspot); the user reads the camera's address. */
    data class Address(val address: ByteArray) : WifiDiagnosticTarget
}

private class SocketChannel(private val socket: Socket) : PtpIpChannel {
    override fun write(bytes: ByteArray) { socket.getOutputStream().apply { write(bytes); flush() } }
    override fun readFully(buffer: ByteArray, timeoutMillis: Int) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        var offset = 0
        while (offset < buffer.size) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw java.net.SocketTimeoutException()
            socket.soTimeout = ((remaining + 999_999) / 1_000_000).toInt()
            val count = socket.getInputStream().read(buffer, offset, buffer.size - offset)
            if (count < 0) throw EOFException()
            offset += count
        }
    }
    override fun close() = socket.close()
}

internal fun ptpIpConnector(factory: SocketFactory, address: InetAddress) = PtpIpConnector { timeout ->
    val socket = factory.createSocket()
    try {
        socket.connect(InetSocketAddress(address, PtpIp.PORT), timeout)
        socket.tcpNoDelay = true
        SocketChannel(socket)
    } catch (failure: Exception) {
        try { socket.close() } catch (_: Exception) {}
        throw failure
    }
}

private fun cameraOn(manager: ConnectivityManager, network: Network): InetAddress? {
    val link = manager.getLinkProperties(network) ?: return null
    // The camera is both the access point and the DHCP server of its own network.
    if (Build.VERSION.SDK_INT >= 30) link.dhcpServerAddress?.let { return it }
    return link.routes.firstOrNull { it.isDefaultRoute && it.gateway?.isAnyLocalAddress == false }?.gateway
}

/** [guid] and [name] identify this app to the camera; they are the only data sent besides GetDeviceInfo. */
suspend fun runWifiDiagnostic(context: Context, target: WifiDiagnosticTarget, guid: ByteArray,
                              name: String = "beta7compact"): WifiDiagnosticReport {
    val started = System.nanoTime() / 1_000_000
    fun elapsed() = (System.nanoTime() / 1_000_000 - started).coerceAtLeast(0)
    val trace = mutableListOf<RemoteDiagnosticTrace>()
    fun failed(stage: String, failure: WifiDiagnosticFailure): WifiDiagnosticReport {
        trace += RemoteDiagnosticTrace(stage, RemoteTraceStatus.FAIL, elapsed())
        return WifiDiagnosticReport(WifiDiagnosticOutcome.FAILED, stage, failure, elapsed(), null, null, null, false,
            null, null, null, emptyMap(), 0, trace.toList())
    }
    suspend fun probe(factory: SocketFactory, address: InetAddress) = withContext(Dispatchers.IO) {
        runPtpIpProbe(ptpIpConnector(factory, address), guid, name, address.hostAddress, earlier = trace.toList(), startedAt = started)
    }
    return when (target) {
        is WifiDiagnosticTarget.Address ->
            probe(SocketFactory.getDefault(), InetAddress.getByAddress(target.address))
        is WifiDiagnosticTarget.CameraNetwork -> {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val joined = CompletableDeferred<Network?>()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { joined.complete(network) }
                override fun onUnavailable() { joined.complete(null) }
            }
            trace += RemoteDiagnosticTrace("WIFI_REQUEST", RemoteTraceStatus.START, elapsed())
            try {
                val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(WifiNetworkSpecifier.Builder().setSsid(target.ssid).setWpa2Passphrase(target.passphrase).build())
                    .build()
                // Includes the time the user takes to approve the system dialog.
                manager.requestNetwork(request, callback, 60_000)
            } catch (_: Exception) { return failed("WIFI_REQUEST", WifiDiagnosticFailure.WIFI_UNAVAILABLE) }
            try {
                val network = joined.await() ?: return failed("WIFI_REQUEST", WifiDiagnosticFailure.WIFI_UNAVAILABLE)
                trace += RemoteDiagnosticTrace("WIFI_REQUEST", RemoteTraceStatus.OK, elapsed())
                trace += RemoteDiagnosticTrace("CAMERA_ADDRESS", RemoteTraceStatus.START, elapsed())
                var address: InetAddress? = null
                for (attempt in 0 until 15) { address = cameraOn(manager, network); if (address != null) break; delay(200) }
                val camera = address ?: return failed("CAMERA_ADDRESS", WifiDiagnosticFailure.NO_CAMERA_ADDRESS)
                trace += RemoteDiagnosticTrace("CAMERA_ADDRESS", RemoteTraceStatus.OK, elapsed())
                probe(network.socketFactory, camera)
            } finally {
                // Releasing the request also leaves the camera's network.
                try { manager.unregisterNetworkCallback(callback) } catch (_: Exception) {}
            }
        }
    }
}
