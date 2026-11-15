package dev.sukbeomh.beta7compact.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import android.os.ParcelFileDescriptor
import dev.sukbeomh.beta7compact.core.Camera
import dev.sukbeomh.beta7compact.core.CameraObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** One owner for the physical USB interface, shared by listing, thumbs and the worker. */
object UsbAccess {
    private val lock = Mutex()
    suspend fun <T> use(context: Context, raw: Boolean, block: suspend (Camera) -> T): T = lock.withLock {
        open(context, raw).use { block(it) }
    }
    fun device(context: Context): UsbDevice {
        val candidates = context.getSystemService(UsbManager::class.java).deviceList.values.filter { it.vendorId == 0x054c }
        check(candidates.size == 1) { if (candidates.isEmpty()) "Sony 카메라를 USB로 연결하세요" else "Sony 카메라 한 대만 연결하세요" }
        return candidates.single()
    }
    private fun open(context: Context, raw: Boolean): Camera {
        val device = device(context)
        val manager = context.getSystemService(UsbManager::class.java)
        check((0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == 6 }) {
            "카메라 설정 → USB 연결 → MTP"
        }
        check(manager.hasPermission(device)) { "USB 접근 권한을 허용하세요" }
        if (raw) return RawCamera(manager, device)
        // Retry exactly once through raw PTP only if MtpDevice open/device-info fails.
        return try { NativeCamera(manager, device) } catch (e: Exception) {
            if (e is SecurityException) throw e
            RawCamera(manager, device)
        }
    }
}

private class NativeCamera(manager: UsbManager, device: UsbDevice) : Camera {
    private val connection = checkNotNull(manager.openDevice(device)) { "USB 장치를 열 수 없습니다" }
    private val mtp = MtpDevice(device)
    init {
        try {
            check(mtp.open(connection)) { "MTP 세션을 열 수 없습니다" }
            checkNotNull(mtp.deviceInfo) { "MTP 장치 정보를 읽을 수 없습니다" }
        } catch (e: Throwable) { mtp.close(); connection.close(); throw e }
    }
    override fun list(): List<CameraObject> = checkNotNull(mtp.storageIds) { "카드를 읽을 수 없습니다" }.flatMap { storage ->
        checkNotNull(mtp.getObjectHandles(storage, 0, 0)) { "사진 목록을 읽을 수 없습니다" }.map { handle ->
            val obj = checkNotNull(mtp.getObjectInfo(handle)) { "사진 정보를 읽을 수 없습니다" }
            CameraObject(handle, storage, obj.name, obj.compressedSizeLong,
                obj.dateCreated.takeIf { it > 0 } ?: obj.dateModified, obj.parent)
        }
    }.filter { it.valid }
    override fun thumbnail(item: CameraObject): ByteArray? = mtp.getThumbnail(item.handle)
    override fun download(item: CameraObject, destination: File) {
        val now = checkNotNull(mtp.getObjectInfo(item.handle)) { "수신 대상을 확인할 수 없습니다" }
        check(CameraObject(item.handle, now.storageId, now.name, now.compressedSizeLong,
            now.dateCreated.takeIf { it > 0 } ?: now.dateModified, now.parent).key == item.key) { "카드 내용이 변경되었습니다. 목록을 새로고침하세요" }
        ParcelFileDescriptor.open(destination, ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use {
            check(mtp.importFile(item.handle, it)) { "MTP 원본 수신에 실패했습니다" }
        }
    }
    override fun close() { mtp.close(); connection.close() }
}

private class RawCamera(manager: UsbManager, device: UsbDevice) : Camera {
    private val connection = checkNotNull(manager.openDevice(device)) { "USB 장치를 열 수 없습니다" }
    private var face: android.hardware.usb.UsbInterface? = null
    private lateinit var client: PtpUsbClient
    init {
        try {
            fun configuration(): Int {
                val result = ByteArray(1)
                check(connection.controlTransfer(0x80, 8, 0, 0, result, 1, 8000) == 1) { "USB 구성을 확인할 수 없습니다" }
                return result[0].toInt() and 255
            }
            val active = configuration()
            val (config, selected) = (0 until device.configurationCount).flatMap { c ->
                val cfg = device.getConfiguration(c)
                (0 until cfg.interfaceCount).map { cfg to cfg.getInterface(it) }
            }.filter { (_, f) ->
                val endpoints = (0 until f.endpointCount).map { f.getEndpoint(it) }
                f.interfaceClass == 6 && endpoints.any { it.type == 2 && it.direction == 128 } &&
                    endpoints.any { it.type == 2 && it.direction == 0 }
            }.sortedWith(compareBy({ it.first.id != active }, { it.second.alternateSetting != 0 })).firstOrNull()
                ?: error("카메라 설정 → USB 연결 → MTP")
            ensureUsbConfiguration(active, config.id, { connection.setConfiguration(config) }, ::configuration, {})
            check(connection.claimInterface(selected, true)) { "USB 인터페이스를 사용할 수 없습니다" }
            face = selected
            val alternate = ByteArray(1)
            check(connection.controlTransfer(0x81, 10, 0, selected.id, alternate, 1, 8000) == 1) { "USB 인터페이스 상태 확인 실패" }
            if ((alternate[0].toInt() and 255) != selected.alternateSetting) {
                check(connection.setInterface(selected)) { "USB 인터페이스 선택 실패" }
            }
            val endpoints = (0 until selected.endpointCount).map { selected.getEndpoint(it) }
            val input = endpoints.first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
            val output = endpoints.first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
            client = PtpUsbClient(object : UsbBulkTransport {
                override fun write(bytes: ByteArray, timeoutMs: Int) = connection.bulkTransfer(output, bytes, bytes.size, timeoutMs)
                override fun read(bytes: ByteArray, timeoutMs: Int) = connection.bulkTransfer(input, bytes, bytes.size, timeoutMs)
            })
            client.read(0x1001)
            client.openSession()
        } catch (e: Throwable) { face?.let { connection.releaseInterface(it) }; connection.close(); throw e }
    }
    override fun list(): List<CameraObject> = Ptp.ids(client.read(0x1004)).flatMap { storage ->
        Ptp.ids(client.read(0x1007, storage, 0, 0)).map { handle ->
            val obj = Ptp.objectInfo(handle, client.read(0x1008, handle))
            CameraObject(handle, storage, obj.name, obj.size, CameraObject.ptpTime(obj.date), obj.parent)
        }
    }.filter { it.valid }
    override fun thumbnail(item: CameraObject): ByteArray? = try { client.read(0x100A, item.handle) }
        catch (e: PtpResponseException) { null }
    override fun download(item: CameraObject, destination: File) {
        val now = Ptp.objectInfo(item.handle, client.read(0x1008, item.handle))
        check(CameraObject(now.handle, now.storage, now.name, now.size, CameraObject.ptpTime(now.date), now.parent).key == item.key) {
            "카드 내용이 변경되었습니다. 목록을 새로고침하세요"
        }
        destination.outputStream().use { client.getObject(item.handle, it, item.size) }
    }
    override fun close() {
        try { client.close() } finally { face?.let { connection.releaseInterface(it) }; connection.close() }
    }
}
