package dev.sukbeomh.beta7compact.usb

import dev.sukbeomh.beta7compact.DiagnosticEvent
import dev.sukbeomh.beta7compact.DiagnosticLog
import dev.sukbeomh.beta7compact.core.Camera
import dev.sukbeomh.beta7compact.core.CameraObject
import java.io.File
import kotlinx.coroutines.CancellationException

enum class MtpFailure(val message: String, val event: DiagnosticEvent) {
    OPEN("MTP 실패: 세션·장치 정보를 열 수 없음", DiagnosticEvent.USB_MTP_OPEN_FAILED),
    LIST("MTP 실패: 카드·사진 목록·정보를 읽을 수 없음", DiagnosticEvent.USB_MTP_LIST_FAILED),
    THUMBNAIL("MTP 실패: 미리보기를 읽을 수 없음", DiagnosticEvent.USB_MTP_THUMB_FAILED),
    DOWNLOAD("MTP 실패: 원본을 수신할 수 없음", DiagnosticEvent.USB_MTP_DOWNLOAD_FAILED)
}

internal class MtpReadException(val failure: MtpFailure) : java.io.IOException(failure.message)

/** Wrap only MTP calls: identity checks, disk failures and cancellation never trigger fallback. */
internal inline fun <T : Any> mtpRead(failure: MtpFailure, read: () -> T?): T {
    try { return read() ?: throw MtpReadException(failure) }
    catch (e: SecurityException) { throw e }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { throw MtpReadException(failure) }
}

/** Retry the failed camera read once, never the caller's journal/MediaStore block. */
internal class FallbackCamera(
    raw: Boolean, private val native: () -> Camera, private val rawCamera: () -> Camera,
    private val remember: (Boolean) -> Unit, private val failed: (MtpFailure) -> Unit
) : Camera {
    private var usingRaw = raw
    private var current: Camera? = null
    private fun switchToRaw(failure: MtpFailure) {
        usingRaw = true // Even if opening raw fails, never retry MTP or raw again in this action.
        DiagnosticLog.record(failure.event)
        failed(failure)
        val previous = current
        current = null
        previous?.close() // Must release native ownership before raw claims the interface.
        DiagnosticLog.record(DiagnosticEvent.USB_RAW_FALLBACK)
        current = rawCamera()
    }
    init {
        if (usingRaw) current = rawCamera()
        else try { current = native() }
        catch (e: MtpReadException) { switchToRaw(e.failure) }
    }
    private fun <T> read(block: (Camera) -> T): T {
        val result = try { block(checkNotNull(current)) }
        catch (e: MtpReadException) {
            if (usingRaw) throw e
            switchToRaw(e.failure)
            block(checkNotNull(current))
        }
        remember(usingRaw) // Persist only after an actual read succeeds.
        DiagnosticLog.record(if (usingRaw) DiagnosticEvent.USB_RAW_OK else DiagnosticEvent.USB_MTP_OK)
        return result
    }
    override fun list() = read { it.list() }
    override fun thumbnail(item: CameraObject) = read { it.thumbnail(item) }
    override fun download(item: CameraObject, destination: File) = read { it.download(item, destination) }
    override fun close() { val previous = current; current = null; previous?.close() }
}
