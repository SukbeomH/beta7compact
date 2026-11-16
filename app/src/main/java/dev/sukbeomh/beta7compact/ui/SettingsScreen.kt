package dev.sukbeomh.beta7compact.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sukbeomh.beta7compact.*
import dev.sukbeomh.beta7compact.ui.components.*
import kotlinx.coroutines.*

@Composable fun SettingsScreen(activity: MainActivity, app: App, raw: Boolean, canChange: Boolean, changeRaw: (Boolean) -> Unit, recipes: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var notices by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("가져오기", style = MaterialTheme.typography.titleSmall)
        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("raw PTP로 가져오기"); Text("A7C 기본 · MTP 실패 시 자동 전환", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(raw, changeRaw, enabled = canChange)
            }
            Text("저장 위치 · DCIM/beta7compact/")
            Text("JPEG / ARW / HIF · 원본 보존", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        Text("레시피", style = MaterialTheme.typography.titleSmall)
        Card { Column(Modifier.padding(16.dp)) {
            AutoRecipeSetting(app, recipes)
            TextButton(onClick = recipes) { Text("기본 레시피 관리") }
        } }
        Text("위치 연동", style = MaterialTheme.typography.titleSmall)
        OutlinedCard { Column(Modifier.padding(16.dp)) { Text("BLE 위치 동기화 · 준비 중"); Text("GPS 좌표는 화면·로그·DB에 기록하지 않습니다", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        Text("진단 · 정보", style = MaterialTheme.typography.titleSmall)
        Card { Column(Modifier.padding(16.dp)) {
            TextButton(onClick = {
                try { activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, DiagnosticLog.shareText())
                }, "진단 로그 공유")) } catch (e: Exception) { error = "진단 로그를 공유할 앱이 없습니다" }
            }) { Text("진단 로그 공유") }
            Text("이번 실행의 고정 문구 이벤트만 공유합니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { scope.launch {
                try { notices = withContext(Dispatchers.IO) {
                    app.assets.open("material-symbols-NOTICE.txt").bufferedReader().use { it.readText() } + "\n\nLUT 출처·라이선스\n" +
                        app.assets.open("luts/NOTICE").bufferedReader().use { it.readText() }
                } } catch (e: Exception) { error = "라이선스 문서를 열 수 없습니다" }
            } }) { Text("오픈소스 라이선스 · LUT 출처") }
            Text("Material Symbols · Apache-2.0", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("버전 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", Modifier.padding(vertical = 16.dp))
        } }
        error?.let { ErrorBanner(it, "앱 설정 열기", { activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${app.packageName}"))) }) { error = null } }
    }
    notices?.let { text -> AlertDialog(onDismissRequest = { notices = null }, title = { Text("라이선스 · 출처") },
        text = { Text(text, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = { notices = null }) { Text("닫기") } }) }
}
