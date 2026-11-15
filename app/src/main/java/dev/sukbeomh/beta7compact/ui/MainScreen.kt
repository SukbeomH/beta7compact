package dev.sukbeomh.beta7compact.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import dev.sukbeomh.beta7compact.*
import dev.sukbeomh.beta7compact.ble.*
import dev.sukbeomh.beta7compact.core.*
import dev.sukbeomh.beta7compact.library.Photo
import dev.sukbeomh.beta7compact.transfer.ImportWorker
import dev.sukbeomh.beta7compact.usb.UsbAccess
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MissingPermission") // Each BLE user action requests grants; failures are shown as one-line errors.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(activity: MainActivity, app: App) {
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var objects by remember { mutableStateOf<List<CameraObject>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var usbText by remember { mutableStateOf("Sony 카메라를 USB로 연결하세요") }
    var found by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var raw by remember { mutableStateOf(app.settings.getBoolean("raw", false)) }
    var interval by remember { mutableIntStateOf(app.settings.getInt("interval", 10)) }
    val records by app.db.photos().observe().collectAsStateWithLifecycle(initialValue = emptyList())
    val location by app.locationState.collectAsStateWithLifecycle()
    val linkState by app.usbState.collectAsStateWithLifecycle()
    val refresh by activity.refresh.collectAsStateWithLifecycle()
    val work by remember { WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(ImportWorker.NAME) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val working = work.any { !it.state.isFinished }
    val workInfo = work.firstOrNull()
    val completed = records.filter { it.imported != null }.map { it.key }.toSet()
    fun scanUsb() {
        if (busy || working) return
        scope.launch {
            busy = true; error = null
            try {
                val camera = UsbAccess.device(app)
                if (!(0 until camera.interfaceCount).any { camera.getInterface(it).interfaceClass == 6 }) {
                    error("카메라 설정 → USB 연결 → MTP")
                }
                if (!app.getSystemService(android.hardware.usb.UsbManager::class.java).hasPermission(camera)) {
                    usbText = "USB 권한을 허용한 뒤 목록을 새로고침하세요"
                    objects = emptyList(); app.usb(LinkEvent.FOUND); return@launch
                }
                app.usb(LinkEvent.LOST); app.usb(LinkEvent.FOUND); app.usb(LinkEvent.OPEN)
                val list = withContext(Dispatchers.IO) { UsbAccess.use(app, raw) { it.list() } }
                objects = list; selected = selected.intersect(list.map { it.key }.toSet())
                app.usb(LinkEvent.READY); usbText = "사진 ${list.size}개 · 새 사진 ${list.count { it.key !in completed }}개"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                objects = emptyList(); app.usb(LinkEvent.ERROR)
                usbText = if (e.message?.any { it in '가'..'힣' } == true) e.message!! else "USB 연결·MTP 설정을 확인하세요"
            } finally { busy = false }
        }
    }
    fun receive(keys: Set<String>?) {
        activity.requestNotifications { granted ->
            if (!granted) error = "진행률 알림을 위해 알림 권한을 허용하세요"
            else scope.launch {
                try {
                    val plan = ImportPlan.create(objects, completed, keys)
                    app.db.photos().enqueue(plan.map { Photo.from(it) })
                    ImportWorker.start(app)
                    selected = emptySet()
                } catch (e: Exception) { error = "대기열 저장에 실패했습니다. 저장 공간을 확인하세요" }
            }
        }
    }
    LaunchedEffect(refresh) { if (!working) scanUsb() }
    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xff315e51))) {
        Scaffold(bottomBar = {
            NavigationBar {
                listOf("홈", "가져오기", "라이브러리", "설정").forEachIndexed { i, label ->
                    NavigationBarItem(selected = page == i, onClick = { page = i }, icon = { Text(listOf("⌂", "↓", "▦", "⚙")[i]) }, label = { Text(label) })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                Text("beta7compact", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 12.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { error = null }) { Text("닫기") } }
                if (page == 0 || page == 3) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                        Text("USB · ${when(linkState) { LinkState.SESSION -> "연결됨"; LinkState.CONNECTING -> "연결 중"; else -> "연결 확인" }}")
                        Text(usbText)
                        TextButton(onClick = { try { activity.usbPermission() } catch (e: Exception) { error = "Sony 카메라를 USB로 연결하세요" } }, enabled = !busy && !working) { Text("USB 연결 / 권한 허용") }
                    } }
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                        val chosen = app.settings.getString("camera", null)
                        val bonded = runCatching {
                            chosen != null && app.getSystemService(BluetoothManager::class.java).adapter?.getRemoteDevice(chosen)?.bondState == BluetoothDevice.BOND_BONDED
                        }.getOrDefault(false)
                        Text("BLE · ${if (bonded) "선택한 카메라 페어링됨" else "카메라 페어링 필요"}")
                        Text("카메라 Bluetooth 켬 → 페어링 → 위치정보 연동 켬")
                        TextButton(onClick = {
                            activity.requestBle { granted ->
                                if (!granted) error = "Bluetooth·정확한 위치·알림 권한을 허용하세요"
                                else scope.launch {
                                    busy = true
                                    try { found = BleScanner.scan(app); if (found.isEmpty()) error = "카메라가 없습니다. 페어링 화면을 열고 다시 검색하세요" }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = "BLE 검색 실패. Bluetooth·위치 기능을 확인하세요" }
                                    finally { busy = false }
                                }
                            }
                        }, enabled = !busy && !location.running) { Text(if (busy) "처리 중…" else "Sony 카메라 검색") }
                        found.forEachIndexed { index, device ->
                            TextButton(onClick = {
                                try {
                                    app.settings.edit().putString("camera", device.address).apply()
                                    check(device.bondState == BluetoothDevice.BOND_BONDED || device.createBond())
                                    found = emptyList(); error = "카메라와 휴대폰의 페어링 확인을 완료하세요"
                                } catch (e: Exception) { error = "페어링을 시작할 수 없습니다" }
                            }) { Text("${device.name ?: "Sony 카메라"} · ${index + 1} 선택") }
                        }
                    } }
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("위치 동기화", Modifier.weight(1f))
                            Switch(checked = location.running, onCheckedChange = { on ->
                                if (!on) app.stopService(Intent(app, LocationService::class.java))
                                else activity.requestBle { granted ->
                                    if (!granted) error = "정확한 위치·Bluetooth·알림 권한을 허용하세요"
                                    else try { androidx.core.content.ContextCompat.startForegroundService(activity, Intent(activity, LocationService::class.java)) }
                                    catch (e: Exception) { error = "앱을 연 상태에서 위치 동기화를 켜세요" }
                                }
                            })
                        }
                        Text(location.message)
                        Text("전송 간격 ${interval}초 · 마지막 전송 ${location.lastSent?.let { time(it) } ?: "없음"}")
                        Text("GPS 좌표는 화면·로그·DB에 기록하지 않습니다")
                    } }
                    if (page == 3) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("raw PTP로 가져오기", Modifier.weight(1f))
                            Switch(raw, onCheckedChange = { raw = it; app.settings.edit().putBoolean("raw", it).apply(); objects = emptyList(); scanUsb() }, enabled = !working && !busy)
                        }
                        Text("기본 MtpDevice · 세션 열기 실패 시 raw PTP 1회 대체")
                        Text("전송 간격 ${interval}초 (5~60초)")
                        Slider(value = interval.toFloat(), onValueChange = { interval = it.toInt(); app.settings.edit().putInt("interval", interval).apply() }, valueRange = 5f..60f, steps = 10)
                        Text("원본: DCIM/beta7compact/ · JPEG / ARW / HIF")
                        Text("중단 후 같은 카드를 연결하고 대기열 재개를 누르세요. 수신 중이던 파일은 처음부터 다시 받습니다.")
                    }
                } else if (page == 1) {
                    Text(usbText)
                    val lastError = workInfo?.outputData?.getString("error") ?: workInfo?.progress?.getString("error")
                    lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (working) {
                        Text("진행 ${workInfo?.progress?.getInt("done", 0)} / ${workInfo?.progress?.getInt("total", 0)}")
                        TextButton(onClick = { WorkManager.getInstance(app).cancelUniqueWork(ImportWorker.NAME) }) { Text("중단") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { scanUsb() }, enabled = !busy && !working) { Text("새로고침") }
                        TextButton(onClick = { receive(null) }, enabled = objects.isNotEmpty() && !working && !busy) { Text("새 사진 모두 받기") }
                        TextButton(onClick = { receive(selected) }, enabled = selected.isNotEmpty() && !working && !busy) { Text("선택 ${selected.size}개 받기") }
                        if (records.any { it.imported == null }) TextButton(onClick = { ImportWorker.start(app) }, enabled = !working) { Text("대기열 재개") }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val grouped = objects.groupBy { day(it.captured) }.toSortedMap(reverseOrder())
                    LazyVerticalGrid(columns = GridCells.Adaptive(140.dp), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        grouped.forEach { (date, photos) ->
                            item(span = { GridItemSpan(maxLineSpan) }) { Text(date, style = MaterialTheme.typography.titleMedium) }
                            items(photos, key = { "${it.storage}:${it.handle}" }) { obj ->
                                PhotoTile(obj.name, obj.key in completed, obj.key in selected,
                                    pair = photos.filter { it.pair == obj.pair }.map { it.mime }.toSet().containsAll(setOf("image/jpeg", "image/x-sony-arw")),
                                    thumb = { cameraThumb(app, obj, raw, working) }, onClick = {
                                        if (obj.key !in completed && !working) selected = if (obj.key in selected) selected - obj.key else selected + obj.key
                                    })
                            }
                        }
                    }
                } else {
                    val photos = records.filter { it.imported != null }
                    Text("가져온 원본 ${photos.size}개")
                    LazyVerticalGrid(columns = GridCells.Adaptive(140.dp), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(photos, key = { it.key }) { photo ->
                            PhotoTile(photo.name, true, false, photos.filter { it.pair == photo.pair }.map { it.cameraObject().mime }.toSet().containsAll(setOf("image/jpeg", "image/x-sony-arw")),
                                thumb = { libraryThumb(app, photo.uri!!) }, onClick = {
                                    try { activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(photo.uri), photo.cameraObject().mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                                    catch (e: Exception) { error = "이 원본 형식을 열 앱이 없습니다" }
                                })
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun PhotoTile(name: String, imported: Boolean, selected: Boolean, pair: Boolean, thumb: @Composable () -> Bitmap?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(
        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        val bitmap = thumb()
        if (bitmap != null) Image(bitmap.asImageBitmap(), name, Modifier.fillMaxWidth().height(110.dp), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) { Text(name.substringAfterLast('.').uppercase()) }
        Column(Modifier.padding(8.dp)) {
            Text(name, maxLines = 1)
            Text(if (imported) "가져옴" else if (selected) "선택됨" else "원본", style = MaterialTheme.typography.labelSmall)
            if (pair) Text("RAW + JPEG 쌍", style = MaterialTheme.typography.labelSmall)
        }
    }
}
@Composable
private fun cameraThumb(app: App, obj: CameraObject, raw: Boolean, importing: Boolean): Bitmap? {
    var bitmap by remember(obj.key, raw) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(obj.key, raw, importing) {
        if (!importing) bitmap = withContext(Dispatchers.IO) {
            runCatching { UsbAccess.use(app, raw) { camera -> camera.thumbnail(obj)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } } }.getOrNull()
        }
    }
    return bitmap
}
@Composable
private fun libraryThumb(app: App, uri: String): Bitmap? {
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) { runCatching { app.contentResolver.loadThumbnail(Uri.parse(uri), Size(256, 256), null) }.getOrNull() }
    }
    return bitmap
}
private fun day(value: Long) = if (value <= 0) "촬영일 미상" else SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date(value))
private fun time(value: Long) = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(value))
