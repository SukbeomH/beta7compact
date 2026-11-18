package dev.sukbeomh.beta7compact

import android.app.Application
import androidx.room.Room
import dev.sukbeomh.beta7compact.library.LibraryDb
import dev.sukbeomh.beta7compact.core.LinkEvent
import dev.sukbeomh.beta7compact.core.LinkState
import dev.sukbeomh.beta7compact.core.transition
import kotlinx.coroutines.flow.MutableStateFlow
import dev.sukbeomh.beta7compact.core.Camera
import dev.sukbeomh.beta7compact.usb.UsbAccess

class App : Application() {
    val db by lazy { Room.databaseBuilder(this, LibraryDb::class.java, "library.db")
        .addMigrations(LibraryDb.MIGRATION_1_2, LibraryDb.MIGRATION_2_3, LibraryDb.MIGRATION_3_4).build() }
    val settings by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    val updater by lazy { dev.sukbeomh.beta7compact.update.Updater(this) }
    val diskThumbnails by lazy { dev.sukbeomh.beta7compact.cache.DiskThumbnailCache(java.io.File(cacheDir, "camera-thumbnails-v1")) }
    val cameraThumbnails = dev.sukbeomh.beta7compact.cache.ThumbnailCache<android.graphics.Bitmap>(
        24 * 1024 * 1024, { it.allocationByteCount })
    val usbState = MutableStateFlow(LinkState.DISCONNECTED)
    val locationState = MutableStateFlow(LocationStatus())
    /** Evidence of the last location-link attempt; memory only, never coordinates or addresses. */
    val locationTrace = MutableStateFlow(dev.sukbeomh.beta7compact.core.LinkTrace())
    val usbDiagnostic = MutableStateFlow<String?>(null)
    suspend fun <T> useUsb(block: suspend (Camera) -> T): T = UsbAccess.use(this,
        { settings.getBoolean("raw", true) },
        remember = { raw -> if (settings.getBoolean("raw", true) != raw) settings.edit().putBoolean("raw", raw).apply() },
        failed = { usbDiagnostic.value = "${it.message} · raw PTP로 1회 대체" }, block = block)
    @Synchronized fun usb(event: LinkEvent) { usbState.value = transition(usbState.value, event) }
}
data class LocationStatus(val running: Boolean = false, val message: String = "꺼짐", val lastSent: Long? = null)
