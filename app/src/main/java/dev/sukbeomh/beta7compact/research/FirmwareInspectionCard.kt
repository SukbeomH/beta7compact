package dev.sukbeomh.beta7compact.research

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable fun FirmwareInspectionCard() {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<FirmwareInspection?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            result = null
            error = null
            busy = true
            job = scope.launch {
                try {
                    result = withContext(Dispatchers.IO) {
                        val task = currentCoroutineContext()
                        requireNotNull(resolver.openInputStream(uri)).use { input ->
                            inspectFirmware(input) { task.ensureActive() }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error = "파일을 끝까지 읽지 못했습니다. 접근 권한과 저장 위치를 확인하세요."
                } finally { busy = false }
            }
        }
    }
    OutlinedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("펌웨어 파일 분석", style = MaterialTheme.typography.titleMedium)
            Text("선택한 파일의 크기·SHA-256·첫 64바이트를 확인합니다. 카메라 연결이나 펌웨어 변경은 하지 않습니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("해시 일치는 파일 동일성만 나타내며, 정품 여부나 α7C 호환성을 보장하지 않습니다.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("파일 선택") }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { job?.cancel() }) { Text("분석 취소") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            result?.let { info ->
                SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("크기: ${info.size} bytes")
                    Text("SHA-256: ${info.sha256}")
                    Text("헤더: ${info.headerHex.ifEmpty { "(빈 파일)" }}")
                } }
                Text("결과는 자동 저장하거나 진단 로그에 추가하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { result = null }) { Text("결과 지우기") }
            }
        }
    }
}
