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
import dev.sukbeomh.beta7compact.remote.CaptureExperimentAction
import dev.sukbeomh.beta7compact.remote.CaptureExperimentOutcome
import dev.sukbeomh.beta7compact.remote.CaptureExperimentReport
import dev.sukbeomh.beta7compact.remote.RemoteResourceClose
import dev.sukbeomh.beta7compact.remote.SonyButtonReleaseStatus
import dev.sukbeomh.beta7compact.remote.SonyPriorityRestore
import dev.sukbeomh.beta7compact.remote.SonyPriorityStatus
import dev.sukbeomh.beta7compact.remote.SonyPtpCloseStatus
import dev.sukbeomh.beta7compact.remote.runCaptureExperiment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun CaptureExperimentSetting(activity: MainActivity, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf(CaptureExperimentAction.AUTOFOCUS) }
    var acknowledged by remember { mutableStateOf(false) }
    var claimPriority by remember { mutableStateOf(false) }
    var confirmCapture by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<CaptureExperimentReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var guideExpanded by remember { mutableStateOf(false) }

    val execute: (CaptureExperimentAction) -> Unit = execute@ { selected ->
        if (busy || !enabled) return@execute
        if (selected == CaptureExperimentAction.SINGLE_CAPTURE && !confirmCapture) return@execute
        if (selected == CaptureExperimentAction.AUTOFOCUS && (!acknowledged || action != selected)) return@execute
        acknowledged = false
        confirmCapture = false
        // Permission to write control priority covers this run only.
        val claim = claimPriority
        claimPriority = false
        busy = true
        report = null
        error = null
        scope.launch {
            try {
                report = withContext(Dispatchers.IO) { runCaptureExperiment(context.applicationContext, selected, claim) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "결과 보고서를 만들지 못했습니다. 명령이 전송되었을 수 있으므로 카메라와 저장 결과를 확인한 뒤 다음 시험을 진행하세요."
            } finally { busy = false }
        }
    }

    TextButton(onClick = {
        acknowledged = false
        claimPriority = false
        confirmCapture = false
        action = CaptureExperimentAction.AUTOFOCUS
        opened = true
    }) { Text("AF·단발 촬영 실험") }
    if (!opened) return
    val sharedText = report?.let {
        "beta7compact ${BuildConfig.VERSION_NAME} AF·단발 촬영 실험\n" + it.safeText()
    }
    AlertDialog(
        onDismissRequest = { if (!busy && !confirmCapture) opened = false },
        title = { Text("AF·단발 촬영 실험") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sony 카메라용 미검증 실험 기능입니다. 원형 α7C(ILCE-7C) 이외 기종도 실행할 수 있으나 호환성이 확인되지 않았습니다. 렌즈가 움직이거나 실제 셔터가 작동해 사진이 카드에 저장될 수 있습니다.")
                Text("Sony 연결 과정에서 카메라 모드가 바뀔 수 있습니다. 완료 뒤 PC Remote 끄기·MTP 선택 또는 USB 재연결이 필요할 수 있습니다.")
                TextButton(onClick = { guideExpanded = !guideExpanded }) {
                    Text("카메라 설정·테스트 안내 · ${if (guideExpanded) "접기" else "펼치기"}")
                }
                if (guideExpanded) {
                    Text("아래 설정은 원형 α7C 기준입니다. 다른 기종은 카메라 설명서에서 PC Remote 설정을 확인하세요.")
                    Text("• 스마트폰 제어 끔 → PC Remote 켬 → 연결 방법 USB\n" +
                        "• 정지사진 M/P/A/S 모드, 단일 촬영, Bulb 이외의 셔터속도\n" +
                        "• PC Remote 정지 이미지 저장 위치: 카메라만. 기록 가능한 카드를 준비하세요.\n" +
                        "• 자동 초점 모드를 사용하세요. 앱은 조회한 초점 상태가 새로 잠기는지 확인하며, 지원하지 않는 상태에서는 실행을 거부합니다.\n" +
                        "• 제어 우선순위가 Application이어야 합니다. 아래 확인란을 켠 실행에서만 앱이 이 값을 쓰고 끝날 때 되돌립니다. 저장 대상 등 다른 설정은 바꾸지 않습니다. 조건 불충족은 로그로 공유하고 강제로 진행하지 마세요.\n" +
                        "먼저 AF만 시험한 뒤 결과를 보관하세요. 단발 촬영은 AF를 포함한 별도 1회 시도입니다. 앱은 사진 저장 완료를 확인하지 못하므로 카메라에서 직접 확인하세요.")
                }
                Text("이번에 실행할 동작", style = MaterialTheme.typography.titleSmall)
                CaptureActionChoice("AF만 실행", CaptureExperimentAction.AUTOFOCUS, action, enabled && !busy && !confirmCapture) {
                    action = it; acknowledged = false
                }
                CaptureActionChoice("AF 후 단발 촬영 명령 1회", CaptureExperimentAction.SINGLE_CAPTURE, action, enabled && !busy && !confirmCapture) {
                    action = it; acknowledged = false
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it }, enabled = enabled && !busy && !confirmCapture)
                    Text(if (action == CaptureExperimentAction.AUTOFOCUS)
                        "기종별 호환성 미확인, 렌즈 동작과 모드 변경 가능성을 확인했고, 이번 AF 실험을 실행합니다."
                    else "기종별 호환성 미확인, 실제 촬영·카드 저장과 모드 변경 가능성을 확인했고, 이번 단발 촬영 실험을 실행합니다.",
                        modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = claimPriority, onCheckedChange = { claimPriority = it }, enabled = enabled && !busy && !confirmCapture)
                    Text("이번 실행에서 제어 우선순위(0xD25A)를 Application으로 쓰고 끝날 때 되돌리는 것을 허용합니다. 쓰는 동안 카메라 다이얼보다 원격 설정이 우선할 수 있으며, 실기 미검증입니다.",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
                if (!enabled) Text("사진 가져오기가 끝난 뒤 실행하세요.")
                Row {
                    TextButton(enabled = enabled && acknowledged && !busy && !confirmCapture, onClick = {
                        if (action == CaptureExperimentAction.SINGLE_CAPTURE) {
                            acknowledged = false
                            confirmCapture = true
                        } else execute(CaptureExperimentAction.AUTOFOCUS)
                    }) { Text(if (busy) "실험 진행 중…" else "선택한 실험 실행") }
                    TextButton(enabled = enabled && !busy && !confirmCapture, onClick = {
                        acknowledged = false
                        try {
                            activity.usbPermission()
                            error = "USB 권한 확인 후 이번 실행 확인란을 선택하세요."
                        } catch (_: Exception) {
                            error = "Sony 카메라 한 대를 연결한 뒤 권한을 요청하세요."
                        }
                    }) { Text("USB 권한 요청") }
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("연결·실험·버튼 해제·종료 처리를 기다리고 있습니다. 완료될 때까지 카메라를 조작하거나 케이블을 분리하지 마세요.")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                report?.let { CaptureExperimentExplanation(it) }
                sharedText?.let { text ->
                    Text("공유할 결과 로그", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
                    Text("위 텍스트만 공유합니다. 사진·기기 문자열·시리얼·원시 응답·속성값은 제외하며 자동 저장·전송하지 않습니다.")
                    Row {
                        TextButton(enabled = !busy && !confirmCapture, onClick = {
                            try {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "beta7compact AF·단발 촬영 실험")
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }, "촬영 실험 로그 공유"))
                            } catch (_: Exception) { error = "결과를 공유할 앱이 없습니다." }
                        }) { Text("로그 공유") }
                        TextButton(enabled = !busy && !confirmCapture, onClick = { report = null; acknowledged = false }) { Text("결과 지우기") }
                    }
                }
                Text("자동 재시도하지 않습니다. 실패해도 촬영이 이루어졌을 수 있으므로, 다시 실행하기 전에 카메라와 저장된 사진을 확인하세요. 가져오기로 돌아가려면 PC Remote를 끄고 MTP를 선택한 뒤 필요하면 재연결하세요.")
            }
        },
        confirmButton = { TextButton(enabled = !busy && !confirmCapture, onClick = { opened = false }) { Text("닫기") } },
    )
    if (confirmCapture) {
        AlertDialog(
            onDismissRequest = { confirmCapture = false },
            title = { Text("실제 단발 촬영을 시도할까요?") },
            text = { Text("AF로 렌즈가 움직이고 실제 셔터가 작동하여 카드에 사진이 저장될 수 있습니다. Sony 연결은 카메라 모드도 바꿀 수 있습니다. 명령 응답이나 결과 로그만으로 저장 완료를 확정할 수 없습니다.") },
            confirmButton = {
                TextButton(enabled = enabled && !busy, onClick = { execute(CaptureExperimentAction.SINGLE_CAPTURE) }) {
                    Text("AF 후 단발 촬영 시도")
                }
            },
            dismissButton = { TextButton(onClick = { confirmCapture = false; acknowledged = false }) { Text("취소") } },
        )
    }
}

