package dev.sukbeomh.beta7compact.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.sukbeomh.beta7compact.BuildConfig
import dev.sukbeomh.beta7compact.MainActivity
import dev.sukbeomh.beta7compact.remote.RemoteDiagnosticReport
import dev.sukbeomh.beta7compact.remote.collectRemoteDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun PcRemoteDiagnosticsSetting(activity: MainActivity, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<RemoteDiagnosticReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { acknowledged = false; opened = true }) { Text("PC Remote 실험 진단") }
    if (!opened) return
    val sharedText = report?.let {
        "beta7compact ${BuildConfig.VERSION_NAME} PC Remote 실험 진단\n" + it.safeText()
    }
    AlertDialog(
        onDismissRequest = { if (!busy) opened = false },
        title = { Text("PC Remote 실험 진단") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("원형 α7C(ILCE-7C)용 실험 기능입니다. 카메라에서 스마트폰 제어를 끄고, 네트워크 → PC Remote 기능을 켠 뒤 연결 방법을 USB로 선택하세요.")
                Text("Sony SDIO 연결은 카메라 모드를 바꿀 수 있습니다. 기존 읽기 전용 연결 진단과 다르며, 끝난 뒤 USB 재연결이나 카메라의 PC Remote 끄기·MTP 선택이 필요할 수 있습니다.")
                Text("연결 협상·속성 조회·짧은 이벤트 관찰 후 종료합니다. 촬영·노출 설정 쓰기·펌웨어 명령은 보내지 않습니다.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it }, enabled = enabled && !busy)
                    Text("모드 변경 가능성을 확인했고, 이번 진단을 실행합니다.", modifier = Modifier.weight(1f))
                }
                if (!enabled) Text("사진 가져오기가 끝난 뒤 실행하세요.")
                Row {
                    TextButton(enabled = enabled && acknowledged && !busy, onClick = {
                        acknowledged = false
                        busy = true
                        report = null
                        error = null
                        scope.launch {
                            try {
                                report = withContext(Dispatchers.IO) { collectRemoteDiagnostics(context.applicationContext) }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = "진단 보고서를 만들지 못했습니다. 연결을 확인한 뒤 다시 실행하세요."
                            } finally { busy = false }
                        }
                    }) { Text(if (busy) "실험 진단 중…" else "실험 진단 실행") }
                    TextButton(enabled = enabled && !busy, onClick = {
                        try {
                            activity.usbPermission()
                            error = "USB 권한 확인 후 실행 확인란을 선택하고 진단을 실행하세요."
                        } catch (_: Exception) {
                            error = "Sony 카메라 한 대를 연결한 뒤 권한을 요청하세요."
                        }
                    }) { Text("USB 권한 요청") }
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("연결 응답과 종료 처리를 기다리고 있습니다. 완료될 때까지 케이블을 유지하세요.")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                sharedText?.let { text ->
                    Text("공유할 진단 로그", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
                    Text("위 내용만 공유합니다. 시리얼번호·기기 문자열·경로·사진·원시 응답은 제외하며 자동 저장·전송하지 않습니다.")
                    Row {
                        TextButton(enabled = !busy, onClick = {
                            try {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "beta7compact PC Remote 실험 진단")
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }, "실험 진단 로그 공유"))
                            } catch (_: Exception) {
                                error = "진단 로그를 공유할 앱이 없습니다."
                            }
                        }) { Text("로그 공유") }
                        TextButton(enabled = !busy, onClick = { report = null }) { Text("결과 지우기") }
                    }
                }
                Text("실험 진단의 성공은 촬영·설정 제어 호환성 보장이 아닙니다. 실패한 경우에도 보고서의 단계와 오류 분류가 개선에 도움이 됩니다.")
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { opened = false }) { Text("닫기") } },
    )
}
