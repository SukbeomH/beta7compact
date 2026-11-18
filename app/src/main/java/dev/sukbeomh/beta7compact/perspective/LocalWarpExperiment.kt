package dev.sukbeomh.beta7compact.perspective

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.sukbeomh.beta7compact.App
import dev.sukbeomh.beta7compact.ui.openPhoto
import kotlinx.coroutines.*
import kotlin.math.min

@Composable fun LocalWarpExperimentSetting(app: App) {
    var opened by rememberSaveable { mutableStateOf(false) }
    var saving by remember {mutableStateOf(false)}
    TextButton(onClick = {opened = true}) {Text("수동 국소 보정 · 실험")}
    if (opened) Dialog(onDismissRequest = {if(!saving) opened=false}, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        LocalWarpExperiment(app, saving, {saving=it}) {opened = false}
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun LocalWarpExperiment(app: App, saving: Boolean, onSavingChange: (Boolean) -> Unit, close: () -> Unit) {
    var uriText by rememberSaveable { mutableStateOf<String?>(null) }
    var cx by rememberSaveable {mutableFloatStateOf(.5f)}
    var cy by rememberSaveable {mutableFloatStateOf(.5f)}
    var radius by rememberSaveable {mutableFloatStateOf(.25f)}
    var strength by rememberSaveable {mutableFloatStateOf(0f)}
    var original by rememberSaveable {mutableStateOf(false)}
    var overlay by rememberSaveable {mutableStateOf(true)}
    var loadAttempt by remember {mutableIntStateOf(0)}
    var snapshotFile by remember {mutableStateOf<java.io.File?>(null)}
    DisposableEffect(Unit) {onDispose {snapshotFile?.let {runCatching {it.delete()}}}}
    var input by remember {mutableStateOf<Bitmap?>(null)}
    var rendered by remember {mutableStateOf<Bitmap?>(null)}
    var renderedConfig by remember {mutableStateOf<LocalWarpConfig?>(null)}
    var loading by remember {mutableStateOf(false)}
    var rendering by remember {mutableStateOf(false)}
    var confirm by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    var previewError by remember {mutableStateOf(false)}
    var output by remember {mutableStateOf<Uri?>(null)}
    var saveJob by remember {mutableStateOf<Job?>(null)}
    val scope = rememberCoroutineScope()
    val config = LocalWarpConfig(cx, cy, radius, strength)
    fun reset() {cx=.5f;cy=.5f;radius=.25f;strength=0f;original=false}
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->
        if (uri != null) {reset(); input=null; rendered=null; renderedConfig=null; output=null; message=null; uriText=uri.toString(); loadAttempt++}
    }
    BackHandler {if (!saving) close()}
    LaunchedEffect(uriText,loadAttempt) {
        val uri = uriText?.let(Uri::parse) ?: return@LaunchedEffect
        loading=true; input=null; rendered=null; renderedConfig=null; previewError=false
        snapshotFile?.let {runCatching {it.delete()}};snapshotFile=null
        var pending: java.io.File?=null
        var handedOff=false
        try {
            val file=LocalWarpPhotoIO.snapshot(app,uri)
            pending=file
            val loaded=LocalWarpPhotoIO.load(app,Uri.fromFile(file))
            snapshotFile=file;input=loaded;handedOff=true
        }
        catch (e: CancellationException) {throw e}
        catch (_: OutOfMemoryError) {previewError=true;message="이미지를 읽을 메모리가 부족합니다. 더 작은 JPEG를 선택하세요."}
        catch (_: Exception) {previewError=true;message="JPEG를 읽을 수 없습니다. 다시 선택하세요."}
        finally {if(!handedOff) pending?.let {runCatching {it.delete()}};loading=false}
    }
    LaunchedEffect(input,config) {
        val source = input ?: return@LaunchedEffect
        rendering=true; previewError=false
        try {
            delay(100)
            val result=withContext(Dispatchers.Default) {
                val execution=currentCoroutineContext()
                val pixels=IntArray(source.width*source.height)
                source.getPixels(pixels,0,source.width,0,0,source.width,source.height)
                val warped=LocalWarp.render(pixels,source.width,source.height,config) {execution.ensureActive()}
                execution.ensureActive()
                Bitmap.createBitmap(warped,source.width,source.height,Bitmap.Config.ARGB_8888)
            }
            rendered=result;renderedConfig=config
        } catch (e: CancellationException) {throw e}
        catch (_: OutOfMemoryError) {rendered=null;renderedConfig=null;previewError=true;message="미리보기 메모리가 부족합니다. 더 작은 JPEG를 선택하세요."}
        catch (_: Exception) {rendered=null;renderedConfig=null;previewError=true;message="미리보기를 만들지 못했습니다. 초기화하거나 JPEG를 다시 선택하세요."}
        finally {rendering=false}
    }
    val ready=snapshotFile!=null && input!=null && rendered!=null && renderedConfig==config && !loading && !rendering && !previewError
    @Composable fun Preview(modifier: Modifier) {
        BoxWithConstraints(modifier.background(Color.Black),contentAlignment=Alignment.Center) {
            val source=input
            if (source==null) Text(if(loading) "JPEG 읽는 중…" else "JPEG를 선택하세요",color=Color.White)
            else {
                val aspect=source.width.toFloat()/source.height
                val w=min(maxWidth.value,maxHeight.value*aspect).coerceAtLeast(0f)
                val h=w/aspect
                Box(Modifier.size(w.dp,h.dp).pointerInput(source,saving) {
                    detectTapGestures {point ->
                        if(!saving && size.width>0 && size.height>0) {cx=(point.x/size.width).coerceIn(0f,1f);cy=(point.y/size.height).coerceIn(0f,1f)}
                    }
                }) {
                    val image=if(original || renderedConfig!=config) source else rendered ?: source
                    Image(image.asImageBitmap(),if(original) "원본 JPEG" else if(ready) "국소 보정 미리보기" else "보정 계산 중 · 원본 표시",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                    if(overlay) Canvas(Modifier.fillMaxSize()) {
                        val center=Offset(cx*size.width,cy*size.height)
                        val r=radius*min(size.width,size.height)
                        drawCircle(Color.Black,r,center,style=Stroke(4.dp.toPx()))
                        drawCircle(Color.White,r,center,style=Stroke(2.dp.toPx()))
                    }
                }
            }
            if(loading || rendering) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    @Composable fun Controls(modifier: Modifier) {
        Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("인물 형태 보정 · 품질 평가 전",style=MaterialTheme.typography.titleMedium)
            Text("수동 2D 국소 변형 실험입니다. 얼굴 3D 복원이나 렌즈 재현이 아닙니다. 얼굴 형태·배경 직선이 부자연스러워질 수 있습니다.",style=MaterialTheme.typography.bodySmall)
            input?.let {Text("미리보기 ${it.width} × ${it.height} · 사진 위를 눌러 영역 중심 지정",style=MaterialTheme.typography.bodySmall)}
            Text("강도 ${(strength*100).toInt()}%")
            Slider(strength,{strength=it},enabled=input!=null && !saving)
            Text("영역 반경 ${(radius*100).toInt()}% · 사진의 짧은 변 기준")
            Slider(radius,{radius=it},valueRange=.05f.. .5f,enabled=input!=null && !saving)
            Text("중심 가로 ${(cx*100).toInt()}%")
            Slider(cx,{cx=it},enabled=input!=null && !saving)
            Text("중심 세로 ${(cy*100).toInt()}%")
            Slider(cy,{cy=it},enabled=input!=null && !saving)
            Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(overlay,{overlay=it});Text("보정 영역 표시")}
            TextButton(onClick={reset()},enabled=!saving) {Text("초기화 · 강도 0")}
            Text("입력 JPEG는 미리보기와 저장 일치를 위해 앱 전용 임시 파일로 복사합니다(입력당 최대 64MiB). 화면을 닫으면 정리합니다.\n실험 JPEG: 긴 변 최대 2048px · sRGB · JPEG 재압축 · 메타데이터(촬영 정보·위치) 제외. 원본과 기존 레시피는 유지됩니다. 강도 0도 저장하면 재압축됩니다.",style=MaterialTheme.typography.bodySmall)
            message?.let {Text(it)}
            output?.let {uri -> TextButton(onClick={try {openPhoto(app,uri.toString())} catch(_:Exception) {message="저장한 실험 JPEG를 열 수 없습니다."}}) {Text("저장한 실험 JPEG 열기")}}
            Text("평가: 원본과 결과의 얼굴 닮음·눈/입/턱선·배경 직선·영역 경계를 비교하세요. 실제 인물 품질 평가는 아직 수행하지 않았습니다.",style=MaterialTheme.typography.bodySmall)
        }
    }
    Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Scaffold(contentWindowInsets=WindowInsets(0,0,0,0),topBar={
            Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick=close,enabled=!saving) {Text("닫기")}
                Text("수동 국소 보정 실험",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
            }
        },bottomBar={
            FlowRow(Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,top=8.dp,bottom=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.End)) {
                TextButton(onClick={pick.launch(arrayOf("image/jpeg"))},enabled=!saving && !loading) {Text("JPEG 선택")}
                TextButton(onClick={original=!original},enabled=input!=null) {Text(if(original) "보정 보기" else "원본 보기")}
                if(saving) TextButton(onClick={saveJob?.cancel()}) {Text("저장 취소")}
                Button(onClick={confirm=true},enabled=ready && !saving) {Text(if(saving) "저장 중…" else "실험 JPEG 저장")}
            }
        }) {padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val stackedPreviewHeight=maxHeight*.4f
                if(maxWidth>=700.dp && maxWidth>maxHeight) Row(Modifier.fillMaxSize()) {
                    Preview(Modifier.weight(1f).fillMaxHeight())
                    Controls(Modifier.width(352.dp).fillMaxHeight())
                } else Column(Modifier.fillMaxSize()) {
                    Preview(Modifier.fillMaxWidth().height(stackedPreviewHeight))
                    Controls(Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("실험 JPEG 저장")},text={Text("현재 영역과 강도로 별도 JPEG를 저장합니다. 긴 변 최대 2048px이며 메타데이터는 제외됩니다. 기존 사진 저장 결과에는 연결되지 않습니다.")},
        confirmButton={TextButton(onClick={
            confirm=false
            val uri=snapshotFile?.let(Uri::fromFile)
            if(uri!=null && ready && !saving) {
                val snapshot=config
                onSavingChange(true);message=null;output=null
                saveJob=scope.launch {
                    try {output=LocalWarpPhotoIO.save(app,uri,snapshot);message="실험 JPEG 저장 완료 · Pictures/beta7compact/Experiments/"}
                    catch(e:CancellationException) {message="저장을 취소했습니다.";throw e}
                    catch(_:OutOfMemoryError) {message="저장 메모리가 부족합니다. 더 작은 JPEG를 선택하세요."}
                    catch(_:Exception) {message="실험 JPEG 저장에 실패했습니다."}
                    finally {onSavingChange(false);saveJob=null}
                }
            }
        }) {Text("별도 JPEG 저장")}},dismissButton={TextButton(onClick={confirm=false}) {Text("취소")}})
}
