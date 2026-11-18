package dev.sukbeomh.beta7compact.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import dev.sukbeomh.beta7compact.R
import dev.sukbeomh.beta7compact.ui.components.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sukbeomh.beta7compact.App
import dev.sukbeomh.beta7compact.library.Photo
import dev.sukbeomh.beta7compact.recipe.*
import kotlinx.coroutines.*

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun RecipeEditor(app: App, photos: List<Photo>, appWindowWidth: Float? = null, appWindowHeight: Float? = null,
    currentRecipe: Recipe, onRecipeChange: (Recipe) -> Unit, name: String, changeName: (String) -> Unit,
    onArchived: (Recipe) -> Unit, backEnabled: Boolean, onGestureCancelled: () -> Unit, onBack: () -> Unit) {
    val scope=rememberCoroutineScope();val store=remember { RecipeStore(app) };var previewIndex by rememberSaveable { mutableIntStateOf(0) };val photo=photos[previewIndex.coerceIn(0,photos.lastIndex)]
    val history=remember { RecipeHistory(currentRecipe) }
    val recipe=currentRecipe
    // One touch on the panel is one undo step: every change after the first while that finger stays down is folded into it.
    val touch=remember { object { var down=false;var changed=false } }
    val touchTracker=Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while(true) {
                val pressed=awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
                if(pressed && !touch.down) touch.changed=false
                touch.down=pressed
            }
        }
    }
    fun change(next: Recipe) {
        val before=history.current
        history.set(next,continuing=touch.down && touch.changed)
        if(history.current!=before && touch.down) touch.changed=true
        onRecipeChange(history.current)
    }
    val panelScroll=rememberScrollState()
    var details by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var tabletopOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    val saved by app.db.recipes().observe().collectAsStateWithLifecycle(emptyList())
    val strengthTarget=remember { BringIntoViewRequester() }
    PredictiveBackHandler(enabled=backEnabled) { events ->
        try { events.collect { backProgress=it.progress };onBack() }
        catch(e: CancellationException) { onGestureCancelled() }
        finally { backProgress=0f }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var baking by remember { mutableStateOf(false) }
    var bakeSnapshot by remember { mutableStateOf<Recipe?>(null) }
    var entries by remember { mutableStateOf<List<LutEntry>>(emptyList()) }
    var category by rememberSaveable { mutableStateOf("color_negative") }
    var original by remember { mutableStateOf<PhotoRenderer.Preview?>(null) }
    var before by remember { mutableStateOf<Bitmap?>(null) };var after by remember { mutableStateOf<Bitmap?>(null) }
    var compare by remember { mutableStateOf(false) };var rendering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) };var status by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
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
                };status="33³ .cube 저장 완료 · 입력 변환은 LUT 앞에서 유지 · 그레인·비네트·왜곡 제외";error=null
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { error="LUT를 내보낼 수 없습니다" }
            finally { baking=false;bakeSnapshot=null }
        } else bakeSnapshot=null
    }
    LaunchedEffect(photo.key,retry) {
        original=null;before=null;after=null
        try { reload();original=withContext(Dispatchers.IO) { PhotoRenderer.preview(app,photo) }
            original?.let { p -> before=withContext(Dispatchers.Default) { PhotoRenderer.upright(p.bitmap,p.orientation) } }
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { error=PhotoRenderer.reason(e) }
        catch(e: OutOfMemoryError) { error=PhotoRenderer.reason(e) }
    }
    LaunchedEffect(original,recipe,retry) {
        val p=original ?: return@LaunchedEffect
        rendering=true;delay(60)
        try {
            after=withContext(Dispatchers.Default) { val execution=coroutineContext; synchronized(PhotoRenderer.lock) {
                GpuEngine(store.load(recipe.lutId)).use { gpu ->
                    val warped=DistortionRenderer.preview(p.bitmap,p.sourceWidth,p.sourceHeight,recipe.distortion) {execution.ensureActive()}
                    try {
                        val processed=gpu.render(warped,recipe,fullWidth=p.sourceWidth,fullHeight=p.sourceHeight,
                            scaleX=p.sourceWidth.toFloat()/p.bitmap.width,scaleY=p.sourceHeight.toFloat()/p.bitmap.height)
                        try { PhotoRenderer.upright(processed,p.orientation) }
                        finally { if(p.orientation!=1) processed.recycle() }
                    } finally { if(warped!==p.bitmap) warped.recycle() }
                }
            } };error=null
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { after=null;error=PhotoRenderer.reason(e) }
        catch(e: OutOfMemoryError) { after=null;error=PhotoRenderer.reason(e) }
        finally { rendering=false }
    }

    fun save() {
        val snapshot=recipe;saving=true;scope.launch {
            try { RecipeWorker.start(app,photos.map { it.key },snapshot);status="보정 사진 ${photos.size}장을 JPEG 저장 대기열에 추가했습니다";error=null }
            catch(e: Exception) { error="저장 대기열을 만들지 못했습니다" }
            finally { saving=false }
        }
    }
    fun recover() {
        val reason=error.orEmpty()
        when {
            reason.contains("내보") -> {bakeSnapshot=recipe;exportCube.launch("beta7compact-recipe.cube")}
            reason.contains("LUT") || reason.contains(".cube") -> pick.launch(arrayOf("*/*"))
            reason.contains("저장 대기열") -> save()
            else -> retry++
        }
    }
    var headerHeightPx by remember { mutableIntStateOf(0) }
    @Composable fun Header() {
        Row(Modifier.fillMaxWidth().onSizeChanged {headerHeightPx=it.height}.heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) {
            SymbolButton(R.drawable.pixel_back,"사진으로 돌아가기",action=onBack)
            Text(if(photos.size>1) "${photos.size}장에 적용" else "편집",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
        }
    }
    @Composable fun SaveActions() {
        Surface(color=MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,top=8.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.End),
                    verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row {
                        SymbolButton(R.drawable.pixel_compare,if(compare) "보정 사진 보기" else "원본 보기") {compare=!compare}
                        SymbolButton(R.drawable.pixel_undo,"실행 취소",history.canUndo) {onRecipeChange(history.undo())}
                        SymbolButton(R.drawable.pixel_redo,"다시 실행",history.canRedo) {onRecipeChange(history.redo())}
                    }
                    EditorRecipeSave(app,recipe,name,changeName,onArchived) {status=it}
                    Button(onClick=::save,enabled=!saving && original!=null && after!=null && !rendering,
                        modifier=Modifier.heightIn(min=48.dp)) {
                        Text(if(saving) "저장 준비 중…" else if(photos.size>1) "사진 ${photos.size}장 저장" else "사진 저장")
                    }
                }
                status?.let {Text(it,Modifier.semantics {liveRegion=LiveRegionMode.Polite},style=MaterialTheme.typography.bodySmall)}
            }
        }
    }
    @Composable fun Preview(box: PhotoBox) {
        Box(Modifier.size(box.width.dp,box.height.dp).background(androidx.compose.ui.graphics.Color.Black)) {
            val display=if(compare) before else after ?: before
            display?.let { image ->
                Image(image.asImageBitmap(),if(compare) "처리 전" else "레시피 미리보기 · 길게 눌러 원본",
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures(onPress={compare=true;try { awaitRelease() } finally { compare=false }})
                    },contentScale=ContentScale.Fit)
            }
            if(rendering) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            if(display==null && error==null) Text("미리보기 읽는 중…",Modifier.align(Alignment.Center),color=androidx.compose.ui.graphics.Color.White)
            if(display==null && error!=null) ErrorBanner(error!!,"다시 시도",{retry++})
        }
    }
    @Composable fun Panel(modifier: Modifier, tabletop: Boolean) {
        Surface(modifier,shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxSize().then(touchTracker)) {
            Column(Modifier.weight(1f).verticalScroll(panelScroll).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(photos.size>1) LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(photos.indices.toList()) { i ->
                        Column(Modifier.width(72.dp)) {
                            PhotoTile(photos[i].name,photoThumbnail(app,photos[i].uri),selected=previewIndex==i) {previewIndex=i}
                            Text("${i+1}",style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if(photo.name.endsWith(".arw",true)) Row(verticalAlignment=Alignment.CenterVertically) {
                    Symbol(R.drawable.symbol_raw_on,size=24.dp)
                    Text("RAW · 카메라 내장 JPEG로 편집",color=dev.sukbeomh.beta7compact.ui.theme.BetaTokens.warning)
                }
                Text("사진 저장: 보정된 사진을 별도 JPEG로 저장합니다. 원본은 유지됩니다.",style=MaterialTheme.typography.bodySmall)
                Text("레시피 저장: 현재 설정을 레시피로 저장해 다른 사진에도 사용할 수 있습니다.",style=MaterialTheme.typography.bodySmall)
                Text("LUT",style=MaterialTheme.typography.titleSmall)
                val categories=listOf("보관","네거","슬라이드","시네마","흑백","크리에이티브","사용자")
                val categoryIds=listOf("saved","color_negative","slide","cinema_print","bw","creative","user")
                ChipStrip(categories,categories.getOrElse(categoryIds.indexOf(category)) { "네거" }) { category=categoryIds[categories.indexOf(it)] }
                LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(category=="saved") {
                        items(saved,key={it.id}) { value ->
                            OutlinedCard(onClick={
                                val next=runCatching { Recipe.fromJson(value.json) }.getOrNull()
                                if(next!=null && entries.any { it.id==next.lutId }) {change(next);changeName(value.name)}
                                else {error="이 레시피에 필요한 LUT가 없습니다. 사용자 LUT를 선택하세요";category="user"}
                            },modifier=Modifier.width(88.dp).heightIn(min=96.dp)) { Text(value.name,Modifier.padding(8.dp),maxLines=3) }
                        }
                    } else {
                        val shown=entries.filter { it.id=="identity" || it.category==category }.distinctBy { it.id }
                        items(shown,key={it.id}) { entry ->
                            var thumb by remember(entry.id) { mutableStateOf<Bitmap?>(null) }
                            LaunchedEffect(entry.id) { thumb=withContext(Dispatchers.IO) {
                                entry.preview?.takeIf { entry.id !in FilmIcons.specs }?.let { runCatching { app.assets.open("luts/$it").use(BitmapFactory::decodeStream) }.getOrNull() }
                            } }
                            Column(Modifier.width(80.dp).semantics { selected=recipe.lutId==entry.id }.clickable {
                                if(recipe.lutId==entry.id) scope.launch { strengthTarget.bringIntoView() }
                                else change(recipe.copy(lutId=entry.id,requiredLutName=""))
                            }) {
                                Box(Modifier.size(72.dp).border(if(recipe.lutId==entry.id) 2.dp else 1.dp,
                                    if(recipe.lutId==entry.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,RoundedCornerShape(8.dp)),contentAlignment=Alignment.Center) {
                                    if(entry.id in FilmIcons.specs) FilmIcon(entry.id,Modifier.size(64.dp))
                                    else if(thumb!=null) Image(thumb!!.asImageBitmap(),null,Modifier.fillMaxSize().padding(2.dp),contentScale=ContentScale.Crop)
                                    else Text(if(entry.id=="identity") "원본" else "LUT")
                                    if(recipe.lutId==entry.id) Symbol(R.drawable.pixel_check,"선택됨",Modifier.align(Alignment.TopEnd))
                                }
                                Text(entry.name,Modifier.padding(top=4.dp),maxLines=2,style=MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Column(Modifier.bringIntoViewRequester(strengthTarget)) {
                    Control("강도",recipe.strength,0f..1f,1f,valueLabel="${(recipe.strength*100).toInt()}%") {change(recipe.copy(strength=it))}
                }
                TextButton(onClick={details=!details}) { Symbol(R.drawable.pixel_tune);Spacer(Modifier.width(8.dp));Text(if(details) "세부 조정 접기" else "세부 조정") }
                if(details) {
                    ScrollableTabRow(selectedTabIndex=tab,edgePadding=0.dp) { listOf("기본","톤커브","HSL","효과","왜곡").forEachIndexed { i,label -> Tab(tab==i,{tab=i},text={Text(label)}) } }
                    ColorControls(tab,recipe) { change(it) }
                }
                TextButton(onClick={pick.launch(arrayOf("*/*"))}) { Text("사용자 .cube 가져오기 (2~65)") }
                TextButton(onClick={change(Recipe(inputTransform=recipe.inputTransform,distortion=recipe.distortion,version=if(recipe.distortion==0f) 2 else 3))}) { Text("색 보정 초기화 · 입력 변환·왜곡 유지") }
                TextButton(enabled=!baking && entries.any { it.id==recipe.lutId },onClick={bakeSnapshot=recipe;exportCube.launch("beta7compact-recipe.cube")}) { Text(if(baking) "LUT 굽는 중…" else "현재 색을 33³ .cube로 내보내기") }
                Text("구운 LUT는 색 보정 초기화 후 선택하세요. 입력 변환은 유지하며 왜곡은 .cube에 포함되지 않습니다.",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={tabletopOverride=!tabletop}) { Text(if(tabletop) "테이블탑 해제" else "테이블탑으로 보기") }
                error?.let { ErrorBanner(it,"다시 시도",::recover) {error=null} }
                results.forEach { result -> TextButton(onClick={try { openPhoto(app,result.uri) } catch(e: Exception) {error="결과 사진을 열 수 없습니다"}}) {Text("저장한 보정 사진 열기")} }
            }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val detected=rememberTabletop(maxWidth.value)
        val tabletop=tabletopOverride ?: detected
        val layout=paneLayout(appWindowWidth ?: maxWidth.value,appWindowHeight ?: maxHeight.value,tabletop)
        val density=LocalDensity.current
        val fontScale=density.fontScale
        val tabletopBoundary=maxHeight/2-with(density) {headerHeightPx.toDp()}
        Surface(Modifier.fillMaxSize().graphicsLayer {scaleX=1f-backProgress*.05f;scaleY=scaleX;translationX=backProgress*24.dp.toPx()}) {
            Scaffold(contentWindowInsets=WindowInsets(0,0,0,0),topBar={if(layout!=PaneLayout.SIDE_BY_SIDE) Header()},bottomBar={SaveActions()}) {padding ->
                BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                    if(layout==PaneLayout.SIDE_BY_SIDE) {
                        Row(Modifier.fillMaxSize().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                Header()
                                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center) {
                                    Preview(photoBox(maxWidth.value,maxHeight.value))
                                }
                            }
                            Panel(Modifier.width(352.dp).fillMaxHeight().padding(vertical=8.dp),tabletop)
                        }
                    } else {
                        val gap=8.dp
                        val bodyHeight=(maxHeight-gap).value.coerceAtLeast(0f)
                        val previewHeight=if(layout==PaneLayout.TABLETOP) {
                            // Retain the original viewport midpoint while reserving scrollable controls.
                            tabletopBoundary.value.coerceIn(0f,(bodyHeight-minOf(160f*fontScale,bodyHeight*.6f)).coerceAtLeast(0f)).dp
                        } else editorPreviewHeight(maxWidth.value,bodyHeight,fontScale=fontScale).dp
                        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(gap)) {
                            BoxWithConstraints(Modifier.fillMaxWidth().height(previewHeight),contentAlignment=Alignment.Center) {
                                Preview(photoBox(maxWidth.value,maxHeight.value))
                            }
                            Panel(Modifier.fillMaxWidth().weight(1f).padding(horizontal=8.dp),tabletop)
                        }
                    }
                }
            }
        }
    }
}

@Composable fun AutoRecipeSetting(app: App, recipes: () -> Unit = {}) {
    var enabled by remember { mutableStateOf(app.settings.getBoolean("autoRecipe",false)) }
    var error by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment=Alignment.CenterVertically) {
        Text("가져오기 후 기본 레시피 자동 적용",Modifier.weight(1f))
        Switch(enabled,onCheckedChange={next ->
            if(next && app.settings.getString("defaultRecipe",null)==null) error="레시피 탭에서 기본 레시피를 먼저 지정하세요"
            else {enabled=next;app.settings.edit().putBoolean("autoRecipe",next).apply();error=null}
        })
    }
    Text("원본 보존 · 별도 JPEG 저장",color=MaterialTheme.colorScheme.onSurfaceVariant)
    error?.let {ErrorBanner(it,"레시피 관리",recipes) {error=null}}
}