@Composable private fun CaptureActionChoice(label: String, value: CaptureExperimentAction,
                                             selected: CaptureExperimentAction, enabled: Boolean,
                                             select: (CaptureExperimentAction) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected == value, onClick = { select(value) }, enabled = enabled)
        Text(label)
    }
}

@Composable private fun CaptureExperimentExplanation(report: CaptureExperimentReport) {
    Text("결과 해석", style = MaterialTheme.typography.titleSmall)
    Text(when (report.outcome) {
        CaptureExperimentOutcome.AF_LOCK_OBSERVED -> "새로운 초점 잠금 상태를 관찰했습니다. AF만 실행했으며 전체 셔터 명령은 보내지 않았습니다. 실제 초점 품질은 카메라에서 확인하세요."
        CaptureExperimentOutcome.CAPTURE_SUBMITTED_UNCONFIRMED -> "단발 촬영 명령을 제출했지만 사진 저장 완료는 확인하지 못했습니다. 카메라의 재생 화면에서 결과를 직접 확인하세요."
        CaptureExperimentOutcome.FAILED -> if (report.capturePressAttempted)
            "실험을 완료하지 못했지만 전체 셔터 명령을 시도했습니다. 사진이 이미 촬영되었을 수 있으므로 바로 재시도하지 말고 카메라와 저장 결과를 먼저 확인하세요."
        else "실험을 완료하지 못했습니다. 이번 실행에서는 전체 셔터 명령을 시도하지 않았습니다. 아래 실패 단계와 조건 미충족 사유를 포함한 로그를 공유해주세요."
    })
    when (report.priorityStatus) {
        SonyPriorityStatus.NOT_WRITABLE -> Text("제어 우선순위를 쓸 수 없는 상태로 조회되어 쓰지 않았습니다. 카메라의 PC Remote 설정을 확인하고 로그를 공유해주세요.")
        SonyPriorityStatus.WRITTEN_UNCONFIRMED -> Text("제어 우선순위를 썼지만 바뀐 것을 확인하지 못했습니다. 카메라의 다이얼·모드 동작을 직접 확인하세요.", color = MaterialTheme.colorScheme.error)
        else -> Unit
    }
    if (report.priorityRestore in setOf(SonyPriorityRestore.UNCONFIRMED, SonyPriorityRestore.FAILED, SonyPriorityRestore.SKIPPED_UNKNOWN_PREVIOUS)) {
        Text("제어 우선순위를 되돌린 것이 확인되지 않았습니다. 카메라 다이얼이 평소처럼 동작하는지 확인하고, 다르면 PC Remote를 끄거나 전원을 껐다 켜세요.",
            color = MaterialTheme.colorScheme.error)
    }
    if (report.halfReleaseStatus == SonyButtonReleaseStatus.UNCONFIRMED ||
        report.fullReleaseStatus == SonyButtonReleaseStatus.UNCONFIRMED) {
        Text("버튼 해제 응답이 확인되지 않았습니다. 반복 실행하지 말고 카메라의 동작·상태를 직접 확인한 뒤 USB를 재연결하세요. 로그의 반셔터·전체 셔터 해제 상태를 함께 공유해주세요.",
            color = MaterialTheme.colorScheme.error)
    }
    if (report.resourceClose != RemoteResourceClose.CONFIRMED || report.ptpCloseStatus == SonyPtpCloseStatus.UNCONFIRMED) {
        Text("세션 또는 USB 자원 종료가 확인되지 않았습니다. 로그를 보관하고 카메라 상태를 확인한 뒤 다음 시험을 진행하세요.")
    }
    Text("버튼 해제·세션 종료의 OK 응답은 물리 상태나 카메라 모드 원복을 보장하지 않습니다. 로그와 함께 폰·카메라 펌웨어·렌즈·촬영 모드 및 카메라 메시지를 알려주세요.")
}
