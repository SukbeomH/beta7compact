package dev.sukbeomh.beta7compact.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import dev.sukbeomh.beta7compact.*
import dev.sukbeomh.beta7compact.R
import dev.sukbeomh.beta7compact.core.*
import dev.sukbeomh.beta7compact.library.Photo
import dev.sukbeomh.beta7compact.recipe.RecipeWorker
import dev.sukbeomh.beta7compact.transfer.ImportWorker
import dev.sukbeomh.beta7compact.ui.components.*
import dev.sukbeomh.beta7compact.usb.UsbAccess
import kotlinx.coroutines.*

private val NavigationSaver = listSaver<NavigationState, Any>(
    save = { listOf(it.tab.ordinal, it.picking, it.detailKey ?: "") + it.editorKeys },
    restore = { NavigationState(AppTab.entries[it[0] as Int], it.drop(3).map { key -> key as String }, it[1] as Boolean, (it[2] as String).ifEmpty { null }) }
)
@Composable fun MainScreen(activity: MainActivity, app: App) {
    val scope = rememberCoroutineScope()
    var navigation by rememberSaveable(stateSaver = NavigationSaver) {
        mutableStateOf(NavigationState(initialTab(runCatching { UsbAccess.device(app) }.isSuccess)))
    }
    fun dispatch(action: NavigationAction) { navigation = navigate(navigation, action) }
    var objects by remember { mutableStateOf<List<CameraObject>>(emptyList()) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf("Sony 카메라를 USB로 연결하세요") }
    var raw by remember { mutableStateOf(app.settings.getBoolean("raw", true)) }
    val usbDiagnostic by app.usbDiagnostic.collectAsStateWithLifecycle()
    val link by app.usbState.collectAsStateWithLifecycle()
    val refresh by activity.refresh.collectAsStateWithLifecycle()
    val records by app.db.photos().observe().collectAsStateWithLifecycle(emptyList())
    val work by remember { WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(ImportWorker.NAME) }.collectAsStateWithLifecycle(emptyList())
    val working = work.any { !it.state.isFinished }
    val completed = records.filter { it.imported != null }.flatMap { it.cameraObject().compatibleKeys }.toSet()
    val device = remember(refresh) { runCatching { UsbAccess.device(app) }.getOrNull() }
    val permission = device?.let { app.getSystemService(UsbManager::class.java).hasPermission(it) } == true
    val mtp = device?.let { camera -> (0 until camera.interfaceCount).any { camera.getInterface(it).interfaceClass == 6 } } == true
    val connected = permission && mtp && link == LinkState.SESSION
    val status = when {
        device == null -> "미연결"
        !mtp -> "MTP 필요"
        !permission -> "권한 필요"
        busy -> "연결 중"
        connected -> "연결됨"
        else -> "연결 확인"
    }
    fun scanUsb() {
        if (busy || working) return
        scope.launch {
            busy = true; error = null
            try {
                val camera = UsbAccess.device(app)
                check((0 until camera.interfaceCount).any { camera.getInterface(it).interfaceClass == 6 }) { "카메라 설정 → USB 연결 → MTP" }
                if (!app.getSystemService(UsbManager::class.java).hasPermission(camera)) {
                    detail = "USB 권한을 허용한 뒤 다시 스캔하세요"
                    objects = emptyList(); app.usb(LinkEvent.FOUND); return@launch
                }
                app.usb(LinkEvent.LOST); app.usb(LinkEvent.FOUND); app.usb(LinkEvent.OPEN)
                val list = withContext(Dispatchers.IO) { app.useUsb { it.list() } }
                objects = list; selected = selected.intersect(list.map { it.key }.toSet()).toList()
                app.usb(LinkEvent.READY); detail = "사진 ${list.size}장 · 새 사진 ${list.count { !it.isKnown(completed) }}장"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                objects = emptyList(); app.usb(LinkEvent.ERROR)
                detail = if (e.message?.any { it in '가'..'힣' } == true) e.message!! else "USB 연결·MTP 설정을 확인하세요"
                if (device != null) error = detail
            } finally { busy = false }
        }
    }
    fun resolve() {
        try { if (!permission && device != null) activity.usbPermission() else scanUsb() }
        catch (e: Exception) { error = "Sony 카메라를 USB로 연결하세요" }
    }
    fun resumeImport() {
        if (device == null || !mtp) { error = "같은 카드를 넣은 카메라를 MTP 모드로 연결하세요"; return }
        if (!permission) { resolve(); return }
        activity.requestNotifications { granted ->
            if (granted) ImportWorker.start(app) else error = "알림 권한을 앱 설정에서 허용하세요"
        }
    }
    fun receive(keys: Set<String>?) {
        activity.requestNotifications { granted ->
            if (!granted) error = "진행률 알림을 위해 알림 권한을 허용하세요"
            else scope.launch {
                try {
                    val plan = ImportPlan.create(objects, completed, keys)
                    app.db.photos().enqueue(plan.map { Photo.from(it) })
                    ImportWorker.start(app); selected = emptyList()
                    dispatch(NavigationAction.Back)
                } catch (e: Exception) { error = "대기열 저장에 실패했습니다. 저장 공간을 확인하세요" }
            }
        }
    }
    DisposableEffect(app) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key -> if (key == "raw") raw = prefs.getBoolean("raw", true) }
        app.settings.registerOnSharedPreferenceChangeListener(listener)
        onDispose { app.settings.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(refresh) { if (!working) scanUsb() }
    val saveable = rememberSaveableStateHolder()
    val editorPhotos = navigation.editorKeys.mapNotNull { key -> records.firstOrNull { it.key == key && it.imported != null } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val windowWidth = maxWidth.value
        val windowHeight = maxHeight.value
        val wide = windowClass(windowWidth) != WindowClass.COMPACT
        val editing = navigation.editorKeys.isNotEmpty()
        val detailPhoto = records.firstOrNull { it.key == navigation.detailKey }
        val fullScreen = editing || navigation.detailKey != null
        @Composable fun Navigation(rail: Boolean) {
            val icons = listOf(R.drawable.symbol_photo_camera, R.drawable.symbol_photo_library, R.drawable.symbol_palette, R.drawable.symbol_settings)
            if (rail) NavigationRail {
                AppTab.entries.forEachIndexed { i, tab -> NavigationRailItem(navigation.tab == tab,
                    { dispatch(NavigationAction.Tab(tab)) }, icon = { Symbol(icons[i]) }, label = { Text(tab.label) }) }
            } else NavigationBar {
                AppTab.entries.forEachIndexed { i, tab -> NavigationBarItem(navigation.tab == tab,
                    { dispatch(NavigationAction.Tab(tab)) }, icon = { Symbol(icons[i]) }, label = { Text(tab.label) }) }
            }
        }
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing, bottomBar = { if (!wide && !fullScreen) Navigation(false) }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide && !fullScreen) Navigation(true)
                Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = if (fullScreen) 0.dp else if (wide) 24.dp else 16.dp)) {
                    ObservedWorkBanner(app, ImportWorker.NAME, "가져오기", { resumeImport() }) { dispatch(NavigationAction.ViewImported) }
                    ObservedWorkBanner(app, RecipeWorker.NAME, "레시피 저장", { RecipeWorker.resume(app) }) { dispatch(NavigationAction.Tab(AppTab.PHOTOS)) }
                    error?.let { message -> ErrorBanner(message, if(message.contains("알림") || message.contains("공간")) "앱 설정 열기" else "다시 연결", {
                        if(message.contains("알림") || message.contains("공간")) activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${app.packageName}")))
                        else resolve()
                    }) { error = null } }
                    if (navigation.detailKey != null) {
                        if (detailPhoto != null) PhotoDetailScreen(app, detailPhoto,
                            { dispatch(NavigationAction.Edit(listOf(detailPhoto.key))) }, { dispatch(NavigationAction.Back) }, windowWidth, windowHeight)
                        else { BackHandler { dispatch(NavigationAction.Back) }; TextButton(onClick = { dispatch(NavigationAction.Back) }) { Text("사진으로 돌아가기") } }
                    } else if (editing) {
                        if (editorPhotos.isNotEmpty()) key(navigation.editorKeys) { RecipeEditor(app, editorPhotos, windowWidth, windowHeight) { dispatch(NavigationAction.Back) } }
                        else { BackHandler { dispatch(NavigationAction.Back) }; TextButton(onClick = { dispatch(NavigationAction.Back) }) { Text("사진으로 돌아가기") } }
                    } else {
                        ScreenTitle(if (navigation.picking) "사진 고르기" else navigation.tab.label)
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        saveable.SaveableStateProvider(navigation.tab.name) {
                            when (navigation.tab) {
                                AppTab.CAMERA -> if (navigation.picking) CameraPicker(objects, completed, selected.toSet(), working,
                                    { selected = it.toList() }, { cameraThumb(app, it, raw, working) }, { receive(selected.toSet()) }, { dispatch(NavigationAction.Back) })
                                else CameraScreen(status, connected, usbDiagnostic ?: detail, busy, working,
                                    objects.count { !it.isKnown(completed) }, records.count { it.imported == null }, ::resolve, ::scanUsb,
                                    { receive(null) }, {
                                        selected = objects.filter { !it.isKnown(completed) }.map { it.key }
                                        dispatch(NavigationAction.Pick)
                                    }, { resumeImport() }, { dispatch(NavigationAction.Tab(AppTab.PHOTOS)) })
                                AppTab.PHOTOS -> PhotosScreen(app, records.filter { it.imported != null },
                                    { dispatch(NavigationAction.Edit(it)) }, { dispatch(NavigationAction.View(it)) }, { dispatch(NavigationAction.Tab(AppTab.CAMERA)) }, windowWidth, windowHeight)
                                AppTab.RECIPES -> RecipesScreen(app) { dispatch(NavigationAction.Tab(AppTab.PHOTOS)) }
                                AppTab.SETTINGS -> SettingsScreen(activity, app, raw, !working && !busy, { value ->
                                    raw = value; app.settings.edit().putBoolean("raw", value).apply(); objects = emptyList(); scanUsb()
                                }, { dispatch(NavigationAction.Tab(AppTab.RECIPES)) })
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun cameraThumb(app: App, obj: CameraObject, raw: Boolean, importing: Boolean): Bitmap? {
    var bitmap by remember(obj.key, raw) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(obj.key, raw, importing) {
        if (!importing) bitmap = withContext(Dispatchers.IO) {
            runCatching { app.useUsb { camera -> camera.thumbnail(obj)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } } }.getOrNull()
        }
    }
    return bitmap
}
