package dev.sukbeomh.beta7compact.remote

/** Source: libgphoto2 31a2f408 ptp.c:4616 and library.c:5160. No arbitrary property writes. */
enum class SonyCaptureCommand(val propertyCode: Int, val value: Int) {
    HALF_PRESS(0xd2c1, 2), FULL_PRESS(0xd2c2, 2),
    FULL_RELEASE(0xd2c2, 1), HALF_RELEASE(0xd2c1, 1),
}

/**
 * The one camera setting this app writes, per docs/CAMERA_WRITE_POLICY.md: control priority 0xD25A through
 * SetExtDevicePropValue 0x9205. libgphoto2 31a2f408 writes 1 after connecting; AlphaWire defines 0 = camera, 1 = remote.
 */
enum class SonySettingCommand(val propertyCode: Int, val value: Int) {
    PRIORITY_APPLICATION(0xd25a, 1), PRIORITY_CAMERA(0xd25a, 0),
}

interface SonyCaptureTransport : SonySessionTransport {
    suspend fun control(command: SonyCaptureCommand)
    /** Refused unless the transport was opened for it. */
    suspend fun set(command: SonySettingCommand): Unit = throw IllegalStateException("Setting writes are not enabled on this transport")
}
