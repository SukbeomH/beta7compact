package dev.sukbeomh.beta7compact.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import dev.sukbeomh.beta7compact.recipe.RecipeWorker
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sukbeomh.beta7compact.App
import dev.sukbeomh.beta7compact.library.*
import dev.sukbeomh.beta7compact.recipe.Recipe
import dev.sukbeomh.beta7compact.ui.components.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private data class PhotoError(val message: String, val actionLabel: String, val retry: () -> Unit)

@Composable internal fun photoThumbnail(app: App, uri: String?, size: Int = 384): Bitmap? {
    var image by remember(uri, size) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri, size) {
        image = withContext(Dispatchers.IO) { runCatching { app.contentResolver.loadThumbnail(Uri.parse(uri), Size(size, size), null) }.getOrNull() }
    }
    return image
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun PhotosScreen(app: App, photos: List<Photo>, edit: (List<String>) -> Unit, view: (String) -> Unit, camera: () -> Unit, windowWidth: Float? = null, windowHeight: Float? = null) {
    val gridState = rememberLazyGridState()
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var focused by rememberSaveable { mutableStateOf<String?>(null) }
    var moreActions by remember { mutableStateOf(false) }
    BackHandler(enabled = selectionMode) { selectionMode = false; selected = emptyList() }
    var filter by rememberSaveable { mutableStateOf("전체") }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<PhotoError?>(null) }
    var adding by remember { mutableStateOf(false) }
    var addStatus by remember { mutableStateOf<String?>(null) }
    var addJob by remember { mutableStateOf<Job?>(null) }
    fun addImages(uris: List<Uri>) {
        if(uris.isEmpty() || adding) return
        if(uris.size>ExternalImagePolicy.MAX_ITEMS) {
            error=PhotoError("한 번에 50장까지 추가할 수 있습니다", "확인", {error=null});return
        }
        adding=true
        addJob=scope.launch {
            var added=0;var duplicate=0;var failed=0
            try {
                for((index,uri) in uris.distinct().withIndex()) {
                    addStatus="사진 추가 중 · ${index+1}/${uris.distinct().size}"
                    try {
                        val (key,result)=ExternalPhotoImporter.add(app,uri)
                        if(result==ExternalPhotoImporter.Result.ADDED) added++ else duplicate++
                        focused=key
                    } catch(e: CancellationException) {throw e}
                    catch(e: Exception) {failed++}
                    catch(e: OutOfMemoryError) {failed++}
                }
                filter="전체"
                addStatus="추가 ${added}장 · 이미 있음 ${duplicate}장" + if(failed>0) " · 실패 ${failed}장" else ""
                if(failed>0) error=PhotoError("추가하지 못한 사진은 지원 형식·64MiB 한도·읽기 권한을 확인해 주세요", "확인", {error=null})
                if(added>0 || duplicate>0) gridState.requestScrollToItem(0)
            } catch(e: CancellationException) {
                addStatus="사진 추가 중단 · 완료된 사진은 목록에 유지됩니다"
                throw e
            } finally {adding=false;addJob=null}
        }
    }
    val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ExternalImagePolicy.MAX_ITEMS),::addImages)
    val filePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(),::addImages)
    fun pickPhotos() {
        try {photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}
        catch(e: Exception) {error=PhotoError("사진 선택기를 열 수 없습니다. 더보기에서 파일로 추가해 주세요", "확인", {error=null})}
    }
    // Existing per-photo result flows preserve Room v4 and react to completed exports.
    val results by remember(photos.map { it.key }) {
        if (photos.isEmpty()) flowOf(emptyList<RecipeResult>())
        else combine(photos.map { app.db.recipes().results(it.key) }) { rows -> rows.flatMap { it } }
    }.collectAsStateWithLifecycle(emptyList())
    val edited = results.map { it.photoKey }.toSet()
    val visible = photos.filter { when (filter) { "미편집" -> it.key !in edited; "편집됨" -> it.key in edited; "RAW" -> it.name.endsWith(".arw", true); "외부" -> it.isExternal; else -> true } }
    val photo = photos.firstOrNull { it.key == focused } ?: visible.firstOrNull()
    val selection = resolvePhotoSelection(selected, photos.map { it.key }.toSet(), visible.map { it.key }.toSet())
    val keys = selection.keys
    fun share() {
        val uris = photos.filter { it.key in keys }.mapNotNull { it.uri?.let(Uri::parse) }
        try { sharePhotos(app, uris) } catch (e: Exception) { error = PhotoError("사진을 공유할 앱이 없습니다", "사진 공유 재시도", ::share) }
    }
    fun resumeSave() {
        scope.launch {
            try { RecipeWorker.resume(app); error = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = PhotoError("저장을 재개할 수 없습니다", "저장 재개 재시도", ::resumeSave) }
        }
    }
    fun openResult(uri: String) {
        try { openPhoto(app, uri) }
        catch (e: Exception) { error = PhotoError("결과 사진을 열 수 없습니다", "결과 열기 재시도", { openResult(uri) }) }
    }
    fun shareResult(uri: String) {
        try { sharePhotos(app, listOf(Uri.parse(uri))) }
        catch (e: Exception) { error = PhotoError("결과 사진을 공유할 앱이 없습니다", "결과 공유 재시도", { shareResult(uri) }) }
    }
    @Composable fun Grid(modifier: Modifier, wide: Boolean, portrait: Boolean = false) {
        LazyVerticalGrid(if (wide && !portrait) GridCells.Adaptive(128.dp) else GridCells.Fixed(3), modifier, state = gridState, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(visible, key = { it.key }) { item ->
                PhotoTile(item.name, photoThumbnail(app, item.uri), item.key in keys,
                    badge = if (item.isExternal) "외부" else if (item.name.endsWith(".arw", true)) "RAW" else "", edited = item.key in edited,
                    onLongClick = { selectionMode = true; selected = (selected + item.key).distinct(); focused = item.key }) {
                    focused = item.key
                    if (selectionMode) selected = if (item.key in selected) selected - item.key else selected + item.key
                    else if (!wide || portrait) view(item.key)
                }
            }
        }
    }
    @Composable fun Preview(modifier: Modifier) {
        BoxWithConstraints(modifier.background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
            val box = photoBox(maxWidth.value, maxHeight.value)
            val preview = photoPreview(app, photo)
            val image = preview.bitmap
            if (image != null) Image(image.asImageBitmap(), "선택 사진 미리보기", Modifier.size(box.width.dp, box.height.dp), contentScale = ContentScale.Fit)
            else if (preview.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            else ErrorBanner("미리보기를 읽을 수 없습니다", "사진 상세에서 재시도", { photo?.let { view(it.key) } })
        }
    }
    @Composable fun Info(modifier: Modifier) {
        Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            photo?.let { p ->
                Text(p.name, style = MaterialTheme.typography.titleMedium)
                Text(if (p.key in edited) "편집됨 · 원본 보존" else "미편집", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("저장 결과", style = MaterialTheme.typography.titleSmall)
                val saved = results.filter { it.photoKey == p.key }
                if (saved.isEmpty()) Text("아직 없음", color = MaterialTheme.colorScheme.onSurfaceVariant)
                saved.forEach { result ->
                    val recipe = remember(result.recipeJson) { runCatching { Recipe.fromJson(result.recipeJson) }.getOrNull() }
                    TextButton(onClick = { openResult(result.uri) }) { Text("${recipe?.lutId ?: "레시피"} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(result.created))}") }
                    TextButton(onClick = { shareResult(result.uri) }) { Text("결과 공유") }
                }
            }
        }
    }
    @Composable fun ApplyRecipe() {
        val targets = if (selectionMode) keys else listOfNotNull(photo?.key)
        Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 20.dp),
            contentAlignment = Alignment.CenterEnd) {
            Button(onClick = { edit(targets) }, enabled = targets.isNotEmpty(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.widthIn(min = 160.dp).heightIn(min = 48.dp)) {
                Text(if (selectionMode) "레시피 적용 (${keys.size}장)" else "레시피 적용")
            }
        }
    }

    @Composable fun SelectionActions() {
        FlowRow(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                Text("${keys.size}장 선택" + if(selection.hiddenCount > 0) " · 필터 밖 ${selection.hiddenCount}장" else "",
                    Modifier.semantics {liveRegion = LiveRegionMode.Polite}, style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onClick = {selectionMode = false; selected = emptyList()}) {Text("선택 종료")}
            OutlinedButton(onClick = ::share, enabled = keys.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {Text("공유")}
            Button(onClick = {edit(keys)}, enabled = keys.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {Text("레시피 적용")}
        }
    }
    @Composable fun ListTools() {
        ScreenTitle("사진 · ${photos.size}장") {
            if (!selectionMode) TextButton(onClick = ::pickPhotos,enabled=!adding) {Text("사진 추가")}
            Box {
                TextButton(onClick = {moreActions = true}) {Text("더보기")}
                DropdownMenu(expanded = moreActions, onDismissRequest = {moreActions = false}) {
                    DropdownMenuItem(text = {Text("사진 선택")},enabled=!selectionMode,onClick={moreActions=false;selectionMode=true;selected=emptyList()})
                    DropdownMenuItem(text = {Text("파일에서 사진 추가")},enabled=!adding && !selectionMode,onClick={
                        moreActions=false
                        try {filePicker.launch(arrayOf("image/*"))}
                        catch(e: Exception) {error=PhotoError("파일 선택기를 열 수 없습니다", "확인", {error=null})}
                    })
                    DropdownMenuItem(text = {Text("카메라에서 가져오기")},onClick={moreActions=false;camera()})
                    DropdownMenuItem(text = {Text("보정 사진 저장 재개")}, onClick = {moreActions = false; resumeSave()})
                }
            }
        }
        if(adding) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick={addJob?.cancel()}) {Text("사진 추가 중단")}
        }
        addStatus?.let {Text(it,Modifier.semantics {liveRegion=LiveRegionMode.Polite},style=MaterialTheme.typography.bodySmall)}
        ChipStrip(listOf("전체", "미편집", "편집됨", "RAW", "외부"), filter) {filter = it}
        error?.let {failure -> ErrorBanner(failure.message, failure.actionLabel, {error = null; failure.retry()}) {error = null}}
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val detailWidth = (maxWidth.value * .4f).coerceIn(240f, 360f).dp
        val portrait = isPortraitWindow(windowWidth ?: maxWidth.value, windowHeight ?: maxHeight.value)
        val wide = windowClass(windowWidth ?: maxWidth.value) != WindowClass.COMPACT
        if (wide && !portrait && !selectionMode && visible.isNotEmpty()) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ListTools()
                    Grid(Modifier.weight(1f).fillMaxWidth(), wide)
                }
                Column(Modifier.width(detailWidth).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Preview(Modifier.fillMaxWidth().weight(1f))
                    Info(Modifier.fillMaxWidth().weight(1f))
                    ApplyRecipe()
                }
            }
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ListTools()
                if (selectionMode) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (photos.isEmpty()) EmptyState("아직 추가한 사진이 없어요", "폰 사진을 추가하거나 더보기에서 카메라 원본을 가져오세요.", "사진 추가", {if(!adding) pickPhotos()})
                        else if (visible.isEmpty()) EmptyState("이 필터에 사진이 없어요", "선택한 사진은 유지됩니다. 전체 사진을 확인해 보세요.", "전체 보기", { filter = "전체" })
                        else Grid(Modifier.fillMaxSize(), wide, portrait)
                    }
                    SelectionActions()
                } else if (photos.isEmpty()) EmptyState("아직 추가한 사진이 없어요", "폰 사진을 추가하거나 더보기에서 카메라 원본을 가져오세요.", "사진 추가", {if(!adding) pickPhotos()})
                else if (visible.isEmpty()) EmptyState("이 필터에 사진이 없어요", "전체 사진을 확인해 보세요.", "전체 보기", { filter = "전체" })
                else if (!wide || portrait) {
                    Grid(Modifier.weight(1f), wide, portrait)
                }
            }
    }
}
internal fun openPhoto(app: App, uri: String) {
    app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), app.contentResolver.getType(Uri.parse(uri)) ?: "image/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
}
internal fun sharePhotos(app: App, uris: List<Uri>) {
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    intent.type = "image/*"; intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = android.content.ClipData.newRawUri("사진", uris.first()).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
    app.startActivity(Intent.createChooser(intent, "사진 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
