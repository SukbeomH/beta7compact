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
import dev.sukbeomh.beta7compact.remote.RemoteDiagnosticOutcome
import dev.sukbeomh.beta7compact.remote.RemoteDiagnosticFailure
import dev.sukbeomh.beta7compact.remote.RemoteResourceClose
import dev.sukbeomh.beta7compact.remote.SonyPtpCloseStatus
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
                Text("Sony 카메라용 미검증 실험 기능입니다. 원형 α7C(ILCE-7C) 이외 기종도 실행할 수 있으나 호환성이 확인되지 않았습니다. 기종별 PC Remote USB 설정을 직접 확인하세요.")
                Text("Sony SDIO 연결은 카메라 모드를 바꿀 수 있습니다. 기존 읽기 전용 연결 진단과 다르며, 끝난 뒤 USB 재연결이나 카메라의 PC Remote 끄기·MTP 선택이 필요할 수 있습니다.")
                Text("연결 협상·속성 조회·짧은 이벤트 관찰 후 종료합니다. 촬영·노출 설정 쓰기·펌웨어 명령은 보내지 않습니다.")
                PcRemoteTestGuide()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it }, enabled = enabled && !busy)
                    Text("기종별 호환성 미확인과 모드 변경 가능성을 확인했고, 이번 진단을 실행합니다.", modifier = Modifier.weight(1f))
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
                report?.let { PcRemoteResultExplanation(it) }
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


@Composable private fun GuideSection(title: String, content: String) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Text("$title · ${if (expanded) "접기" else "펼치기"}", modifier = Modifier.fillMaxWidth())
    }
    if (expanded) Text(content, style = MaterialTheme.typography.bodyMedium)
}

@Composable private fun PcRemoteTestGuide() {
    GuideSection("1. 카메라 설정", "원형 α7C(ILCE-7C) 기준입니다.\n" +
        "• MENU → 네트워크 → 스마트폰으로 제어: 끔\n" +
        "• 네트워크 → PC Remote 기능 → PC Remote: 켬\n" +
        "• PC Remote 연결 방법: USB\n" +
        "PC Remote는 네트워크 메뉴의 별도 기능입니다. 기존 사진 가져오기의 MTP 설정과 구분하세요.")
    GuideSection("2. 테스트 순서", "1. 가져오기를 끝내고 카메라 펌웨어·렌즈·사진/영상 모드를 기록하세요.\n" +
        "2. 데이터 전송 케이블로 폰과 카메라를 연결하고 USB 권한을 허용하세요.\n" +
        "3. 모드 변경 확인란을 선택하고 실험 진단을 실행하세요.\n" +
        "4. 첫 실행은 버튼·다이얼을 조작하지 말고 완료를 기다리세요.\n" +
        "5. 성공·실패와 관계없이 로그를 보관하세요. 카메라 화면 메시지나 연결 끊김도 기록하면 도움이 됩니다.")
    GuideSection("3. 실패 시 공유할 내용", "‘로그 공유’로 처음 연결 단계부터 포함된 전체 텍스트를 보내주세요. 마지막 오류 줄만 보내면 원인을 찾기 어렵습니다.\n" +
        "함께 알려줄 내용: 폰 모델·Android 버전, 카메라 펌웨어, 렌즈·촬영 모드, 실제 PC Remote 설정, 케이블·젠더·허브 사용 여부, 카메라 메시지, 첫 실행인지 재연결 후인지, MTP 복귀 가능 여부.\n" +
        "로그에는 시리얼·기기 문자열·사진·원시 패킷이 포함되지 않습니다. 속성값은 촬영 조건·상태를 나타내는 조건속성 줄만 포함합니다. 자동 저장되지 않으므로 앱을 닫거나 결과를 지우기 전에 공유하세요. 앱이 종료되어 로그가 없으면 마지막 화면과 종료까지 걸린 시간을 알려주세요.")
    GuideSection("4. 가져오기로 복귀", "카메라의 PC Remote를 끄고 → USB 연결을 MTP로 선택 → 필요하면 케이블을 다시 연결하세요. 스마트폰 제어는 꺼둡니다.\n" +
        "기존 사진 목록 조회·가져오기가 되는지 확인하세요. 복귀가 안 되면 기존 ‘카메라 연결 진단 · 결과 공유’도 실행해 MTP 진단 로그와 PC Remote 실험 로그를 구분해서 보내주세요.")
}

