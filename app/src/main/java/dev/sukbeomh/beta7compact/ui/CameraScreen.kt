package dev.sukbeomh.beta7compact.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sukbeomh.beta7compact.core.*
import dev.sukbeomh.beta7compact.ui.components.*
import java.text.SimpleDateFormat
import java.util.*

@Composable fun CameraScreen(status: String, connected: Boolean, detail: String, busy: Boolean, working: Boolean,
    newCount: Int, pendingCount: Int, resolve: () -> Unit, scan: () -> Unit, receive: () -> Unit,
    pick: () -> Unit, resume: () -> Unit, photos: () -> Unit, wifiImport: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = windowClass(maxWidth.value) != WindowClass.COMPACT
        @Composable fun Connection(modifier: Modifier) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Sony 카메라", style = MaterialTheme.typography.titleMedium)
                    StatusChip(status, connected)
                    Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (connected) {
                        Text("새 사진 ${newCount}장", style = MaterialTheme.typography.headlineMedium)
                        Button(onClick = receive, enabled = newCount > 0 && !busy && !working, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("새 사진 ${newCount}장 가져오기") }
                        if (newCount == 0 && !busy) TextButton(onClick = photos) { Text("모두 가져왔어요 · 사진 탭 열기") }
                    } else {
                        Text("1. 카메라 전원 켜기\n2. USB-C 케이블 연결\n3. 카메라 메뉴 → USB 연결 → MTP")
                        Button(onClick = resolve, enabled = !busy && !working) { Text(if (status == "권한 필요") "USB 권한 요청" else "다시 연결") }
                    }
                } }
                OutlinedButton(onClick = pick, enabled = connected && !busy && !working, modifier = Modifier.fillMaxWidth()) { Text("골라서 가져오기") }
                OutlinedButton(onClick = wifiImport, enabled = !busy && !working, modifier = Modifier.fillMaxWidth()) { Text("Wi-Fi로 사진 가져오기") }
                TextButton(onClick = scan, enabled = !busy && !working) { Symbol(dev.sukbeomh.beta7compact.R.drawable.pixel_refresh); Text("다시 스캔") }
                if (pendingCount > 0 && !working) WorkBanner("중단된 가져오기", 0, pendingCount, "이어서 받기", resume)
            }
        }
        @Composable fun Planned(modifier: Modifier) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("실험·예정 기능", style = MaterialTheme.typography.titleSmall)
                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Text("USB 라이브뷰 · 준비 중", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("촬영 영상 미리보기", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                Text("원본은 DCIM/beta7compact/에 보존합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (wide) Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Connection(Modifier.weight(1f)); Planned(Modifier.weight(1f))
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Connection(Modifier.fillMaxWidth()); Planned(Modifier.fillMaxWidth())
        }
    }
}

@Composable fun CameraPicker(objects: List<CameraObject>, completed: Set<String>, selected: Set<String>, working: Boolean,
    select: (Set<String>) -> Unit, thumb: @Composable (CameraObject) -> Bitmap?, receive: () -> Unit, back: () -> Unit) {
    BackHandler(onBack = back)
    var filter by rememberSaveable { mutableStateOf("새 사진만") }
    val visible = objects.filter { when (filter) {
        "새 사진만" -> !it.isKnown(completed)
        "RAW" -> it.mime == "image/x-sony-arw"
        "JPEG" -> it.mime == "image/jpeg"
        else -> true
    } }
    Column(Modifier.fillMaxSize()) {
        Row {
            TextButton(onClick = back) { Text("카메라로") }
            TextButton(onClick = {
                val eligible = visible.filter { !it.isKnown(completed) }.map { it.key }.toSet()
                select(if (selected.containsAll(eligible)) selected - eligible else selected + eligible)
            }, enabled = !working) { Text("전체 선택 / 해제") }
        }
        ChipStrip(listOf("새 사진만", "전체", "RAW", "JPEG"), filter) { filter = it }
        BoxWithConstraints(Modifier.weight(1f)) {
            val columns = if (maxWidth < 600.dp) 3 else 6
            LazyVerticalGrid(GridCells.Fixed(columns), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                visible.groupBy { cameraDay(it.captured) }.forEach { (day, items) ->
                    item(span = { GridItemSpan(maxLineSpan) }) { Text(day, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleSmall) }
                    items(items, key = { it.key }) { obj ->
                        val known = obj.isKnown(completed)
                        val pair = objects.any { it.pair == obj.pair && it.mime != obj.mime }
                        PhotoTile(obj.name, thumb(obj), obj.key in selected,
                            badge = when { known -> "가져옴"; pair -> "RAW + JPEG"; obj.mime == "image/x-sony-arw" -> "RAW"; obj.needsVerification(completed) -> "확인 필요"; else -> "" }) {
                            if (!known && !working) select(if (obj.key in selected) selected - obj.key else selected + obj.key)
                        }
                    }
                }
            }
        }
        Button(onClick = receive, enabled = selected.isNotEmpty() && !working, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp).heightIn(min = 48.dp)) { Text("${selected.size}장 가져오기") }
    }
}
private fun cameraDay(time: Long) = if (time <= 0) "촬영일 미상" else SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date(time))
