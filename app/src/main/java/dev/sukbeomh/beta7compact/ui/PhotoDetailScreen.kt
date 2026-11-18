package dev.sukbeomh.beta7compact.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sukbeomh.beta7compact.App
import dev.sukbeomh.beta7compact.R
import dev.sukbeomh.beta7compact.library.isExternal
import dev.sukbeomh.beta7compact.library.Photo
import dev.sukbeomh.beta7compact.recipe.*
import dev.sukbeomh.beta7compact.ui.components.*
import kotlinx.coroutines.*

internal data class PhotoPreviewState(val bitmap: Bitmap? = null, val loading: Boolean = true)
@Composable internal fun photoPreview(app: App, photo: Photo?, retry: Int = 0): PhotoPreviewState {
    var state by remember(photo?.key,photo?.uri) { mutableStateOf(PhotoPreviewState()) }
    LaunchedEffect(photo?.key,photo?.uri,retry) {
        state = PhotoPreviewState()
        val bitmap = if (photo == null) null else withContext(Dispatchers.IO) {
            try { PhotoRenderer.preview(app, photo).let { PhotoRenderer.upright(it.bitmap, it.orientation) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { null }
            catch (e: OutOfMemoryError) { null }
        }
        state = PhotoPreviewState(bitmap, loading = false)
    }
    return state
}
@Composable fun PhotoDetailScreen(app: App, photo: Photo, edit: () -> Unit, back: () -> Unit,
    appWindowWidth: Float? = null, appWindowHeight: Float? = null) {
    BackHandler(onBack = back)
    val results by remember(photo.key) { app.db.recipes().results(photo.key) }.collectAsStateWithLifecycle(emptyList())
    val photos by app.db.photos().observe().collectAsStateWithLifecycle(emptyList())
    var retry by remember { mutableIntStateOf(0) }
    var lutNames by remember { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(Unit) { lutNames = withContext(Dispatchers.IO) { runCatching { RecipeStore(app).entries().associate { it.id to it.name } }.getOrDefault(emptyMap()) } }
    val preview = photoPreview(app, photo, retry)
    val image = preview.bitmap
    var error by remember { mutableStateOf<String?>(null) }
    @Composable fun Picture(box: PhotoBox) {
        Box(Modifier.size(box.width.dp, box.height.dp).background(Color.Black), contentAlignment = Alignment.Center) {
            if (image != null) Image(image.asImageBitmap(), "사진 원본 미리보기", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else if (preview.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            else ErrorBanner("미리보기를 읽을 수 없습니다", "다시 시도", { retry++ })
        }
    }
    @Composable fun Info(modifier: Modifier) {
        Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (photo.isExternal) "추가일 · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(photo.captured))}" else if (photo.captured > 0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(photo.captured)) else "촬영일 미상")
                Text("${if (photos.any { it.key != photo.key && it.pair == photo.pair }) "RAW+JPEG 쌍 · " else ""}레시피 적용본 ${results.size}개")
                Text("원본은 수정되지 않습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = edit, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("편집") }
                OutlinedButton(onClick = { try { photo.uri?.let { sharePhotos(app, listOf(Uri.parse(it))) } } catch (e: Exception) { error = "사진을 공유할 앱이 없습니다" } }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("공유") }
            }
            Text("적용본", style = MaterialTheme.typography.titleSmall)
            if (results.isEmpty()) Text("아직 저장한 결과가 없습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { it.id }) { result ->
                    OutlinedCard(onClick = { try { openPhoto(app, result.uri) } catch (e: Exception) { error = "결과 사진을 열 수 없습니다" } }, modifier = Modifier.width(204.dp)) {
                        val thumbnail = photoThumbnail(app, result.uri)
                        Box(Modifier.fillMaxWidth().aspectRatio(1.5f).background(Color.Black), contentAlignment = Alignment.Center) {
                            thumbnail?.let { Image(it.asImageBitmap(), "저장 결과", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                        }
                        Text(resultLabel(result.recipeJson, lutNames), Modifier.padding(8.dp))
                        TextButton(onClick = { try { sharePhotos(app, listOf(Uri.parse(result.uri))) } catch (e: Exception) { error = "결과를 공유할 앱이 없습니다" } }) { Text("공유") }
                    }
                }
            }
            error?.let { ErrorBanner(it, "원본 열기", { try { photo.uri?.let { uri -> openPhoto(app, uri) } } catch(e: Exception) { error="원본을 열 앱이 없습니다" } }) { error = null } }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val windowWidth = maxWidth.value
        val windowHeight = maxHeight.value
        val layout = paneLayout(appWindowWidth ?: windowWidth, appWindowHeight ?: windowHeight)
        val scrollPage = layout == PaneLayout.STACKED && windowHeight < photoBox(windowWidth).height + 256f
        Column(if (scrollPage) Modifier.verticalScroll(rememberScrollState()) else Modifier.fillMaxSize()) {
            Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                SymbolButton(R.drawable.pixel_back, "사진 목록으로", action = back)
                Text(photo.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
            if (layout == PaneLayout.SIDE_BY_SIDE) Row(Modifier.weight(1f)) {
                val box = editorPhotoBox(windowWidth, windowHeight, layout)
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { Picture(box) }
                Info(Modifier.width(368.dp).fillMaxHeight())
            } else {
                Picture(photoBox(windowWidth))
                Info(if (scrollPage) Modifier.fillMaxWidth().height(600.dp) else Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}
