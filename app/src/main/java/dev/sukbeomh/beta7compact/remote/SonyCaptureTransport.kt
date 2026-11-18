package dev.sukbeomh.beta7compact.remote

/** Source: libgphoto2 31a2f408 ptp.c:4616 and library.c:5160. No arbitrary property writes. */
enum class SonyCaptureCommand(val propertyCode: Int, val value: Int) {
    HALF_PRESS(0xd2c1, 2), FULL_PRESS(0xd2c2, 2),
    FULL_RELEASE(0xd2c2, 1), HALF_RELEASE(0xd2c1, 1),
}

interface SonyCaptureTransport : SonySessionTransport {
    suspend fun control(command: SonyCaptureCommand)
}
