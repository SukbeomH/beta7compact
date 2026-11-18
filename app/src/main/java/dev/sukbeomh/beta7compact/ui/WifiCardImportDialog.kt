package dev.sukbeomh.beta7compact.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.sukbeomh.beta7compact.App
import dev.sukbeomh.beta7compact.transfer.SonyWifiCard
import dev.sukbeomh.beta7compact.transfer.WifiCardPhoto
import dev.sukbeomh.beta7compact.transfer.importWifiCardPhotos
import dev.sukbeomh.beta7compact.ui.components.ChipStrip
import dev.sukbeomh.beta7compact.ui.components.PhotoTile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val wifiThumbnailDispatcher = Dispatchers.IO.limitedParallelism(2)

@Composable
internal fun WifiCardImportDialog(app: App, onClose: () -> Unit, onOpenPhotos: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var client by remember { mutableStateOf<SonyWifiCard?>(null) }
    var photos by remember { mutableStateOf<List<WifiCardPhoto>?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var filter by remember { mutableStateOf("전체") }
    var job by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var imported by remember { mutableIntStateOf(0) }
    val busy = job != null
    val visible = remember(photos, filter) {
        photos.orEmpty().filter { when (filter) {
            "JPEG 원본" -> !it.converted
            "RAW 변환" -> it.converted
            else -> true
        } }
    }

    fun scan() {
        val target = address.takeIf { manual }
        selected = emptySet()
        photos = null
        error = null
        status = "카드 사진 목록을 읽는 중…"
        job = scope.launch {
            try {
                val (camera, result) = withContext(Dispatchers.IO) {
                    val camera = if (target == null) SonyWifiCard.discover(app) else SonyWifiCard.connected(app, target)
                    camera to camera.list { isActive }
                }
                client = camera
                photos = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = wifiImportError(failure, "카메라의 스마트폰에서 선택 화면과 Wi-Fi 주소를 확인하세요.")
            } finally {
                status = null
                job = null
            }
        }
    }

    fun receive() {
        val camera = client ?: return
        val batch = photos.orEmpty().filter { it.id in selected }
        if (batch.isEmpty()) return
        error = null
        var received = 0
        status = "사진 가져오는 중 · 0 / ${batch.size}"
        job = scope.launch {
            try {
                importWifiCardPhotos(app, camera, batch) { done ->
                    withContext(Dispatchers.Main) {
                        received = done
                        imported += 1
                        selected = selected - batch[done - 1].id
                        status = "사진 가져오는 중 · $done / ${batch.size}"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = wifiImportError(failure, "카메라 연결을 확인하고 남은 사진을 다시 가져오세요.")
            } finally {
                status = if (received == 0) null else "${received}장 가져오기 완료"
                job = null
            }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 780.dp).fillMaxWidth(0.95f).fillMaxHeight(0.88f),
            shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Wi-Fi 사진 가져오기", style = MaterialTheme.typography.headlineSmall)
                Text("카메라에서 스마트폰으로 보내기 → 스마트폰에서 선택을 열고, Android Wi-Fi 설정에서 카메라 네트워크에 연결하세요.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::scan, enabled = !busy && (!manual || address.isNotBlank())) { Text("목록 읽기") }
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }, enabled = !busy) { Text("Wi-Fi 설정") }
                    TextButton(onClick = { manual = !manual }, enabled = !busy) {
                        Text(if (manual) "주소 자동 찾기" else "주소 직접 입력")
                    }
                }
                if (manual) OutlinedTextField(address,
                    { address = it.take(15); client = null; photos = null; selected = emptySet(); status = null },
                    Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                    label = { Text("카메라 IP 주소") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (photos != null) {
                    Text("사진 ${photos.orEmpty().size}개 · RAW는 카메라의 JPEG 변환본으로 가져옵니다.",
                        style = MaterialTheme.typography.bodySmall)
                    if (!busy) ChipStrip(listOf("전체", "JPEG 원본", "RAW 변환"), filter) { filter = it }
                }
                status?.let { Text(it) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (visible.isEmpty()) {
                    Spacer(Modifier.weight(1f))
                } else {
                    BoxWithConstraints(Modifier.weight(1f)) {
                        LazyVerticalGrid(GridCells.Fixed(if (maxWidth < 500.dp) 3 else 5),
                            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(visible, key = { it.id }) { photo ->
                                val camera = client
                                var bitmap by remember(camera, photo.thumbnail) { mutableStateOf<Bitmap?>(null) }
                                LaunchedEffect(camera, photo.thumbnail, busy) {
                                    if (!busy && bitmap == null && camera != null && photo.thumbnail != null) {
                                        bitmap = withContext(wifiThumbnailDispatcher) {
                                            try {
                                                camera.thumbnail(photo)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                                            } catch (_: Exception) { null }
                                        }
                                    }
                                }
                                PhotoTile(photo.sourceName, bitmap, photo.id in selected,
                                    badge = if (photo.converted) "RAW → JPEG" else "JPEG 원본") {
                                    if (!busy) selected = if (photo.id in selected) selected - photo.id else selected + photo.id
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { if (busy) job?.cancel() else onClose() }) { Text(if (busy) "중단" else "닫기") }
                    if (imported > 0 && !busy) TextButton(onClick = onOpenPhotos) { Text("사진 탭 열기") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = ::receive, enabled = selected.isNotEmpty() && !busy) { Text("${selected.size}개 가져오기") }
                }
            }
        }
    }
}

private fun wifiImportError(failure: Exception, fallback: String): String =
    failure.message?.takeIf { it.length <= 140 && it.any { character -> character in '가'..'힣' } } ?: fallback
