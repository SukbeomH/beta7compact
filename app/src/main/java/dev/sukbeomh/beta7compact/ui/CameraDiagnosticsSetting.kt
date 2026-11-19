package dev.sukbeomh.beta7compact.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.sukbeomh.beta7compact.BuildConfig
import dev.sukbeomh.beta7compact.MainActivity
import dev.sukbeomh.beta7compact.usb.CameraDiagnosticReport
import dev.sukbeomh.beta7compact.usb.UsbAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun CameraDiagnosticsSetting(activity: MainActivity, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<CameraDiagnosticReport?>(null) }
    var selectedMode by remember { mutableStateOf("미확인") }
    var capturedMode by remember { mutableStateOf("미확인") }
    var error by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { opened = true }) { Text("카메라 연결 진단 · 결과 공유") }
    if (!opened) return
    val sharedText = report?.let {
        "beta7compact ${BuildConfig.VERSION_NAME} USB 진단\n" +
            "사용자가 선택한 카메라 모드: $capturedMode (자동 판정 아님)\n" + it.safeText()
    }
    AlertDialog(onDismissRequest = { if (!busy) opened = false },
        title = { Text("카메라 연결 진단") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("카메라에서 선택한 모드를 기록하세요. 이 화면은 카메라 모드를 변경하지 않습니다.")
            Row {
                listOf("미확인", "MTP", "기타").forEach { mode ->
                    TextButton(onClick = { selectedMode = mode }, enabled = !busy) {
                        Text(if (selectedMode == mode) "✓ $mode" else mode)
                    }
                }
            }
            Text("USB 구성·권한·연결 단계와 지원 목록을 확인합니다. 설정 변경·촬영·Sony 전용 명령은 보내지 않습니다.")
            if (!enabled) Text("사진 가져오기가 끝난 뒤 진단을 실행하세요.")
            Row {
                TextButton(enabled = enabled && !busy, onClick = {
                    busy = true
                    report = null
                    error = null
                    capturedMode = selectedMode
                    scope.launch {
                        try {
                            report = withContext(Dispatchers.IO) { UsbAccess.collectDiagnostics(context.applicationContext) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            error = "진단을 완료하지 못했습니다. 연결을 확인하고 다시 실행하세요."
                        } finally { busy = false }
                    }
                }) { Text(if (busy) "진단 중…" else "진단 실행") }
                TextButton(enabled = enabled && !busy, onClick = {
                    try { activity.usbPermission(); error = "USB 권한 확인 후 진단을 다시 실행하세요." }
                    catch (_: Exception) { error = "Sony 카메라 한 대를 연결한 뒤 권한을 요청하세요." }
                }) { Text("USB 권한 요청") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            report?.info?.let { info ->
                Text("기기 표시: ${info.manufacturer} ${info.model} · ${info.version}")
                Text("기기가 보낸 위 문자열은 공유 내용에서 제외합니다.", style = MaterialTheme.typography.bodySmall)
            }
            sharedText?.let { text ->
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
                Text("위 내용만 공유합니다. 시리얼번호·기기 경로·사진 정보·원시 응답은 제외하며 자동 저장·전송하지 않습니다.")
                Row {
                    TextButton(onClick = {
                        try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "beta7compact USB 진단")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }, "진단 결과 공유")) }
                        catch (_: Exception) { error = "진단 결과를 공유할 앱이 없습니다." }
                    }, enabled = !busy) { Text("결과 공유") }
                    TextButton(onClick = { report = null }, enabled = !busy) { Text("결과 지우기") }
                }
            }
            Text("지원 목록은 실제 사진 가져오기 성공을 보장하지 않습니다. 카메라의 USB 연결 설정을 확인하세요.")
        } },
        confirmButton = { TextButton(onClick = { opened = false }, enabled = !busy) { Text("닫기") } })
}
