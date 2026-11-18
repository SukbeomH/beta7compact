package dev.sukbeomh.beta7compact.remote

import java.io.Closeable
import java.util.Collections

/** Fixed stage-2 operations only. No shutter, property setter or arbitrary opcode API. */
enum class SonySessionOperation(val code: Int, parameters: List<Int>, val receivesData: Boolean) {
    DEVICE_INFO(0x1001, emptyList(), true),
    OPEN_SESSION(0x1002, listOf(1), false),
    CONNECT_1(0x9201, listOf(1, 0, 0), true),
    CONNECT_2(0x9201, listOf(2, 0, 0), true),
    EXTENDED_INFO(0x9202, listOf(0x012c, 1), true),
    CONNECT_3(0x9201, listOf(3, 0, 0), true),
    PROPERTY_SNAPSHOT(0x9209, emptyList(), true),
    CLOSE_SESSION(0x1003, emptyList(), false),
    ;

    val parameters: List<Int> = Collections.unmodifiableList(ArrayList(parameters))
}

/** Payload boundary. Implementations must honor cancellation and bounded I/O timeouts. */
interface SonySessionTransport : Closeable {
    suspend fun exchange(operation: SonySessionOperation): ByteArray
    /** Complete USB event container, or null on a bounded poll with no event. */
    suspend fun nextEvent(timeoutMillis: Int): ByteArray?
}

/** Injectable byte transport; no Android USB adapter is installed by this stage. */
interface SonyRemoteWire : Closeable {
    suspend fun write(bytes: ByteArray, timeoutMillis: Int): Int
    suspend fun read(bytes: ByteArray, timeoutMillis: Int): Int
    suspend fun readEvent(timeoutMillis: Int): ByteArray?
}