@Composable private fun PcRemoteResultExplanation(report: RemoteDiagnosticReport) {
    Text("결과 해석", style = MaterialTheme.typography.titleSmall)
    Text(when (report.outcome) {
        RemoteDiagnosticOutcome.COMPLETE -> "진단 절차를 완료했습니다. 연결·속성 조회·이벤트 관찰·종료 경로의 결과이며, 원격 촬영이나 설정 제어 지원 완료를 뜻하지 않습니다."
        RemoteDiagnosticOutcome.COMPLETE_WITHOUT_EVENTS -> "이벤트 endpoint가 없어 관찰을 생략하고 나머지 진단 절차를 완료했습니다. 카메라 전체가 이벤트를 지원하지 않는다는 뜻은 아닙니다."
        RemoteDiagnosticOutcome.FAILED -> "진단을 완료하지 못했습니다. 아래 로그의 실패단계와 오류 분류를 포함해 전체 결과를 공유해주세요."
    })
    if (report.outcome == RemoteDiagnosticOutcome.FAILED) {
        Text(when (report.failure) {
            RemoteDiagnosticFailure.UNSUPPORTED_PROPERTY_TYPE -> "앱이 아직 해석하지 못하는 속성 자료형입니다. 카메라 고장으로 판단하지 마세요. 로그의 미지원속성 code/type이 파서 확장에 필요합니다."
            RemoteDiagnosticFailure.UNSUPPORTED_PROTOCOL -> "응답한 프로토콜 버전을 현재 앱이 처리하지 못합니다. 광고프로토콜 숫자가 포함된 로그를 공유해주세요."
            RemoteDiagnosticFailure.TIMEOUT -> "제한 시간 안에 응답이나 처리가 끝나지 않았습니다. 로그를 보관한 뒤 카메라 설정·케이블을 확인하고 재연결하세요."
            RemoteDiagnosticFailure.PTP_RESPONSE -> "카메라가 성공 이외의 응답을 보냈습니다. 어느 명령에서 어떤 response 코드가 나왔는지 확인할 수 있도록 전체 로그를 공유해주세요."
            RemoteDiagnosticFailure.IO -> "통신 또는 USB 요청 정리 중 오류가 발생했습니다. 로그를 먼저 보관하고 설정·권한·케이블을 확인한 뒤 재연결하세요."
            else -> "카메라 한 대가 연결되어 있는지, USB 권한과 PC Remote 설정이 맞는지 확인하세요. 응답 형식이나 지원 목록의 차이도 원인일 수 있습니다."
        })
    }
    if (report.eventPollingAvailable == true && report.eventCodes.isEmpty()) {
        Text("이벤트 기록이 0건입니다. 관찰 중 조작하지 않았다면 가능하며, 0건만으로 실패를 뜻하지는 않습니다. 관찰 자체의 성공 여부는 전체 결과를 함께 보세요.")
    }
    Text(when (report.ptpCloseStatus) {
        SonyPtpCloseStatus.CONFIRMED -> "PTP 종료 응답을 확인했습니다. 카메라 모드가 원래대로 돌아갔다는 보장은 아니므로 MTP 복귀 안내를 따르세요."
        SonyPtpCloseStatus.NOT_OPENED -> "이 진단에서 PTP 세션을 여는 단계까지 진행하지 않았습니다. 다른 프로그램이나 카메라의 세션 상태를 뜻하지는 않습니다."
        else -> "PTP 종료 상태를 확인하지 못했습니다. 로그를 보관하고 재연결한 뒤 다음 시험을 진행하세요."
    })
    if (report.resourceClose != RemoteResourceClose.CONFIRMED || report.cleanupErrorCount > 0) {
        Text("USB 자원 종료가 확인되지 않았거나 정리 오류가 있습니다. 아래 자원종료·정리오류수도 함께 공유해주세요.")
    }
}
