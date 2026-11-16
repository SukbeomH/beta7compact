package dev.sukbeomh.beta7compact.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import dev.sukbeomh.beta7compact.App
import dev.sukbeomh.beta7compact.R
import dev.sukbeomh.beta7compact.library.*
import dev.sukbeomh.beta7compact.recipe.*
import dev.sukbeomh.beta7compact.ui.components.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Composable internal fun EditorRecipeSave(app: App, recipe: Recipe, name: String, changeName: (String) -> Unit, onArchived: (Recipe) -> Unit, status: (String) -> Unit) {
    var showing by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    TextButton(onClick = { showing = true }) { Text("레시피로 보관") }
    if (showing) AlertDialog(onDismissRequest = { if (!saving) showing = false }, title = { Text("레시피로 보관") },
        text = { Column { OutlinedTextField(name, changeName, label = { Text("레시피 이름") }, enabled = !saving); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { TextButton(enabled = !saving, onClick = {
            val snapshot = recipe; saving = true
            scope.launch {
                try { status("${saveNewRecipe(app, name, snapshot)} 보관 완료"); onArchived(snapshot); showing = false }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = "레시피를 보관할 수 없습니다" }
                finally { saving = false }
            }
        }) { Text("보관") } }, dismissButton = { TextButton(enabled = !saving, onClick = { showing = false }) { Text("취소") } })
}

/** Marks a saved recipe that changes more than its LUT choice; sits over the card thumbnail. */
@Composable private fun AdjustedBadge(modifier: Modifier) {
    Box(modifier.size(20.dp).background(MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp)
        .background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.pixel_tune), "조정 적용됨", Modifier.size(9.dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable fun RecipesScreen(app: App, photos: () -> Unit) {
    val scope = rememberCoroutineScope()
    val saved by app.db.recipes().observe().collectAsStateWithLifecycle(emptyList())
    val library by app.db.photos().observe().collectAsStateWithLifecycle(emptyList())
    val results by remember(library.map { it.key }) {
        if (library.isEmpty()) flowOf(emptyList<RecipeResult>())
        else combine(library.map { app.db.recipes().results(it.key) }) { it.flatMap { rows -> rows } }
    }.collectAsStateWithLifecycle(emptyList())
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var importing by rememberSaveable { mutableStateOf(false) }
    var sharing by remember { mutableStateOf<SharedRecipe?>(null) }
    var renaming by remember { mutableStateOf<SavedRecipe?>(null) }
    var deleting by remember { mutableStateOf<SavedRecipe?>(null) }
    var linking by remember { mutableStateOf<SavedRecipe?>(null) }
    var rename by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<LutEntry>>(emptyList()) }
    var defaultJson by remember { mutableStateOf(app.settings.getString("defaultRecipe", null)) }
    var running by remember { mutableStateOf(false) }
    fun task(block: suspend () -> Unit) { scope.launch {
        running = true
        try { error = null; block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "레시피 작업을 완료하지 못했습니다" }
        finally { running = false }
    } }
    LaunchedEffect(Unit) { task { entries = withContext(Dispatchers.IO) { RecipeStore(app).entries() } } }
    val importLut = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = linking
        if (uri != null && target != null) task {
            val id = withContext(Dispatchers.IO) { RecipeStore(app).importCube(uri) }
            val linked = Recipe.fromJson(target.json).copy(lutId = id, requiredLutName = "")
            app.db.recipes().save(target.copy(json = linked.toJson()))
            entries = withContext(Dispatchers.IO) { RecipeStore(app).entries() }; linking = null
        }
    }
    val current = saved.firstOrNull { it.id == selected }
    fun sample(value: SavedRecipe) = results.filter { it.recipeJson == value.json }.maxByOrNull { it.created }?.uri
    /** [scrolls] must be false inside the compact list, which already scrolls: a nested vertical scroll is measured with unbounded height and throws. */
    @Composable fun Detail(value: SavedRecipe, modifier: Modifier, scrolls: Boolean) {
        val recipe = remember(value.json) { runCatching { Recipe.fromJson(value.json) }.getOrNull() }
        val entry = entries.firstOrNull { it.id == recipe?.lutId }
        Column(if (scrolls) modifier.verticalScroll(rememberScrollState()) else modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(value.name, style = MaterialTheme.typography.titleLarge)
            val image = photoThumbnail(app, sample(value), 1200)
            if (image != null) Image(image.asImageBitmap(), "최근 적용 사진", Modifier.fillMaxWidth().aspectRatio(1.5f), contentScale = ContentScale.Fit)
            else Text("아직 적용 사진이 없습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (recipe == null) ErrorBanner("레시피를 읽을 수 없습니다", "코드로 다시 가져오기", { importing = true })
            else {
                Text("${entry?.name ?: recipe.requiredLutName.ifBlank { "사용자 LUT 필요" }} · ${(recipe.strength * 100).toInt()}%")
                Text("노출 ${recipe.ev} EV · WB ${recipe.wb.temperature} / ${recipe.wb.tint}")
                Text("대비 ${recipe.edits.contrast} · 채도 ${recipe.edits.saturation} · 톤커브 ${recipe.edits.curves.rgb.points.size}점")
                if (entry == null) ErrorBanner("필요한 LUT가 없습니다", "LUT 연결", { linking = value })
                Button(onClick = photos, enabled = entry != null && !running) { Text("사진에서 적용") }
                Text("편집기 LUT 스트립의 ‘보관’에서 이 레시피를 선택하세요.", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = entry != null && !running, onClick = {
                    sharing = SharedRecipe(value.name, recipe, if (recipe.lutId.startsWith("user-")) entry!!.name else "")
                }) { Symbol(R.drawable.pixel_share); Text("공유 / QR") }
                TextButton(enabled = entry != null && !running, onClick = {
                    app.settings.edit().putString("defaultRecipe", value.json).apply(); defaultJson = value.json; message = "기본 레시피로 지정했습니다"
                }) { Text("기본으로 지정") }
                TextButton(enabled = !running, onClick = { renaming = value; rename = value.name }) { Text("이름 변경") }
                TextButton(enabled = !running, onClick = { task { message = "${saveNewRecipe(app, value.name, recipe)} 복제 완료" } }) { Text("복제") }
                TextButton(enabled = !running, onClick = { deleting = value }) { Text("삭제") }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = windowClass(maxWidth.value) != WindowClass.COMPACT
        @Composable fun ListPane(modifier: Modifier) {
            Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { importing = true }, modifier = Modifier.fillMaxWidth()) { Text("코드로 가져오기") }
                if(defaultJson == null) OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Text("기본 레시피 없음",style=MaterialTheme.typography.titleSmall)
                    Text("보관한 카드를 열고 ‘기본으로 지정’을 누르세요.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                defaultJson?.let { json ->
                    Card { Column(Modifier.padding(16.dp)) {
                        Text("기본 레시피", style = MaterialTheme.typography.titleSmall)
                        Text(saved.firstOrNull { it.json == json }?.name ?: "보관한 기본 설정")
                        AutoRecipeSetting(app) { selected = saved.firstOrNull()?.id }
                    } }
                }
                if (saved.isEmpty()) EmptyState("보관한 레시피가 없어요", "편집기에서 ‘레시피로 보관’을 누르면 여기에 모여요.", "사진 열기", photos)
                saved.forEach { value ->
                    val recipe = remember(value.json) { runCatching { Recipe.fromJson(value.json) }.getOrNull() }
                    val entry = entries.firstOrNull { it.id == recipe?.lutId }
                    OutlinedCard(onClick = { selected = if (selected == value.id && !wide) null else value.id }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                                val image = photoThumbnail(app, sample(value))
                                if (image != null) Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else FilmIcon(entry?.id, Modifier.size(32.dp))
                                if (recipe != null && isAdjusted(recipe)) AdjustedBadge(Modifier.align(Alignment.BottomEnd).padding(8.dp))
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(value.name, style = MaterialTheme.typography.titleMedium)
                                Text(entry?.name ?: "LUT 필요 · 연결", style = MaterialTheme.typography.bodySmall,
                                    color = if (entry == null) dev.sukbeomh.beta7compact.ui.theme.BetaTokens.warning else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconToggleButton(value.favorite, { favorite -> task { app.db.recipes().favorite(value.id, favorite) } }, enabled = !running) {
                                Icon(painterResource(R.drawable.pixel_star), if (value.favorite) "즐겨찾기 해제" else "즐겨찾기",
                                    Modifier.size(dev.sukbeomh.beta7compact.ui.theme.BetaTokens.pixelIcon), tint = if (value.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (!wide && current?.id == value.id) Detail(value, Modifier.fillMaxWidth().padding(8.dp), scrolls = false)
                }
                error?.let { ErrorBanner(it, "목록 다시 읽기", { task { entries = withContext(Dispatchers.IO) { RecipeStore(app).entries() } } }) { error = null } }
                message?.let { Text(it) }
            }
        }
        if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ListPane(Modifier.weight(1f)); current?.let { Detail(it, Modifier.weight(1f), scrolls = true) }
        } else ListPane(Modifier.fillMaxSize())
    }
    if (importing) RecipeImportDialog(app, close = { importing = false })
    sharing?.let { RecipeShareDialog(app, it) { sharing = null } }
    renaming?.let { value -> AlertDialog(onDismissRequest = { renaming = null }, title = { Text("레시피 이름 변경") },
        text = { OutlinedTextField(rename, { rename = it.take(80) }) },
        confirmButton = { TextButton(enabled = !running, onClick = { task { app.db.withTransaction {
            app.db.recipes().save(value.copy(name = RecipeShare.uniqueName(rename, app.db.recipes().names(value.id))))
        }; renaming = null } }) { Text("변경") } }, dismissButton = { TextButton(onClick = { renaming = null }) { Text("취소") } }) }
    deleting?.let { value -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("${value.name} 삭제") },
        text = { Text("보관한 레시피만 삭제합니다. 저장 결과와 원본은 유지됩니다.") },
        confirmButton = { TextButton(enabled = !running, onClick = { task { app.db.recipes().delete(value.id); deleting = null } }) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } }) }
    linking?.let { value -> AlertDialog(onDismissRequest = { linking = null }, title = { Text("필요한 LUT 연결") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("이 레시피에 사용할 사용자 LUT를 선택하세요.")
            entries.filter { it.id.startsWith("user-") }.forEach { entry -> TextButton(onClick = { task {
                val recipe = Recipe.fromJson(value.json).copy(lutId = entry.id, requiredLutName = "")
                app.db.recipes().save(value.copy(json = recipe.toJson())); linking = null
            } }) { Text(entry.name) } }
        }
    }, confirmButton = { TextButton(onClick = { importLut.launch(arrayOf("*/*")) }) { Text(".cube 가져와 연결") } },
        dismissButton = { TextButton(onClick = { linking = null }) { Text("보류") } }) }
}
