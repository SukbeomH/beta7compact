package dev.sukbeomh.beta7compact.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import dev.sukbeomh.beta7compact.core.LinkTrace
import dev.sukbeomh.beta7compact.core.LocationPacket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.util.UUID

/**
 * Serialized GATT operations. Writes go to DD11 (location) and DD01's CCCD only, plus - when the camera exposes
 * them and the user allowed it for this run - `01` to DD30 then DD31 before sending and `00` to both on release.
 * Those two writes follow the public ILCE7M3ExternalGps notes ("firmware 3.02 and above") and match what the
 * open Alpha GPS app sends; they are untested on an ILCE-7C.
 */
@SuppressLint("MissingPermission") // LocationService rechecks grants before constructing and using this link.
class LocationLink(private val context: Context, private val allowUnlock: Boolean = false,
                   private val publish: (LinkTrace) -> Unit = {}) : Closeable {
    var trace = LinkTrace(); private set
    @Synchronized private fun note(change: (LinkTrace) -> LinkTrace) { trace = change(trace); publish(trace) }
    private var unlocked = emptyList<BluetoothGattCharacteristic>()
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
            if (c.uuid != characteristic(0xdd01)) return
            val seen = LinkTrace.hex(value, 8)
            note { if (seen in it.statuses || it.statuses.size >= 8) it else it.copy(statuses = it.statuses + seen) }
            LocationPacket.enabled(value)?.let { enabled.value = it }
        }
        @Deprecated("Compatibility with API 29-32")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) onCharacteristicChanged(g, c, c.value)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { replies.trySend(Reply("subscribe", status)) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { replies.trySend(Reply("write", status)) }
    }
    private suspend fun reply(kind: String, timeout: Long = 15_000): Reply = withTimeout(timeout) {
        val result = replies.receive()
        check(result.kind == kind && result.status == BluetoothGatt.GATT_SUCCESS) { "BLE 응답 실패: $kind" }
        result
    }
    private suspend fun put(c: BluetoothGattCharacteristic, value: ByteArray, timeout: Long = 15_000) {
        val g = checkNotNull(gatt)
        val accepted = if (Build.VERSION.SDK_INT >= 33)
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        else { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = value; g.writeCharacteristic(c) }
        check(accepted) { "BLE 쓰기를 시작할 수 없습니다" }
        reply("write", timeout)
    }
    /** Every failure leaves the step it happened in and what had been read so far in [trace]. */
    suspend fun connect(device: BluetoothDevice): Boolean = try { open(device) } catch (e: Exception) {
        val reason = when {
            e is TimeoutCancellationException -> "응답 시간 초과"
            e is CancellationException -> null
            else -> e.message?.takeIf { it.any { char -> char in '가'..'힣' } } ?: "BLE 오류"
        }
        if (reason != null) note { it.copy(failed = reason) }
        throw e
    }
    private fun step(name: String) = note { it.copy(step = name) }
    private suspend fun open(device: BluetoothDevice): Boolean {
        step("페어링 확인")
        check(device.bondState == BluetoothDevice.BOND_BONDED) { "선택한 카메라의 페어링을 완료하세요" }
        step("연결")
        gatt = checkNotNull(device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE))
        reply("connect")
        val g = checkNotNull(gatt)
        step("MTU 협상")
        check(g.requestMtu(158)) { "BLE MTU 요청 실패" }
        val mtu = reply("mtu").mtu
        note { it.copy(mtu = mtu) }
        check(mtu >= 98) { "위치 패킷을 보낼 BLE MTU가 부족합니다" }
        step("서비스 검색")
        check(g.discoverServices()) { "BLE 서비스 검색 실패" }; reply("discover")
        val pairing = g.getService(UUID.fromString("8000ee00-ee00-ffff-ffff-ffffffffffff")) != null
        note { it.copy(pairingService = pairing) }
        val service = checkNotNull(g.getService(UUID.fromString("8000dd00-dd00-ffff-ffff-ffffffffffff"))) {
            "카메라 위치 서비스가 없습니다"
        }
        val listed = service.characteristics.map { it.uuid.toString().substring(4, 8).uppercase() }.sorted()
        note { it.copy(characteristics = listed) }
        step("특성 확인")
        write = checkNotNull(service.getCharacteristic(characteristic(0xdd11))) { "위치 쓰기 특성이 없습니다" }
        check(write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) { "위치 쓰기 응답을 지원하지 않습니다" }
        val config = checkNotNull(service.getCharacteristic(characteristic(0xdd21))) { "위치 설정 특성이 없습니다" }
        step("설정 읽기")
        check(g.readCharacteristic(config)) { "위치 설정 읽기 실패" }
        val settings = reply("read").value
        note { it.copy(config = LinkTrace.hex(settings)) }
        val includeZone = LocationPacket.zoneRequired(settings)
        step("상태 알림 구독")
        val status = checkNotNull(service.getCharacteristic(characteristic(0xdd01))) { "위치 상태 특성이 없습니다" }
        check(g.setCharacteristicNotification(status, true)) { "위치 알림 설정 실패" }
        val cccd = checkNotNull(status.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))) {
            "위치 알림 구독 특성이 없습니다"
        }
        val accepted = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        else { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
        check(accepted) { "위치 알림 구독 실패" }; reply("subscribe")
        val locks = listOfNotNull(service.getCharacteristic(characteristic(0xdd30)), service.getCharacteristic(characteristic(0xdd31)))
        if (locks.isNotEmpty()) {
            step("잠금 해제")
            if (!allowUnlock) {
                note { it.copy(unlock = "카메라에 있음 · 허용하지 않아 쓰지 않음") }
                error("이 카메라는 위치를 보내기 전에 잠금 해제 쓰기(DD30/DD31)가 필요합니다. 허용을 켜고 다시 시작하세요")
            }
            note { it.copy(unlock = "쓰는 중") }
            for (lock in locks) { put(lock, byteArrayOf(1)); unlocked = unlocked + lock }
            note { it.copy(unlock = "01 쓰기 성공 (${locks.size}개)") }
        } else note { it.copy(unlock = "카메라에 없음") }
        // The camera may notify only when its menu toggle changes, so silence is not a refusal; an explicit "off" is.
        step("상태 알림 대기")
        val reported = withTimeoutOrNull(3_000) { enabled.filterNotNull().first() }
        check(reported != false) { "카메라 위치정보 연동을 켜세요" }
        step(if (reported == true) "전송 준비 완료" else "상태 알림 없이 전송 준비")
        return includeZone
    }
    suspend fun send(packet: ByteArray) {
        check(enabled.value != false) { "카메라 위치정보 연동이 꺼져 있습니다" }
        try { put(write, packet) } catch (e: Exception) {
            if (e is TimeoutCancellationException || e !is CancellationException) note { it.copy(step = "위치 전송", failed = "쓰기 응답 없음 또는 거부") }
            throw e
        }
        note { it.copy(step = "위치 전송", sent = it.sent + 1) } // Counted only after acknowledgement.
    }
    /** Best effort: gives the lock back before disconnecting, as the public notes recommend. */
    suspend fun release() {
        if (unlocked.isEmpty() || gatt == null) return
        val done = unlocked.count { lock -> runCatching { put(lock, byteArrayOf(0), 3_000) }.isSuccess }
        note { it.copy(unlock = it.unlock + " · 00 되돌림 $done/${unlocked.size}") }
        unlocked = emptyList()
    }
    override fun close() { try { gatt?.disconnect() } finally { gatt?.close(); gatt = null; replies.close() } }
    companion object {
        private fun characteristic(short: Int) = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))
    }
}
