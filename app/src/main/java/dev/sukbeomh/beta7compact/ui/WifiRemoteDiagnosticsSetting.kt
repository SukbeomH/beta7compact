package dev.sukbeomh.beta7compact.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.sukbeomh.beta7compact.App
import dev.sukbeomh.beta7compact.BuildConfig
import dev.sukbeomh.beta7compact.remote.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The identifier this app shows the camera over PTP/IP: random, made once, kept in app settings. */
internal fun ptpIpGuid(app: App): ByteArray {
    val saved = app.settings.getString("ptpipGuid", null)?.takeIf { it.length == 32 }
        ?.chunked(2)?.mapNotNull { it.toIntOrNull(16)?.toByte() }?.takeIf { it.size == 16 }
    if (saved != null) return saved.toByteArray()
    return ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }.also { fresh ->
        app.settings.edit().putString("ptpipGuid", fresh.joinToString("") { "%02x".format(it) }).apply()
    }
}

/** Read-only check that the camera answers PTP/IP over Wi-Fi. Unverified on a real ILCE-7C. */
@Composable fun WifiRemoteDiagnosticsSetting(app: App, enabled: Boolean) {
    var opened by remember { mutableStateOf(false) }
    TextButton(onClick = { opened = true }) { Text("Wi-Fi 연결 진단 · 실기 미검증") }
    if (!opened) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var direct by remember { mutableStateOf(true) }
    var ssid by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<WifiDiagnosticReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun run() {
        val target = if (direct) {
            if (ssid.isBlank() || passphrase.length !in 8..63) { error = "카메라 화면의 네트워크 이름과 8~63자 암호를 입력하세요."; return }
            WifiDiagnosticTarget.CameraNetwork(ssid.trim(), passphrase)
        } else WifiDiagnosticTarget.Address(PtpIp.localAddress(address) ?: run {
            error = "카메라의 사설 IPv4 주소를 입력하세요. 예: 192.168.0.12"; return
        })
        busy = true; acknowledged = false; error = null; report = null
        passphrase = "" // Used for this run only.
        scope.launch {
            try { report = runWifiDiagnostic(context.applicationContext, target, ptpIpGuid(app)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "진단을 완료하지 못했습니다. Wi-Fi를 켜고 다시 시도하세요." }
            finally { busy = false }
        }
    }
    val shared = report?.let { "beta7compact ${BuildConfig.VERSION_NAME} Wi-Fi 연결 진단\n" + it.safeText() }
    AlertDialog(onDismissRequest = { if (!busy) opened = false }, title = { Text("Wi-Fi 연결 진단") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("실제 α7C에서 아직 확인하지 못한 기능입니다. 카메라가 Wi-Fi로 PC Remote 연결(PTP/IP)에 응답하는지만 확인합니다. " +
                "세션 열기·Sony 연결·촬영·설정 쓰기는 하지 않습니다.")
            Text("카메라 준비: 네트워크 → 스마트폰으로 제어 끔 → PC Remote 기능 → PC Remote 켬. USB 케이블은 뽑아 두세요.",
                style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { direct = true }, enabled = !busy) { Text(if (direct) "✓ 카메라의 Wi-Fi에 접속" else "카메라의 Wi-Fi에 접속") }
                TextButton(onClick = { direct = false }, enabled = !busy) { Text(if (!direct) "✓ 주소 입력" else "주소 입력") }
            }
            if (direct) {
                Text("PC Remote 연결 방법을 Wi-Fi Direct로 두고, Wi-Fi Direct 정보에 표시된 이름과 암호를 입력하세요. " +
                    "실행하면 Android가 이 네트워크에 연결할지 묻습니다. 연결 중에는 이 네트워크로 인터넷이 되지 않습니다.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(ssid, { ssid = it.take(32) }, label = { Text("네트워크 이름(SSID)") }, singleLine = true, enabled = !busy)
                OutlinedTextField(passphrase, { passphrase = it.take(63) }, label = { Text("암호") }, singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Text("이름과 암호는 저장하지 않고 이번 실행에만 씁니다.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("카메라와 휴대폰이 이미 같은 네트워크(공유기 또는 휴대폰 핫스팟)에 있을 때 씁니다.\n" +
                    "1. 핫스팟을 쓴다면 보안을 WPA2, 대역을 2.4GHz로 맞추세요. 카메라는 WPA3 전용 네트워크에 붙지 못할 수 있습니다.\n" +
                    "2. 카메라: 네트워크 → 액세스 지점 설정에서 네트워크를 등록 → PC Remote 연결 방법을 Wi-Fi 액세스 포인트로.\n" +
                    "3. 카메라: Wi-Fi 정보 표시에서 IP 주소를 확인해 아래에 입력하세요.\n" +
                    "이 방식은 카메라가 페어링을 요구합니다. 앱은 페어링 절차를 수행하지 않으므로, 연결 인사가 거부되거나 시간 초과로 끝나면 그 결과가 그대로 기록됩니다.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(address, { address = it.take(15) }, label = { Text("카메라 IP 주소") }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(acknowledged, { acknowledged = it }, enabled = enabled && !busy)
                Text("카메라에 이 앱의 무작위 식별값과 이름(beta7compact)을 보내고, 카메라 화면에 연결 확인창이 뜰 수 있음을 확인했습니다.",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
            if (!enabled) Text("사진 가져오기가 끝난 뒤 실행하세요.")
            TextButton(onClick = ::run, enabled = enabled && acknowledged && !busy) { Text(if (busy) "진단 진행 중… (최대 약 2분)" else "진단 실행") }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("카메라 화면에 확인창이 뜨면 내용을 기록하고 OK를 누르세요.")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            report?.let { result ->
                Text("결과 해석", style = MaterialTheme.typography.titleSmall)
                Text(when (result.failure) {
                    null -> "카메라가 PTP/IP 연결에 응답했습니다. 광고명령 줄의 0x9201·0x9202·0x9209가 '있음'이면 다음 단계(Wi-Fi 관찰 세션)를 시도할 수 있습니다."
                    WifiDiagnosticFailure.WIFI_UNAVAILABLE -> "카메라의 Wi-Fi에 연결하지 못했습니다. 이름·암호와 카메라의 Wi-Fi Direct 화면, 휴대폰 Wi-Fi가 켜져 있는지 확인하세요."
                    WifiDiagnosticFailure.NO_CAMERA_ADDRESS -> "네트워크에는 연결했지만 카메라 주소를 알아내지 못했습니다. 로그를 공유해주세요."
                    WifiDiagnosticFailure.CONNECTION_REFUSED -> "카메라가 15740 포트 연결을 거부했습니다. PC Remote가 켜져 있고 연결 방법이 Wi-Fi인지 확인하세요."
                    WifiDiagnosticFailure.TIMEOUT -> "응답을 기다리다 시간이 초과되었습니다. 카메라 화면에 확인창이 떠 있었는지, 주소가 맞는지 확인하세요."
                    WifiDiagnosticFailure.INIT_REJECTED -> "카메라가 연결 인사를 거부했습니다. 페어링이 필요한 방식일 수 있습니다. 거부사유 값을 포함해 로그를 공유해주세요."
                    else -> "연결 도중 실패했습니다. 실패 단계가 포함된 로그를 공유해주세요."
                })
                Text("공유할 진단 로그", style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(shared.orEmpty(), style = MaterialTheme.typography.bodySmall) }
                Text("위 내용만 공유합니다. 네트워크 이름·암호·카메라 식별값·기기 문자열은 제외하며 자동 저장·전송하지 않습니다.")
                Row {
                    TextButton(enabled = !busy, onClick = {
                        try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "beta7compact Wi-Fi 연결 진단"); putExtra(Intent.EXTRA_TEXT, shared)
                        }, "진단 로그 공유")) } catch (_: Exception) { error = "진단 로그를 공유할 앱이 없습니다." }
                    }) { Text("로그 공유") }
                    TextButton(enabled = !busy, onClick = { report = null }) { Text("결과 지우기") }
                }
            }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = { opened = false }) { Text("닫기") } })
}
