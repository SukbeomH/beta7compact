package dev.sukbeomh.beta7compact.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.sukbeomh.beta7compact.usb.DeviceInfo
import dev.sukbeomh.beta7compact.usb.UsbAccess
import dev.sukbeomh.beta7compact.usb.hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun CameraDiagnosticsSetting(enabled: Boolean) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DeviceInfo?>(null) }
    var failed by remember { mutableStateOf(false) }
    TextButton(enabled = enabled && !busy, onClick = {
        scope.launch {
            busy = true
            failed = false
            result = null
            try {
                result = withContext(Dispatchers.IO) { UsbAccess.inspect(context) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            } finally { busy = false }
        }
    }) { Text(if (busy) "카메라 정보를 읽는 중…" else "카메라 지원 정보 확인") }
    if (failed) Text("조회하지 못했습니다. Sony 카메라 한 대를 MTP 모드로 연결하고 카메라 탭에서 USB 권한을 허용하세요.")
    result?.let { info ->
        AlertDialog(onDismissRequest = { result = null }, title = { Text("카메라가 보고한 지원 정보") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${info.manufacturer} ${info.model}")
                Text("기기 버전: ${info.version}")
                Text("지원 명령: ${info.operations.distinct().joinToString { hex(it) }.ifEmpty { "없음" }}")
                Text("지원 속성: ${info.properties.distinct().joinToString { hex(it) }.ifEmpty { "없음" }}")
                Text("지원 목록은 실제 제어 성공을 보장하지 않습니다. 설정 변경·촬영 명령은 보내지 않습니다. 이 정보는 저장하거나 진단 로그에 포함하지 않습니다.")
            } }, confirmButton = { TextButton(onClick = { result = null }) { Text("닫기") } })
    }
}
