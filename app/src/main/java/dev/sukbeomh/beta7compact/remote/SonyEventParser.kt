package dev.sukbeomh.beta7compact.remote

import dev.sukbeomh.beta7compact.usb.PtpContainer
import dev.sukbeomh.beta7compact.usb.le

/** Raw observations only: an event does not establish that our capture succeeded. */
data class SonyEvent(val code: Int, val transactionId: Long, val parameters: List<Long>)

object SonyEventParser {
    const val OBJECT_ADDED = 0xC201
    const val OBJECT_REMOVED = 0xC202
    const val PROPERTY_CHANGED = 0xC203
    const val CAPTURED = 0xC206

    // USB event layout and names: libgphoto2 31a2f408, ptp.h:147–156,1369–1373.
    fun parse(bytes: ByteArray): SonyEvent {
        require(bytes.size in 12..24 && (bytes.size - 12) % 4 == 0) {
            "USB PTP event must contain a header and zero to three uint32 parameters"
        }
        val container = PtpContainer.decode(bytes)
        require(container.type == 4) { "Expected USB PTP event container" }
        val payload = le(container.payload)
        return SonyEvent(container.code, container.transaction.toLong() and 0xffffffffL,
            List(payload.remaining() / 4) { payload.int.toLong() and 0xffffffffL })
    }
}

/** Local observation window, not a wire transaction or camera state machine. */
data class SonyEventObservation private constructor(
    val generation: Long,
    val deadlineMillis: Long,
    val lastObservedMillis: Long,
    val expired: Boolean,
    val propertiesNeedRefresh: Boolean,
    val objectsNeedRefresh: Boolean,
) {
    fun advance(nowMillis: Long): SonyEventObservation {
        require(nowMillis >= lastObservedMillis) { "Observation clock must be monotonic" }
        return copy(lastObservedMillis = nowMillis, expired = expired || nowMillis >= deadlineMillis)
    }

    fun observe(event: SonyEvent, eventGeneration: Long, nowMillis: Long): SonyEventObservation {
        val next = advance(nowMillis)
        if (eventGeneration != generation || next.expired) return next
        return when (event.code) {
            SonyEventParser.PROPERTY_CHANGED -> next.copy(propertiesNeedRefresh = true)
            SonyEventParser.OBJECT_ADDED, SonyEventParser.OBJECT_REMOVED ->
                next.copy(objectsNeedRefresh = true)
            else -> next
        }
    }

    companion object {
        fun start(generation: Long, nowMillis: Long, timeoutMillis: Long): SonyEventObservation {
            require(nowMillis >= 0 && timeoutMillis > 0 && timeoutMillis <= Long.MAX_VALUE - nowMillis) {
                "Invalid observation deadline"
            }
            return SonyEventObservation(generation, nowMillis + timeoutMillis, nowMillis,
                expired = false, propertiesNeedRefresh = false, objectsNeedRefresh = false)
        }
    }
}
