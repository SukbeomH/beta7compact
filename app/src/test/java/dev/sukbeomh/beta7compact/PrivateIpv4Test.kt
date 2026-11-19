package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.transfer.privateIpv4Address
import org.junit.Test
import kotlin.test.*

class PrivateIpv4Test {
    @Test fun acceptsOnlyPrivateDottedIpv4Addresses() {
        assertContentEquals(byteArrayOf(192.toByte(), 168.toByte(), 122, 1), privateIpv4Address(" 192.168.122.1 "))
        assertNotNull(privateIpv4Address("10.0.0.5")); assertNotNull(privateIpv4Address("172.31.255.254"))
        for (bad in listOf("8.8.8.8", "172.32.0.1", "192.169.1.1", "camera.local", "192.168.1", "192.168.1.256", "192.168.1.1.1", "", "1e1.0.0.1", "192.168..1", "١٠.0.0.1"))
            assertNull(privateIpv4Address(bad), bad)
    }
}
