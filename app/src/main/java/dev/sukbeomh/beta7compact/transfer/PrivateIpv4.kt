package dev.sukbeomh.beta7compact.transfer

/** Accept only dotted private IPv4 addresses for the camera on the local network. */
internal fun privateIpv4Address(text: String): ByteArray? {
    val parts = text.trim().split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || !it.all { c -> c in '0'..'9' } }) return null
    val octets = parts.map { it.toInt() }
    if (octets.any { it > 255 }) return null
    val (a, b) = octets
    val private = a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    return if (private) ByteArray(4) { octets[it].toByte() } else null
}
