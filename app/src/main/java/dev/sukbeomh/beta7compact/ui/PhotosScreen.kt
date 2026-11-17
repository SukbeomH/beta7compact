package dev.sukbeomh.beta7compact.ui

import android.content.Intent
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
@Composable fun PhotosScreen(app: App, photos: List<Photo>, edit: (List<String>) -> Unit, view: (String) -> Unit, camera: () -> Unit, windowWidth: Float? = null, windowHeight: Float? = null) {
    val gridState = rememberLazyGridState()
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var focused by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("전체") }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<PhotoError?>(null) }
    // Existing per-photo result flows preserve Room v4 and react to completed exports.
    val results by remember(photos.map { it.key }) {
        if (photos.isEmpty()) flowOf(emptyList<RecipeResult>())
        else combine(photos.map { app.db.recipes().results(it.key) }) { rows -> rows.flatMap { it } }
    }.collectAsStateWithLifecycle(emptyList())
    val edited = results.map { it.photoKey }.toSet()
    val visible = photos.filter { when (filter) { "미편집" -> it.key !in edited; "편집됨" -> it.key in edited; "RAW" -> it.name.endsWith(".arw", true); else -> true } }
    val photo = photos.firstOrNull { it.key == focused } ?: visible.firstOrNull()
    val keys = selected.filter { key -> photos.any { it.key == key } }
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
                    badge = if (item.name.endsWith(".arw", true)) "RAW" else "", edited = item.key in edited,
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

    @Composable fun ListTools() {
        ScreenTitle("사진")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (selectionMode) "${keys.size}장 선택" else "가져온 원본 ${photos.size}장", Modifier.weight(1f))
                    TextButton(onClick = { selectionMode = !selectionMode; selected = emptyList() }) { Text(if (selectionMode) "선택 해제" else "선택") }
                    if (selectionMode) TextButton(onClick = ::share, enabled = keys.isNotEmpty()) { Text("공유") }
                }
                TextButton(onClick = ::resumeSave) { Text("보정 사진 저장 재개") }
                ChipStrip(listOf("전체", "미편집", "편집됨", "RAW"), filter) { filter = it }
                error?.let { failure -> ErrorBanner(failure.message, failure.actionLabel, { error = null; failure.retry() }) { error = null } }
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
                if (photos.isEmpty()) EmptyState("아직 가져온 사진이 없어요", "카메라를 연결해 원본을 가져오세요.", "카메라 탭으로", camera)
                else if (selectionMode) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (visible.isEmpty()) EmptyState("이 필터에 사진이 없어요", "선택한 사진은 유지됩니다. 전체 사진을 확인해 보세요.", "전체 보기", { filter = "전체" })
                        else Grid(Modifier.fillMaxSize(), wide, portrait)
                    }
                    ApplyRecipe()
                } else if (visible.isEmpty()) EmptyState("이 필터에 사진이 없어요", "전체 사진을 확인해 보세요.", "전체 보기", { filter = "전체" })
                else if (!wide || portrait) {
                    Grid(Modifier.weight(1f), wide, portrait)
                }
            }
    }
}
internal fun openPhoto(app: App, uri: String) {
    app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "image/jpeg").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
}
internal fun sharePhotos(app: App, uris: List<Uri>) {
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    intent.type = "image/*"; intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = android.content.ClipData.newRawUri("사진", uris.first()).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
    app.startActivity(Intent.createChooser(intent, "사진 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
