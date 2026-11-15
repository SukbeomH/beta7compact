package dev.sukbeomh.beta7compact.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import android.os.ParcelFileDescriptor
import android.util.Log
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

private class NativeCamera(private val manager: UsbManager, private val device: UsbDevice) : Camera {
    private var connection: android.hardware.usb.UsbDeviceConnection? = null
    private var mtp: MtpDevice? = null
    init { openNative() }
    private fun openNative() {
        val opened = checkNotNull(manager.openDevice(device)) { "USB 장치를 열 수 없습니다" }
        val native = MtpDevice(device)
        try {
            check(native.open(opened)) { "MTP 세션을 열 수 없습니다" }
            checkNotNull(native.deviceInfo) { "MTP 장치 정보를 읽을 수 없습니다" }
            connection = opened; mtp = native
        } catch (e: Throwable) { native.close(); opened.close(); throw e }
    }
    // MtpObjectInfo returns milliseconds, but loses the original DateTime suffix/DST spelling.
    // Raw metadata is optional. A failure must leave native MTP available for ordinary transfers.
    private fun <T> metadata(read: (RawCamera) -> T): T? = try {
        readExclusiveMetadata(::close, { RawCamera(manager, device) }, ::openNative, read)
    } catch (e: Exception) {
        checkNotNull(mtp) { "MTP 세션을 다시 열 수 없습니다" }
        // Fixed reason only: never log device identifiers, file metadata or transport dumps.
        val reason = if (e is PtpResponseException) "PTP 응답 거절" else "raw 메타데이터 접근 실패"
        Log.i("UsbCamera", "$reason · 보강 없이 MTP로 진행합니다")
        null
    }
    private fun nativeItem(handle: Int, obj: android.mtp.MtpObjectInfo) = CameraObject(
        handle, obj.storageId, obj.name, obj.compressedSizeLong,
        obj.dateCreated.takeIf { it > 0 } ?: obj.dateModified, obj.parent)
    override fun list(): List<CameraObject> {
        val raw = metadata { it.list() }?.associateBy { it.handle }
        val native = checkNotNull(mtp)
        return checkNotNull(native.storageIds) { "카드를 읽을 수 없습니다" }.flatMap { storage ->
            checkNotNull(native.getObjectHandles(storage, 0, 0)) { "사진 목록을 읽을 수 없습니다" }.toList().mapNotNull { handle ->
                val obj = checkNotNull(native.getObjectInfo(handle)) { "사진 정보를 읽을 수 없습니다" }
                val item = if (raw == null) nativeItem(handle, obj) else raw[handle] ?: return@mapNotNull null
                check(nativeMatches(item, obj)) { "카드 내용이 변경되었습니다. 목록을 새로고침하세요" }
                item.takeIf { it.valid }
            }
        }
    }
    private fun nativeMatches(item: CameraObject, obj: android.mtp.MtpObjectInfo) =
        obj.name == item.name && obj.compressedSizeLong == item.size && obj.storageId == item.storage && obj.parent == item.parent
    override fun thumbnail(item: CameraObject): ByteArray? = checkNotNull(mtp).getThumbnail(item.handle)
    override fun download(item: CameraObject, destination: File) {
        val now = metadata { it.info(item.handle) }
        if (now != null) check(now.matchesQueued(item)) { "카드 내용이 변경되었습니다. 목록을 새로고침하세요" }
        val native = checkNotNull(mtp)
        val obj = checkNotNull(native.getObjectInfo(item.handle)) { "수신 대상을 확인할 수 없습니다" }
        check(nativeMatches(item, obj) && (now != null || nativeItem(item.handle, obj).matchesQueued(item))) {
            "카드 내용이 변경되었습니다. 목록을 새로고침하세요"
        }
        ParcelFileDescriptor.open(destination, ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use {
            check(native.importFile(item.handle, it)) { "MTP 원본 수신에 실패했습니다" }
        }
    }
    override fun close() {
        try { mtp?.close() } finally { mtp = null; connection?.close(); connection = null }
    }
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
            val configurations = (0 until device.configurationCount).flatMap { c ->
                val cfg = device.getConfiguration(c)
                (0 until cfg.interfaceCount).map { cfg.id to cfg.getInterface(it) }
            }
            val alternates = mutableMapOf<Int, Int>()
            val selected = activeStillImageInterface(active, configurations, { it.alternateSetting }, { f ->
                alternates.getOrPut(f.id) {
                    val result = ByteArray(1)
                    check(connection.controlTransfer(0x81, 10, 0, f.id, result, 1, 8000) == 1) {
                        "카메라 설정 → USB 연결 → MTP"
                    }
                    result[0].toInt() and 255
                }
            }) { f ->
                val endpoints = (0 until f.endpointCount).map { f.getEndpoint(it) }
                f.interfaceClass == 6 && endpoints.any { it.type == 2 && it.direction == 128 } &&
                    endpoints.any { it.type == 2 && it.direction == 0 }
            }
            check(connection.claimInterface(selected, true)) { "USB 인터페이스를 사용할 수 없습니다" }
            face = selected
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
            info(handle).also { check(it.storage == storage) { "카드 내용이 변경되었습니다. 목록을 새로고침하세요" } }
        }
    }.filter { it.valid }
    override fun thumbnail(item: CameraObject): ByteArray? = try { client.read(0x100A, item.handle) }
        catch (e: PtpResponseException) { null }
    fun info(handle: Int): CameraObject {
        val obj = Ptp.objectInfo(handle, client.read(0x1008, handle))
        return obj.cameraObject()
    }
    override fun download(item: CameraObject, destination: File) {
        val now = info(item.handle)
        check(now.key == item.key) {
            "카드 내용이 변경되었습니다. 목록을 새로고침하세요"
        }
        destination.outputStream().use { client.getObject(item.handle, it, item.size) }
    }
    override fun close() {
        try { client.close() } finally { face?.let { connection.releaseInterface(it) }; connection.close() }
    }
}
