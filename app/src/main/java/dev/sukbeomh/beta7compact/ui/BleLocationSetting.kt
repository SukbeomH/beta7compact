package dev.sukbeomh.beta7compact.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sukbeomh.beta7compact.*
import dev.sukbeomh.beta7compact.ble.BleScanner
import dev.sukbeomh.beta7compact.ble.LocationService
import dev.sukbeomh.beta7compact.ble.ScanReport
import dev.sukbeomh.beta7compact.core.LinkTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Text a user can send back after a session at the camera. No address, device name or coordinate. */
internal fun bleShareText(version: String, searched: Boolean, report: ScanReport, status: String, trace: LinkTrace): String = listOf(
    "beta7compact $version BLE 위치 연동 진단",
    if (searched) report.diagnostic else "검색: 실행 안 함",
    report.reading.takeIf { searched && it.isNotEmpty() },
    report.cameras.takeIf { it.isNotEmpty() }?.joinToString("\n") { "카메라 광고: $it" },
    "위치 연동 상태: $status",
    trace.text,
).filterNotNull().joinToString("\n")

/** Search, pairing and the location-link switch for the camera's BLE location service. Unverified on a real ILCE-7C. */
@SuppressLint("MissingPermission") // Every action asks for the BLE grants first; failures become one-line messages.
@Composable fun BleLocationSetting(activity: MainActivity, app: App) {
    var opened by remember { mutableStateOf(false) }
    TextButton(onClick = { opened = true }) { Text("BLE 위치 연동 · 실기 미검증") }
    if (!opened) return
    val scope = rememberCoroutineScope()
    val location by app.locationState.collectAsStateWithLifecycle()
    val trace by app.locationTrace.collectAsStateWithLifecycle()
    var report by remember { mutableStateOf(ScanReport()) }
    var found by remember { mutableStateOf(runCatching { BleScanner.bonded(app, app.settings.getString("camera", null)) }.getOrDefault(emptyList())) }
    var chosen by remember { mutableStateOf(app.settings.getString("camera", null)) }
    var searched by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var unlock by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    fun search() = activity.requestBle { granted ->
        if (!granted) message = "Bluetooth·정확한 위치·알림 권한을 허용하세요"
        else {
            busy = true; searched = true; message = null
            scanJob = scope.launch {
                try {
                    val result = BleScanner.scan(app, chosen) { report = it; found = it.devices }
                    if (result.waitMs == 0L && !result.failed && result.cameras.isEmpty()) message = BleScanner.EMPTY_GUIDANCE
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    DiagnosticLog.record(DiagnosticEvent.BLE_FAILED)
                    message = e.message?.takeIf { it.any { char -> char in '가'..'힣' } } ?: "BLE 검색 실패. Bluetooth·위치 기능을 확인하고 다시 검색하세요"
                } finally { busy = false }
            }
        }
    }
    fun select(device: BluetoothDevice) {
        try {
            scanJob?.cancel()
            app.settings.edit().putString("camera", device.address).apply(); chosen = device.address
            DiagnosticLog.record(DiagnosticEvent.BLE_SELECTED)
            if (device.bondState == BluetoothDevice.BOND_BONDED) message = "페어링된 카메라를 선택했습니다. 위치 전송을 켜세요"
            else {
                check(device.createBond())
                DiagnosticLog.record(DiagnosticEvent.BLE_BOND_STARTED)
                message = "카메라와 휴대폰에 뜨는 페어링 확인을 모두 완료하세요"
            }
        } catch (e: Exception) {
            DiagnosticLog.record(DiagnosticEvent.BLE_BOND_FAILED)
            message = "페어링을 시작할 수 없습니다. 카메라의 페어링 화면을 연 상태에서 다시 선택하세요"
        }
    }
    fun toggle(on: Boolean) {
        // Permission for the unlock write covers one run: it is cleared whenever the switch changes.
        val allowed = unlock
        unlock = false
        if (!on) app.stopService(Intent(app, LocationService::class.java))
        else activity.requestBle { granted ->
            if (!granted) message = "정확한 위치·Bluetooth·알림 권한을 허용하세요"
            else try {
                scanJob?.cancel()
                ContextCompat.startForegroundService(activity, Intent(activity, LocationService::class.java).putExtra("unlock", allowed))
            } catch (e: Exception) { message = "앱을 연 상태에서 위치 전송을 켜세요" }
        }
    }
    val shared = bleShareText(BuildConfig.VERSION_NAME, searched, report, location.message, trace)
    AlertDialog(onDismissRequest = { opened = false }, title = { Text("BLE 위치 연동") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("실제 α7C에서 아직 확인하지 못한 기능입니다. 휴대폰의 위치와 시각을 카메라에 보내 사진에 기록하게 합니다.")
            Text("카메라 준비: 비행기 모드 끔 · Bluetooth 리모컨 끔 · Bluetooth 기능 켬 · 페어링 화면 열기. " +
                "이전에 다른 폰이나 Imaging Edge Mobile과 페어링했다면 카메라에서 그 페어링을 지우세요. USB 케이블은 뽑아 두세요.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = ::search, enabled = !busy && !location.running) {
                Text(if (busy) "${if (report.unfiltered) "전체" else "Sony"} 광고 검색 중 (15초)…" else if (searched) "다시 검색" else "Sony 카메라 검색")
            }
            if (searched) {
                if (report.reading.isNotEmpty()) Text(report.reading)
                Text(report.diagnostic, style = MaterialTheme.typography.bodySmall)
                report.cameras.forEach { Text("카메라 광고: $it", style = MaterialTheme.typography.bodySmall) }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            found.forEachIndexed { index, device ->
                val bonded = device.bondState == BluetoothDevice.BOND_BONDED
                TextButton(onClick = { select(device) }, enabled = !location.running) {
                    Text("${if (device.address == chosen) "✓ " else ""}${device.name ?: "Sony 카메라"} · ${if (bonded) "페어링됨" else "페어링 필요"} · ${index + 1} 선택")
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("위치 전송")
                    Text(location.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = location.running, onCheckedChange = ::toggle, enabled = chosen != null || location.running)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(unlock, { unlock = it }, enabled = !location.running)
                Text("카메라가 요구하면 잠금 해제 쓰기(DD30/DD31)를 이번 실행에 허용", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
            Text("허용하면 위치 서비스의 DD30·DD31에 01을 쓰고, 끌 때 00으로 되돌립니다. 공개 분석 자료의 순서를 따르며 α7C에서는 미검증입니다. " +
                "카메라에 이 특성이 없으면 아무것도 쓰지 않습니다.", style = MaterialTheme.typography.bodySmall)
            Text("공유할 진단", style = MaterialTheme.typography.titleSmall)
            SelectionContainer { Text(shared, style = MaterialTheme.typography.bodySmall) }
            Text("위 내용만 공유합니다. 기기 주소·기기 이름·좌표는 제외하며 자동 저장·전송하지 않습니다.")
            TextButton(onClick = {
                try { activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "beta7compact BLE 위치 연동 진단"); putExtra(Intent.EXTRA_TEXT, shared)
                }, "진단 결과 공유")) } catch (_: Exception) { message = "진단 결과를 공유할 앱이 없습니다" }
            }) { Text("진단 공유") }
        } },
        confirmButton = { TextButton(onClick = { opened = false }) { Text("닫기") } })
}
