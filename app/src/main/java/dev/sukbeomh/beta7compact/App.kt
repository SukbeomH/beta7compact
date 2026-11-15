package dev.sukbeomh.beta7compact

import android.app.Application
import androidx.room.Room
import dev.sukbeomh.beta7compact.library.LibraryDb
import dev.sukbeomh.beta7compact.core.LinkEvent
import dev.sukbeomh.beta7compact.core.LinkState
import dev.sukbeomh.beta7compact.core.transition
import kotlinx.coroutines.flow.MutableStateFlow

class App : Application() {
    val db by lazy { Room.databaseBuilder(this, LibraryDb::class.java, "library.db").build() }
    val settings by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    val usbState = MutableStateFlow(LinkState.DISCONNECTED)
    val locationState = MutableStateFlow(LocationStatus())
    @Synchronized fun usb(event: LinkEvent) { usbState.value = transition(usbState.value, event) }
}
data class LocationStatus(val running: Boolean = false, val message: String = "꺼짐", val lastSent: Long? = null)
