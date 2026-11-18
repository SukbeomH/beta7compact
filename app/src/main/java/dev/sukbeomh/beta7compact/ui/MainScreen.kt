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
import dev.sukbeomh.beta7compact.library.isExternal
import dev.sukbeomh.beta7compact.library.isWifiImport
import dev.sukbeomh.beta7compact.library.Photo
import dev.sukbeomh.beta7compact.recipe.Recipe
import dev.sukbeomh.beta7compact.recipe.RecipeWorker
import dev.sukbeomh.beta7compact.transfer.ImportWorker
import dev.sukbeomh.beta7compact.ui.components.*
import dev.sukbeomh.beta7compact.update.UpdateState
import dev.sukbeomh.beta7compact.usb.UsbAccess
import kotlinx.coroutines.*

private val NavigationSaver = listSaver<NavigationState, Any>(
    save = { listOf(it.tab.ordinal, it.picking, it.detailKey ?: "") + it.editorKeys },
    restore = { NavigationState(AppTab.entries[it[0] as Int], it.drop(3).map { key -> key as String }, it[1] as Boolean, (it[2] as String).ifEmpty { null }) }
)
private val EditorExitSaver = listSaver<EditorExitState, String>(
    save = { listOf(it.initialRecipe.toJson(), it.recipe.toJson(), it.pending?.tab?.name ?: "",
        it.pending?.picking.toString(), it.pending?.detailKey ?: "") + it.pending?.editorKeys.orEmpty() },
    restore = { EditorExitState(Recipe.fromJson(it[0]), Recipe.fromJson(it[1]),
        if (it[2].isEmpty()) null else NavigationState(AppTab.valueOf(it[2]), it.drop(5), it[3].toBoolean(), it[4].ifEmpty { null })) }
)

