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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sukbeomh.beta7compact.ui.components.*
import dev.sukbeomh.beta7compact.update.UpdateState
import kotlinx.coroutines.*

/** Automatic check toggle, manual check and one-tap update from this repository's GitHub releases. */
@Composable private fun UpdateSetting(app: App) {
    val updater = app.updater
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by updater.state.collectAsStateWithLifecycle()
    var auto by remember { mutableStateOf(updater.autoCheck) }
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("앱을 열 때 새 버전 확인")
            Text("하루 한 번 · GitHub 릴리즈에 버전만 묻습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(auto, { auto = it; updater.setAutoCheck(it) })
    }
    Text("현재 버전 ${BuildConfig.VERSION_NAME}")
    when (val current = state) {
        UpdateState.Idle -> Unit
        UpdateState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
        is UpdateState.UpToDate -> Text(current.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is UpdateState.Available -> {
            Text("새 버전 ${current.release.version}", style = MaterialTheme.typography.titleMedium)
            if (current.release.notes.isNotEmpty()) Text(current.release.notes, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 8)
            Button(onClick = { updater.startInstall(current.release) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("${current.release.version} 내려받아 설치")
            }
            Text("설치는 Android 확인 화면을 거칩니다 · 원본 사진과 설정은 유지됩니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is UpdateState.Downloading -> {
            val fraction = (current.done.toFloat() / current.release.apkSize).coerceIn(0f, 1f)
            Text("${current.release.version} 내려받는 중 · ${(fraction * 100).toInt()}%")
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        is UpdateState.Installing -> {
            Text(if (current.awaitingUser) "${current.release.version} 설치 확인을 기다리는 중입니다" else "${current.release.version} 설치를 준비하는 중입니다")
            if (current.awaitingUser) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { updater.confirmIntent?.let { runCatching { context.startActivity(it) } } }) { Text("설치 확인 열기") }
                TextButton(onClick = updater::cancelInstall) { Text("설치 취소") }
            }
        }
        is UpdateState.Failed -> ErrorBanner(current.message, if (current.release != null) "다시 내려받기" else "다시 확인", {
            current.release?.let(updater::startInstall) ?: updater.checkNow()
        })
    }
    TextButton(onClick = updater::checkNow, enabled = !busy) { Text("지금 확인") }
}

@Composable fun SettingsScreen(activity: MainActivity, app: App, raw: Boolean, canChange: Boolean, changeRaw: (Boolean) -> Unit, recipes: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var notices by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ThemeSetting(app)
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
        Text("업데이트", style = MaterialTheme.typography.titleSmall)
        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { UpdateSetting(app) } }
        Text("연구 · 읽기 전용", style = MaterialTheme.typography.titleSmall)
        CameraDiagnosticsSetting(activity = activity, enabled = canChange)
        dev.sukbeomh.beta7compact.research.FirmwareInspectionCard()
        Text("연구 · 사진 보정 실험", style = MaterialTheme.typography.titleSmall)
        dev.sukbeomh.beta7compact.perspective.LocalWarpExperimentSetting(app)
        Text("연구 · PC Remote 실험", style = MaterialTheme.typography.titleSmall)
        PcRemoteDiagnosticsSetting(activity = activity, enabled = canChange)
        CaptureExperimentSetting(activity = activity, enabled = canChange)
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
