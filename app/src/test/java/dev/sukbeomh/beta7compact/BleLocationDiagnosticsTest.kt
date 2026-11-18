package dev.sukbeomh.beta7compact

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import dev.sukbeomh.beta7compact.ble.*
import dev.sukbeomh.beta7compact.core.*
import dev.sukbeomh.beta7compact.ui.bleShareText
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class BleLocationDiagnosticsTest {
    private fun bytes(value: String) = value.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    // Manufacturer data of the public ILCE7M3ExternalGps example, without the 2D 01 company id.
    private val example = bytes("0300 6400 4531 22eb00 214000 000000000000000003030018")

    @Test fun publicExampleAdvertisementDecodesVersionModelAndStatusBits() {
        val ad = assertNotNull(SonyAdvertisement.parse(example))
        assertEquals(100, ad.version); assertEquals("E1", ad.model)
        assertEquals(mapOf(0x22 to 0xeb, 0x21 to 0x40), ad.tags)
        assertEquals(true, ad.pairingSupported); assertEquals(true, ad.pairingBit)
        assertEquals(true, ad.locationSupported); assertEquals(false, ad.locationEnabled)
        assertEquals(true, ad.poweredOn)
        assertContains(ad.summary, "상태(0x22) 0xEB"); assertContains(ad.summary, "위치 지원/꺼짐")
    }

    @Test fun advertisementsThatAreNotCamerasOrAreCutShortAreHandled() {
        assertNull(SonyAdvertisement.parse(bytes("0400 6400 4531")))
        assertNull(SonyAdvertisement.parse(bytes("0300 64")))
        val bare = assertNotNull(SonyAdvertisement.parse(bytes("0300 6400 4531")))
        assertTrue(bare.tags.isEmpty()); assertNull(bare.locationEnabled); assertContains(bare.summary, "표시 없음")
        // A tag without its status byte is dropped rather than read past the end.
        assertEquals(mapOf(0x22 to 0xaf), assertNotNull(SonyAdvertisement.parse(bytes("0300 6400 4531 22af00 21"))).tags)
        assertEquals("", assertNotNull(SonyAdvertisement.parse(bytes("0300 6400 0001"))).model)
    }

    @Test fun limiterAllowsFiveStartsPerThirtySecondsAndSaysHowLongToWait() {
        val limiter = ScanLimiter()
        assertEquals(0, limiter.waitFor(0, 2)); limiter.started(0); limiter.started(1_000)
        assertEquals(0, limiter.waitFor(2_000, 2)); limiter.started(2_000); limiter.started(3_000)
        // Four starts used: one more fits, two do not until the oldest leaves the window.
        assertEquals(0, limiter.waitFor(4_000, 1))
        assertEquals(26_000, limiter.waitFor(4_000, 2))
        assertEquals(0, limiter.waitFor(30_000, 2))
        limiter.started(30_000); limiter.started(30_500)
        assertEquals(1_500, limiter.waitFor(30_500, 2))
    }

    @Test fun statusNotificationsKnownFromEitherPublicSourceAreRecognised() {
        assertEquals(true, LocationPacket.enabled(bytes("03010201")))
        assertEquals(true, LocationPacket.enabled(bytes("03010301")))
        assertEquals(false, LocationPacket.enabled(bytes("03010200")))
        assertNull(LocationPacket.enabled(bytes("03010300"))); assertNull(LocationPacket.enabled(bytes("030102")))
    }

    private fun result(id: String, data: ByteArray, company: Int = SONY_COMPANY) =
        ScanResult(BluetoothDevice(id, "ILCE-7C"), ScanRecord(mapOf(company to data)))
    private fun scan(first: (ScanCallback) -> Unit, second: (ScanCallback) -> Unit): Pair<ScanReport, Int> {
        val previous = Build.VERSION.SDK_INT
        try {
            Build.VERSION.SDK_INT = 35
            val manager = BluetoothManager()
            val scanner = manager.adapter!!.bluetoothLeScanner!!
            scanner.started = { callback, pass -> if (pass == 1) first(callback) else second(callback) }
            var starts = 0
            val report = runBlocking { BleScanner.scanWithWait(Context(manager), null, {}, { starts++ }) {} }
            return report to starts
        } finally { Build.VERSION.SDK_INT = previous }
    }

    @Test fun otherDevicesHeardWithoutACameraPointAtTheCameraNotThePhone() {
        val (report, starts) = scan({}) { callback ->
            repeat(3) { callback.onScanResult(0, result("other$it", byteArrayOf(1), company = 7)) }
        }
        assertEquals(2, starts); assertTrue(report.unfilteredRan); assertEquals(3, report.total)
        assertTrue(report.cameras.isEmpty())
        assertContains(report.reading, "휴대폰 검색은 동작합니다(다른 기기 광고 3건)")
        assertContains(report.diagnostic, "무필터 전체 광고 3")
    }

    @Test fun silenceInTheUnfilteredPassPointsAtThePhone() {
        val (report, _) = scan({}, {})
        assertEquals(0, report.total); assertContains(report.reading, "휴대폰 쪽 검색 문제")
    }

    @Test fun cameraAdvertisementIsDecodedOnceAndSkipsTheUnfilteredPass() {
        val (report, starts) = scan({ callback ->
            callback.onScanResult(0, result("camera", example)); callback.onScanResult(0, result("camera", example))
        }, {})
        assertEquals(1, starts); assertFalse(report.unfilteredRan)
        assertEquals(1, report.cameras.size); assertContains(report.cameras.single(), "버전 100")
        assertEquals("카메라 광고를 받았습니다.", report.reading)
        assertContains(report.diagnostic, "무필터 전체 광고 실행 안 함")
    }

    @Test fun tooFrequentSearchesAreExplainedInsteadOfReturningNothing() {
        assertContains(ScanReport(waitMs = 12_400).reading, "13초 뒤")
    }

    @Test fun traceAndSharedTextCarryEvidenceButNoAddressNameOrCoordinate() {
        val trace = LinkTrace(step = "잠금 해제", failed = "응답 시간 초과", mtu = 158, characteristics = listOf("DD01", "DD11", "DD21", "DD30", "DD31"),
            pairingService = true, config = LinkTrace.hex(bytes("0610009c0200")), statuses = listOf("03 01 03 01"), unlock = "쓰는 중")
        assertContains(trace.text, "단계: 잠금 해제 · 실패: 응답 시간 초과"); assertContains(trace.text, "MTU: 158")
        assertContains(trace.text, "DD01 DD11 DD21 DD30 DD31"); assertContains(trace.text, "설정(DD21): 06 10 00 9C 02 00")
        assertContains(LinkTrace().text, "상태 알림(DD01): 받지 못함")
        assertEquals("01 02 …(3바이트)", LinkTrace.hex(byteArrayOf(1, 2, 3), 2))
        val (report, _) = scan({ it.onScanResult(0, result("12:34:56:78:9A:BC", example)) }, {})
        val shared = bleShareText("0.8.9", true, report, "꺼짐", trace)
        assertTrue(shared.startsWith("beta7compact 0.8.9 BLE 위치 연동 진단"))
        assertContains(shared, "카메라 광고: 버전 100"); assertContains(shared, "위치 연동 상태: 꺼짐")
        assertFalse(shared.contains("9A:BC")); assertFalse(shared.contains("ILCE-7C"))
        assertContains(bleShareText("0.8.9", false, ScanReport(), "꺼짐", LinkTrace()), "검색: 실행 안 함")
    }
}
