package dev.sukbeomh.beta7compact.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import dev.sukbeomh.beta7compact.core.LocationPacket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.util.UUID

/** Serialized GATT operations. Only DD11 location and DD01's CCCD can be written. */
@SuppressLint("MissingPermission") // LocationService rechecks grants before constructing and using this link.
class LocationLink(private val context: Context) : Closeable {
    private data class Reply(val kind: String, val status: Int, val value: ByteArray = byteArrayOf(), val mtu: Int = 0)
    private val replies = Channel<Reply>(Channel.UNLIMITED)
    val enabled = MutableStateFlow<Boolean?>(null)
    private var gatt: BluetoothGatt? = null
    private lateinit var write: BluetoothGattCharacteristic
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (status == 0 && state == BluetoothProfile.STATE_CONNECTED) replies.trySend(Reply("connect", 0))
            else if (state == BluetoothProfile.STATE_DISCONNECTED || status != 0) {
                enabled.value = false
                replies.close(IllegalStateException("BLE 연결이 끊겼습니다"))
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { replies.trySend(Reply("mtu", status, mtu = mtu)) }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { replies.trySend(Reply("discover", status)) }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            replies.trySend(Reply("read", status, value.copyOf()))
        }
        @Deprecated("Compatibility with API 29-32")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) replies.trySend(Reply("read", status, c.value.copyOf()))
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == characteristic(0xdd01)) LocationPacket.enabled(value)?.let { enabled.value = it }
        }
        @Deprecated("Compatibility with API 29-32")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) onCharacteristicChanged(g, c, c.value)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { replies.trySend(Reply("subscribe", status)) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { replies.trySend(Reply("write", status)) }
    }
    private suspend fun reply(kind: String): Reply = withTimeout(15_000) {
        val result = replies.receive()
        check(result.kind == kind && result.status == BluetoothGatt.GATT_SUCCESS) { "BLE 응답 실패: $kind" }
        result
    }
    suspend fun connect(device: BluetoothDevice): Boolean {
        check(device.bondState == BluetoothDevice.BOND_BONDED) { "선택한 카메라의 페어링을 완료하세요" }
        gatt = checkNotNull(device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE))
        reply("connect")
        val g = checkNotNull(gatt)
        check(g.requestMtu(158)) { "BLE MTU 요청 실패" }
        check(reply("mtu").mtu >= 98) { "위치 패킷을 보낼 BLE MTU가 부족합니다" }
        check(g.discoverServices()) { "BLE 서비스 검색 실패" }; reply("discover")
        val service = checkNotNull(g.getService(UUID.fromString("8000dd00-dd00-ffff-ffff-ffffffffffff"))) {
            "카메라 위치 서비스가 없습니다"
        }
        // Newer protocols require extra endpoint writes; deliberately unsupported in M1.
        check(service.getCharacteristic(characteristic(0xdd30)) == null && service.getCharacteristic(characteristic(0xdd31)) == null) {
            "새 위치 프로토콜은 M1에서 지원하지 않습니다"
        }
        write = checkNotNull(service.getCharacteristic(characteristic(0xdd11))) { "위치 쓰기 특성이 없습니다" }
        check(write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) { "위치 쓰기 응답을 지원하지 않습니다" }
        val config = checkNotNull(service.getCharacteristic(characteristic(0xdd21))) { "위치 설정 특성이 없습니다" }
        check(g.readCharacteristic(config)) { "위치 설정 읽기 실패" }
        val includeZone = LocationPacket.zoneRequired(reply("read").value)
        val status = checkNotNull(service.getCharacteristic(characteristic(0xdd01))) { "위치 상태 특성이 없습니다" }
        check(g.setCharacteristicNotification(status, true)) { "위치 알림 설정 실패" }
        val cccd = checkNotNull(status.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))) {
            "위치 알림 구독 특성이 없습니다"
        }
        val accepted = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        else { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
        check(accepted) { "위치 알림 구독 실패" }; reply("subscribe")
        // Never infer enabled from successful subscription. Some bodies need an explicit menu toggle.
        check(withTimeout(15_000) { enabled.filterNotNull().first() }) { "카메라 위치정보 연동을 켜세요" }
        return includeZone
    }
    suspend fun send(packet: ByteArray) {
        check(enabled.value == true) { "카메라 위치정보 연동이 꺼져 있습니다" }
        val g = checkNotNull(gatt)
        val accepted = if (Build.VERSION.SDK_INT >= 33)
            g.writeCharacteristic(write, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        else { write.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; write.value = packet; g.writeCharacteristic(write) }
        check(accepted) { "위치 패킷 전송을 시작할 수 없습니다" }
        reply("write") // Last-sent timestamp is set only after acknowledgement.
    }
    override fun close() { try { gatt?.disconnect() } finally { gatt?.close(); gatt = null; replies.close() } }
    companion object {
        private fun characteristic(short: Int) = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))
    }
}
