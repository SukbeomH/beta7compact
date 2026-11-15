package dev.sukbeomh.beta7compact.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import dev.sukbeomh.beta7compact.App
import dev.sukbeomh.beta7compact.library.Photo
import dev.sukbeomh.beta7compact.library.SavedRecipe
import dev.sukbeomh.beta7compact.recipe.*
import kotlinx.coroutines.*
import java.util.UUID

@Composable
fun RecipeLibrary(app: App, photos: List<Photo>) {
    val scope=rememberCoroutineScope()
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var importing by remember { mutableStateOf(false) }
    if(importing) RecipeImportDialog(app,close={importing=false})
    var selectionMode by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val work by remember { WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(RecipeWorker.NAME) }.collectAsStateWithLifecycle(emptyList())
    val active=work.firstOrNull { it.state==androidx.work.WorkInfo.State.RUNNING } ?: work.firstOrNull { !it.state.isFinished } ?: work.firstOrNull()
    if(editing.isNotEmpty()) {
        RecipeEditor(app,editing,onBack={ editing=emptyList() });return
    }
    Column {
        TextButton(onClick={importing=true}) { Text("레시피 코드 붙여넣기") }
        Text("가져온 원본 ${photos.size}개 · 사진을 눌러 레시피 편집")
        Row {
            TextButton(onClick={selectionMode=!selectionMode;selected=emptySet()}) { Text(if(selectionMode) "선택 해제" else "여러 장 선택") }
            if(selectionMode) TextButton(onClick={editing=photos.filter { it.key in selected }},enabled=selected.isNotEmpty()) { Text("${selected.size}장 같은 레시피") }
            TextButton(onClick={scope.launch { try { RecipeWorker.resume(app) } catch(e: Exception) { error="작업을 재개할 수 없습니다" } }}) { Text("작업 재개") }
        }
        active?.let { w ->
            val data=if(w.state.isFinished) w.outputData else w.progress
            Text("레시피 저장 ${data.getInt("done",0)} / ${data.getInt("total",0)} · ${w.state.name}")
            data.getString("error")?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            if(!w.state.isFinished) TextButton(onClick={WorkManager.getInstance(app).cancelUniqueWork(RecipeWorker.NAME)}) { Text("저장 중단") }
        }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        LazyVerticalGrid(columns=GridCells.Adaptive(140.dp),modifier=Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(photos,key={it.key}) { photo ->
                var thumbnail by remember(photo.uri) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(photo.uri) { thumbnail=withContext(Dispatchers.IO) {
                    runCatching { app.contentResolver.loadThumbnail(Uri.parse(photo.uri),Size(256,256),null) }.getOrNull()
                } }
                Card(Modifier.clickable {
                    if(selectionMode) selected=if(photo.key in selected) selected-photo.key else selected+photo.key
                    else editing=listOf(photo)
                }) {
                    thumbnail?.let { Image(it.asImageBitmap(),"원본 미리보기",Modifier.fillMaxWidth().height(110.dp)) }
                    Text((if(photo.key in selected) "✓ " else "")+photo.name,maxLines=1,modifier=Modifier.padding(8.dp))
                    if(photo.name.endsWith(".arw",true)) Text("RAW · 내장 JPEG만 편집",style=MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeEditor(app: App, photos: List<Photo>,onBack: () -> Unit) {
    val scope=rememberCoroutineScope();val store=remember { RecipeStore(app) };val photo=photos.first()
    val history=remember { RecipeHistory() }
    var recipe by remember { mutableStateOf(history.current) }
    fun change(next: Recipe) { history.set(next);recipe=history.current }
    var tab by remember { mutableIntStateOf(0) }
    var baking by remember { mutableStateOf(false) }
    var bakeSnapshot by remember { mutableStateOf<Recipe?>(null) }
    var entries by remember { mutableStateOf<List<LutEntry>>(emptyList()) }
    var category by remember { mutableStateOf("color_negative") }
    var original by remember { mutableStateOf<PhotoRenderer.Preview?>(null) }
    var before by remember { mutableStateOf<Bitmap?>(null) };var after by remember { mutableStateOf<Bitmap?>(null) }
    var compare by remember { mutableStateOf(false) };var rendering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) };var status by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) };var name by remember { mutableStateOf("내 레시피") }
    val exportWork by remember { WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(RecipeWorker.NAME) }.collectAsStateWithLifecycle(emptyList())
    val results by remember(photo.key) { app.db.recipes().results(photo.key) }.collectAsStateWithLifecycle(emptyList())
    suspend fun reload() { entries=withContext(Dispatchers.IO) { store.entries() } }
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) scope.launch {
            try { val id=withContext(Dispatchers.IO) { store.importCube(uri) };reload();change(recipe.copy(lutId=id,requiredLutName=""));category="user";error=null }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                val message=e.message
                error=if(e is IllegalArgumentException && message!=null && message.length<120 && message.any { it in '가'..'힣' }) message else ".cube 파일을 가져올 수 없습니다"
            }
            catch(e: OutOfMemoryError) { error="LUT 파일을 가져올 메모리가 부족합니다" }
        }
    }
    val exportCube=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val snapshot=bakeSnapshot
        if(uri!=null && snapshot!=null) scope.launch {
            baking=true
            try {
                withContext(Dispatchers.IO) {
                    val lut=store.load(snapshot.lutId)
                    app.contentResolver.openOutputStream(uri,"wt")?.bufferedWriter(Charsets.UTF_8)?.use { RecipeBaker.writeCube(snapshot,lut,it) }
                        ?: error("출력 파일을 열 수 없습니다")
                };status="33³ .cube 저장 완료 · 입력 변환은 LUT 앞에서 유지 · 그레인·비네트 제외";error=null
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { error="LUT를 내보낼 수 없습니다" }
            finally { baking=false;bakeSnapshot=null }
        } else bakeSnapshot=null
    }
    LaunchedEffect(photo.key) {
        try { reload();original=withContext(Dispatchers.IO) { PhotoRenderer.preview(app,photo) }
            original?.let { p -> before=withContext(Dispatchers.Default) { PhotoRenderer.upright(p.bitmap,p.orientation) } }
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { error=PhotoRenderer.reason(e) }
        catch(e: OutOfMemoryError) { error=PhotoRenderer.reason(e) }
    }
    LaunchedEffect(original,recipe) {
        val p=original ?: return@LaunchedEffect
        rendering=true;delay(60)
        try {
            after=withContext(Dispatchers.Default) { synchronized(PhotoRenderer.lock) {
                GpuEngine(store.load(recipe.lutId)).use { gpu ->
                    val processed=gpu.render(p.bitmap,recipe,fullWidth=p.sourceWidth,fullHeight=p.sourceHeight,
                        scaleX=p.sourceWidth.toFloat()/p.bitmap.width,scaleY=p.sourceHeight.toFloat()/p.bitmap.height)
                    val upright=PhotoRenderer.upright(processed,p.orientation)
                    if(upright!==processed) processed.recycle()
                    upright
                }
            } };error=null
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { after=null;error=PhotoRenderer.reason(e) }
        catch(e: OutOfMemoryError) { after=null;error=PhotoRenderer.reason(e) }
        finally { rendering=false }
    }
    @Composable fun Preview(modifier: Modifier) {
        Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row {
                TextButton(onClick=onBack) { Text("라이브러리") }
                Button(onClick={
                    val snapshot=recipe;saving=true;scope.launch {
                        try { RecipeWorker.start(app,photos.map { it.key },snapshot);status="${photos.size}장 저장 대기열에 추가했습니다";error=null }
                        catch(e: Exception) { error="저장 대기열을 만들지 못했습니다" }
                        finally { saving=false }
                    }
                },enabled=!saving && original!=null && after!=null && !rendering) { Text(if(photos.size>1) "${photos.size}장 저장" else "저장") }
            }
            if(photo.name.endsWith(".arw",true)) Text("RAW · 카메라 내장 JPEG만 편집")
            val display=if(compare) before else after ?: before
            display?.let { image ->
                Image(image.asImageBitmap(),if(compare) "처리 전" else "레시피 미리보기",Modifier.fillMaxWidth().height(280.dp).pointerInput(Unit) {
                    detectTapGestures(onPress={compare=true;try { awaitRelease() } finally { compare=false }})
                })
            }
            Text(if(rendering) "미리보기 처리 중…" else "미리보기를 길게 눌러 전/후 비교")
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) };status?.let { Text(it) }
            (exportWork.firstOrNull { it.state==androidx.work.WorkInfo.State.RUNNING } ?: exportWork.firstOrNull { !it.state.isFinished })?.let { w ->
                Text("저장 ${w.progress.getInt("done",0)} / ${w.progress.getInt("total",0)}")
                w.progress.getString("error")?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            }
        }
    }
    @Composable fun Panel(modifier: Modifier) {
        Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row {
                TextButton(enabled=history.canUndo,onClick={recipe=history.undo()}) { Text("실행 취소") }
                TextButton(enabled=history.canRedo,onClick={recipe=history.redo()}) { Text("다시 실행") }
            }
            TabRow(tab) { listOf("기본","톤커브","HSL","효과").forEachIndexed { i,label -> Tab(tab==i,{tab=i},text={Text(label)}) } }
            if(tab==0) {
                LazyRow { items(listOf("none","color_negative","slide","cinema_print","bw","creative","user")) { c ->
                    FilterChip(selected=category==c,onClick={category=c},label={Text(when(c) { "none"->"원본";"color_negative"->"네거";"slide"->"슬라이드";"cinema_print"->"시네마";"bw"->"흑백";"creative"->"크리에이티브";else->"사용자" })})
                } }
                LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(entries.filter { it.category==category },key={it.id}) { entry ->
                        var thumb by remember(entry.id) { mutableStateOf<Bitmap?>(null) }
                        LaunchedEffect(entry.id) { thumb=withContext(Dispatchers.IO) {
                            entry.preview?.let { runCatching { app.assets.open("luts/$it").use { input -> BitmapFactory.decodeStream(input) } }.getOrNull() }
                        } }
                        OutlinedCard(onClick={change(recipe.copy(lutId=entry.id,requiredLutName=""))},modifier=Modifier.width(116.dp)) {
                            thumb?.let { Image(it.asImageBitmap(),entry.name,Modifier.fillMaxWidth().height(72.dp)) }
                            Text((if(recipe.lutId==entry.id) "✓ " else "")+entry.name,modifier=Modifier.padding(4.dp),maxLines=2)
                        }
                    }
                }
                TextButton(onClick={pick.launch(arrayOf("*/*"))}) { Text("사용자 .cube 가져오기 (2~65)") }
            }
            TextButton(onClick={change(Recipe(inputTransform=recipe.inputTransform))}) { Text("색 보정 초기화 · 입력 변환 유지") }
            ColorControls(tab,recipe) { change(it) }
            Text("구운 LUT 재사용: 색 보정을 초기화하고 LUT를 선택하세요. 입력 변환은 유지합니다.")
            TextButton(enabled=!baking && entries.any { it.id==recipe.lutId },onClick={bakeSnapshot=recipe;exportCube.launch("beta7compact-recipe.cube")}) { Text(if(baking) "LUT 굽는 중…" else "현재 색을 33³ .cube로 내보내기") }
            RecipeManager(app,recipe,name,{name=it.take(80)}) { next,label -> change(next);name=label;status=label }
            results.forEach { result -> TextButton(onClick={
                try { app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(result.uri),"image/jpeg").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                catch(e: Exception) { error="결과 사진을 열 수 없습니다" }
            }) { Text("저장 결과 · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(result.created))}") } }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if(maxWidth>=700.dp) Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Preview(Modifier.weight(1f).verticalScroll(rememberScrollState()))
            Panel(Modifier.weight(1f).verticalScroll(rememberScrollState()))
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Preview(Modifier.fillMaxWidth());Panel(Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun AutoRecipeSetting(app: App) {
    var enabled by remember { mutableStateOf(app.settings.getBoolean("autoRecipe",false)) }
    var error by remember { mutableStateOf<String?>(null) }
    Row {
        Text("가져오기 후 기본 레시피 자동 적용",Modifier.weight(1f))
        Switch(enabled,onCheckedChange={next ->
            if(next && app.settings.getString("defaultRecipe",null)==null) error="편집 화면에서 기본 레시피를 먼저 지정하세요"
            else { enabled=next;app.settings.edit().putBoolean("autoRecipe",next).apply();error=null }
        })
    }
    Text("기본 꺼짐 · 원본을 보존하고 별도 JPEG로 저장합니다")
    error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
}