@Composable fun MainScreen(activity: MainActivity, app: App) {
    val scope = rememberCoroutineScope()
    var navigation by rememberSaveable(stateSaver = NavigationSaver) {
        mutableStateOf(NavigationState(initialTab(runCatching { UsbAccess.device(app) }.isSuccess)))
    }
    var editorExitState by rememberSaveable(navigation.editorKeys, stateSaver = EditorExitSaver) { mutableStateOf(EditorExitState()) }
    var editorName by rememberSaveable(navigation.editorKeys) { mutableStateOf("내 레시피") }
    var archiveError by remember { mutableStateOf<String?>(null) }
    fun handleExit(event: EditorExitEvent) {
        val result = editorExit(editorExitState, event)
        editorExitState = result.state
        if (event is EditorExitEvent.Request || event == EditorExitEvent.Archive || event == EditorExitEvent.ContinueEditing) archiveError = null
        result.destination?.let { navigation = it }
        result.archive?.let { snapshot -> scope.launch {
            try {
                saveNewRecipe(app, editorName, snapshot)
                handleExit(EditorExitEvent.Archived(snapshot))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                handleExit(EditorExitEvent.ArchiveFailed)
                archiveError = "레시피를 저장할 수 없습니다. 다시 시도하거나 계속 편집하세요."
            }
        } }
    }
    fun dispatch(action: NavigationAction) {
        val destination = navigate(navigation, action)
        if (navigation.editorKeys.isNotEmpty() && destination != navigation) handleExit(EditorExitEvent.Request(destination))
        else navigation = destination
    }
    var objects by remember { mutableStateOf<List<CameraObject>>(emptyList()) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf(false) }
    var wifiImport by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf("Sony 카메라를 USB로 연결하세요") }
    var raw by remember { mutableStateOf(app.settings.getBoolean("raw", true)) }
    val usbDiagnostic by app.usbDiagnostic.collectAsStateWithLifecycle()
    val link by app.usbState.collectAsStateWithLifecycle()
    var objectGeneration by remember { mutableStateOf(-1L) }
    var thumbnailNamespace by remember { mutableStateOf<String?>(null) }
    val refresh by activity.refresh.collectAsStateWithLifecycle()
    val update by app.updater.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { app.updater.checkIfDue() }
    val records by app.db.photos().observe().collectAsStateWithLifecycle(emptyList())
    val work by remember { WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(ImportWorker.NAME) }.collectAsStateWithLifecycle(emptyList())
    val working = work.any { !it.state.isFinished }
    val completed = records.filter { it.imported != null && !it.isExternal }.flatMap { it.cameraObject().compatibleKeys }.toSet()
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
                val cacheGeneration = app.cameraThumbnails.token()
                val namespace = cameraThumbnailNamespace(app)
                val list = withContext(Dispatchers.IO) { app.useUsb { it.list() } }
                if (app.cameraThumbnails.token() != cacheGeneration) return@launch
                objectGeneration = cacheGeneration
                app.cameraThumbnails.retain(list.map { cameraThumbnailKey(it) }.toSet(), cacheGeneration)
                thumbnailNamespace = namespace
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
            val icons = listOf(R.drawable.pixel_camera, R.drawable.pixel_photos, R.drawable.pixel_recipes, R.drawable.pixel_settings)
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
                    ObservedWorkBanner(app, RecipeWorker.NAME, "보정 사진 저장", { RecipeWorker.resume(app) }) { dispatch(NavigationAction.Tab(AppTab.PHOTOS)) }
                    (update as? UpdateState.Available)?.takeIf { !fullScreen && navigation.tab != AppTab.SETTINGS }?.let { available ->
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("새 버전 ${available.release.version}", Modifier.weight(1f))
                                TextButton(onClick = { dispatch(NavigationAction.Tab(AppTab.SETTINGS)) }) { Text("설정에서 업데이트") }
                            }
                        }
                    }
                    error?.let { message -> ErrorBanner(message, if(message.contains("알림") || message.contains("공간")) "앱 설정 열기" else "다시 연결", {
                        if(message.contains("알림") || message.contains("공간")) activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${app.packageName}")))
                        else resolve()
                    }) { error = null } }
                    if (navigation.detailKey != null) {
                        if (detailPhoto != null) PhotoDetailScreen(app, detailPhoto,
                            { dispatch(NavigationAction.Edit(listOf(detailPhoto.key))) }, { dispatch(NavigationAction.Back) }, windowWidth, windowHeight)
                        else { BackHandler { dispatch(NavigationAction.Back) }; TextButton(onClick = { dispatch(NavigationAction.Back) }) { Text("사진으로 돌아가기") } }
                    } else if (editing) {
                        if (editorPhotos.isNotEmpty()) key(navigation.editorKeys) {
                            RecipeEditor(app, editorPhotos, windowWidth, windowHeight,
                                editorExitState.recipe, { handleExit(EditorExitEvent.Change(it)) },
                                editorName, { editorName = it.take(80) },
                                { handleExit(EditorExitEvent.Archived(it)) },
                                editorExitState.pending == null, { handleExit(EditorExitEvent.GestureCancelled) }
                            ) { dispatch(NavigationAction.Back) }
                        }
                        else { BackHandler { dispatch(NavigationAction.Back) }; TextButton(onClick = { dispatch(NavigationAction.Back) }) { Text("사진으로 돌아가기") } }
                    } else {
                        if (navigation.tab != AppTab.PHOTOS) ScreenTitle(if (navigation.picking) "사진 고르기" else navigation.tab.label)
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        saveable.SaveableStateProvider(navigation.tab.name) {
                            when (navigation.tab) {
                                AppTab.CAMERA -> if (navigation.picking) CameraPicker(objects, completed, selected.toSet(), working,
                                    { selected = it.toList() }, { cameraThumb(app, it, raw, working, thumbnailNamespace, objectGeneration) }, { receive(selected.toSet()) }, { dispatch(NavigationAction.Back) })
                                else CameraScreen(status, connected, usbDiagnostic ?: detail, busy, working,
                                    objects.count { !it.isKnown(completed) }, records.count { it.imported == null && !it.isWifiImport }, ::resolve, ::scanUsb,
                                    { receive(null) }, {
                                        selected = objects.filter { !it.isKnown(completed) }.map { it.key }
                                        dispatch(NavigationAction.Pick)
                                    }, { resumeImport() }, { dispatch(NavigationAction.Tab(AppTab.PHOTOS)) }, { wifiImport = true })
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
    if (wifiImport) WifiCardImportDialog(app, onClose = { wifiImport = false }, onOpenPhotos = {
        wifiImport = false
        dispatch(NavigationAction.Tab(AppTab.PHOTOS))
    })
    if (editorExitState.pending != null) AlertDialog(
        onDismissRequest = { handleExit(EditorExitEvent.ContinueEditing) },
        title = { Text("보정 설정을 레시피로 저장할까요?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("저장하지 않은 보정 설정이 있습니다. 레시피로 저장하면 다른 사진에도 사용할 수 있습니다. 사진 파일은 생성하지 않습니다.")
            OutlinedTextField(editorName, { editorName = it.take(80) }, label = { Text("레시피 이름") }, enabled = !editorExitState.archiving)
            archiveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !editorExitState.archiving, onClick = { handleExit(EditorExitEvent.Archive) }) {
            Text(if (editorExitState.archiving) "저장 중…" else "레시피 저장")
        } },
        dismissButton = { Row {
            TextButton(enabled = !editorExitState.archiving, onClick = { handleExit(EditorExitEvent.ContinueEditing) }) { Text("계속 편집") }
            TextButton(enabled = !editorExitState.archiving, onClick = { handleExit(EditorExitEvent.Discard) }) { Text("버리기") }
        } },
    )
}
private fun cameraThumbnailKey(obj: CameraObject) = "${obj.storage}/${obj.parent}/${obj.handle}/${obj.metadataKey}"

private fun cameraThumbnailNamespace(app: App): String? = runCatching {
    val device = UsbAccess.device(app)
    val serial = device.serialNumber?.takeIf { it.isNotBlank() } ?: return@runCatching null
    dev.sukbeomh.beta7compact.cache.DiskThumbnailCache.hash("${device.vendorId}/${device.productId}/$serial")
}.getOrNull()

@Composable private fun cameraThumb(app: App, obj: CameraObject, raw: Boolean, importing: Boolean, namespace: String?, objectGeneration: Long): Bitmap? {
    val key = cameraThumbnailKey(obj)
    val diskKey = namespace?.takeIf { obj.hasRawTime }?.let { "$it/${obj.storage}/${obj.parent}/${obj.metadataKey}" }
    val generation = app.cameraThumbnails.token()
    if (generation != objectGeneration) return null
    var bitmap by remember(key, generation) { mutableStateOf(app.cameraThumbnails.get(key)) }
    LaunchedEffect(key, generation, raw, importing) {
        if (!importing && bitmap == null) {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    // Check again under USB ownership so queued duplicate requests reuse the first result.
                    app.useUsb { camera ->
                        if (app.cameraThumbnails.token() != generation) null
                        else app.cameraThumbnails.get(key) ?: run {
                            val cached = diskKey?.takeIf { cameraThumbnailNamespace(app) == namespace }?.let { app.diskThumbnails.get(it) }
                            val decoded = cached?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                            if (cached != null && decoded == null && diskKey != null) app.diskThumbnails.remove(diskKey)
                            val image = decoded ?: camera.thumbnail(obj)?.let { data ->
                                BitmapFactory.decodeByteArray(data, 0, data.size)?.also {
                                    if (diskKey != null && cameraThumbnailNamespace(app) == namespace) app.diskThumbnails.put(diskKey, data)
                                }
                            }
                            image?.also { app.cameraThumbnails.put(key, it, generation) }
                        }
                    }
                }
                if (app.cameraThumbnails.token() == generation) bitmap = loaded
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Failed reads are not cached; a later visit can retry. */ }
            catch (_: OutOfMemoryError) { app.cameraThumbnails.clear() }
        }
    }
    return bitmap
}
