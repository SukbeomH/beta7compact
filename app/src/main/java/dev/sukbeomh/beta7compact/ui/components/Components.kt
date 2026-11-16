package dev.sukbeomh.beta7compact.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.sukbeomh.beta7compact.App
import dev.sukbeomh.beta7compact.R
import dev.sukbeomh.beta7compact.ui.theme.BetaTokens
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable fun Symbol(id: Int, description: String? = null, modifier: Modifier = Modifier) {
    Icon(painterResource(id), description, modifier.size(24.dp))
}
@Composable fun SymbolButton(id: Int, label: String, enabled: Boolean = true, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.size(BetaTokens.touchTarget)) { Symbol(id, label) }
}
@Composable fun ScreenTitle(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        actions()
    }
}
@Composable fun StatusChip(label: String, healthy: Boolean) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.symbol_usb), null, Modifier.size(20.dp),
                tint = if (healthy) MaterialTheme.colorScheme.primary else BetaTokens.warning)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
@Composable fun ErrorBanner(message: String, actionLabel: String, action: () -> Unit, dismiss: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Symbol(R.drawable.symbol_error)
                Text(message, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium)
                dismiss?.let { SymbolButton(R.drawable.symbol_close, "오류 닫기", action = it) }
            }
            TextButton(onClick = action, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Text(actionLabel) }
        }
    }
}
@Composable fun EmptyState(title: String, detail: String, actionLabel: String, action: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(R.drawable.symbol_photo_library), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = action) { Text(actionLabel) }
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable fun PhotoTile(name: String, bitmap: Bitmap?, selected: Boolean = false, badge: String = "", edited: Boolean = false,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            RoundedCornerShape(2.dp))
        .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "여러 장 선택")
        .semantics { contentDescription = name; this.selected = selected }) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(name.substringAfterLast('.').uppercase(), Modifier.align(Alignment.Center))
        if (badge.isNotEmpty()) Surface(Modifier.align(Alignment.BottomStart).padding(4.dp), color = BetaTokens.matte.copy(alpha = .8f)) {
            Text(badge, Modifier.padding(4.dp), color = BetaTokens.warning, style = MaterialTheme.typography.labelSmall)
        }
        if (selected) Icon(painterResource(R.drawable.symbol_check_circle), "선택됨", Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
        if (edited) Surface(Modifier.align(Alignment.BottomEnd).padding(4.dp), color = BetaTokens.matte.copy(alpha = .8f)) {
            Text("편집됨", Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}
@Composable fun WorkBanner(title: String, done: Int, total: Int, actionLabel: String, action: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Symbol(R.drawable.symbol_download)
                Text("$title · $done / $total", Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = action) { Text(actionLabel) }
            }
            if (total > 0) LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
/** Kept above destinations so both jobs remain visible while navigating or editing. */
@Composable fun ObservedWorkBanner(app: App, name: String, title: String, resume: suspend () -> Unit, view: () -> Unit) {
    val manager = remember(app) { WorkManager.getInstance(app) }
    val work by remember(name) { manager.getWorkInfosForUniqueWorkFlow(name) }.collectAsStateWithLifecycle(emptyList())
    var runningId by rememberSaveable(name) { mutableStateOf<String?>(null) }
    val active = work.firstOrNull { it.state == WorkInfo.State.RUNNING } ?: work.firstOrNull { !it.state.isFinished } ?: work.firstOrNull { it.id.toString() == runningId } ?: work.firstOrNull()
    var lastTotal by rememberSaveable(name) { mutableIntStateOf(0) }
    var completed by rememberSaveable(name) { mutableStateOf(false) }
    var dismissedId by rememberSaveable(name) { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun retry() { scope.launch { try { resume(); localError = null; dismissedId = null } catch (e: CancellationException) { throw e } catch (e: Exception) { localError = "작업을 재개할 수 없습니다" } } }
    LaunchedEffect(active?.id, active?.state, active?.progress) {
        active?.let {
            if (!it.state.isFinished) {
                if (runningId != it.id.toString()) lastTotal = 0
                runningId = it.id.toString(); completed = false
                lastTotal = it.progress.getInt("total", lastTotal)
            }
            else if (runningId == it.id.toString() && it.state == WorkInfo.State.SUCCEEDED) { completed = true }
        }
    }
    active?.let { w ->
        val data = if (w.state.isFinished) w.outputData else w.progress
        if (!w.state.isFinished) WorkBanner(title, data.getInt("done", 0), data.getInt("total", 0), "취소") { manager.cancelUniqueWork(name) }
        val message = data.getString("error") ?: if (w.state == WorkInfo.State.FAILED) "$title 작업이 중단되었습니다" else null
        if (message != null && dismissedId != w.id.toString()) ErrorBanner(message, "재개", ::retry) { dismissedId = w.id.toString() }
        if(w.state == WorkInfo.State.CANCELLED) WorkBanner("$title 중단됨",data.getInt("done",0),data.getInt("total",0),"재개",::retry)
        if (completed) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$title 완료" + (if(data.getInt("done", lastTotal)>0) " · ${data.getInt("done", lastTotal)}장" else ""), Modifier.weight(1f))
                TextButton(onClick = { completed = false; view() }) { Text("보기") }
                SymbolButton(R.drawable.symbol_close, "완료 알림 닫기") { completed = false }
            }
        }
    }
    localError?.let { ErrorBanner(it, "재시도", ::retry) { localError = null } }
}
