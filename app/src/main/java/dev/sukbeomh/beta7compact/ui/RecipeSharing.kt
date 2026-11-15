package dev.sukbeomh.beta7compact.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import dev.sukbeomh.beta7compact.App
import dev.sukbeomh.beta7compact.library.SavedRecipe
import dev.sukbeomh.beta7compact.recipe.*
import kotlinx.coroutines.*
import java.util.UUID

internal suspend fun saveNewRecipe(app: App,name: String,recipe: Recipe): String = app.db.withTransaction {
    val unique=RecipeShare.uniqueName(name,app.db.recipes().names())
    app.db.recipes().save(SavedRecipe(UUID.randomUUID().toString(),unique,recipe.toJson()));unique
}

@Composable
fun RecipeImportDialog(app: App,initial: String = "",close: ()->Unit,onApplied: ((Recipe,String)->Unit)? = null) {
    val scope=rememberCoroutineScope()
    var code by remember { mutableStateOf(initial.take(RecipeShare.MAX_CODE_CHARS)) }
    var error by remember { mutableStateOf(if(initial.length>RecipeShare.MAX_CODE_CHARS) "공유 코드가 너무 큽니다" else null) }
    var incoming by remember { mutableStateOf<SharedRecipe?>(null) }
    var missing by remember { mutableStateOf(false) };var busy by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest=close,title={Text("레시피 코드 가져오기")},text={
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("카메라 앱으로 QR을 읽은 텍스트도 붙여넣을 수 있습니다.")
            OutlinedTextField(code,{ if(it.length<=RecipeShare.MAX_CODE_CHARS) { code=it;incoming=null;error=null } else error="공유 코드가 너무 큽니다" },label={Text("b7c1: 코드")},maxLines=5,enabled=!busy)
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            incoming?.let { value ->
                Text(value.name)
                if(value.recipe.lutId.startsWith("user-")) Text("LUT 필요(${value.lutName}) · LUT 데이터는 포함되지 않습니다")
                if(missing) Text("이 기기에 LUT가 없습니다. 레시피를 보관한 뒤 편집기에서 해당 LUT를 선택하세요. 적용은 보류됩니다.")
            }
        }
    },confirmButton={
        TextButton(enabled=!busy,onClick={
            if(incoming==null) {
                busy=true;scope.launch {
                    try {
                        val parsed=withContext(Dispatchers.Default) { RecipeShare.decode(code) }
                        missing=withContext(Dispatchers.IO) { RecipeStore(app).entries().none { it.id==parsed.recipe.lutId } }
                        incoming=parsed;error=null
                    } catch(e: CancellationException) { throw e } catch(e: Exception) { error=e.message ?: "공유 코드를 읽을 수 없습니다" }
                    finally { busy=false }
                }
            } else {
                val value=checkNotNull(incoming);busy=true
                scope.launch {
                    try {
                        val stored=if(value.recipe.lutId.startsWith("user-")) value.recipe.copy(requiredLutName=value.lutName) else value.recipe
                        val unique=saveNewRecipe(app,value.name,stored)
                        if(!missing) onApplied?.invoke(stored,unique)
                        close()
                    } catch(e: CancellationException) { throw e } catch(e: Exception) { error="레시피를 보관할 수 없습니다" }
                    finally { busy=false }
                }
            }
        }) { Text(if(incoming==null) "검사" else if(missing || onApplied==null) "보관" else "보관 및 적용") }
    },dismissButton={TextButton(onClick=close) { Text("닫기") }})
}

@Composable
internal fun RecipeShareDialog(app: App,value: SharedRecipe,close: ()->Unit) {
    val code=remember(value) { runCatching { RecipeShare.encode(value) } }
    val matrix=remember(code) { code.mapCatching { RecipeQr.encode(it) } }
    AlertDialog(onDismissRequest=close,title={Text("레시피 공유")},text={
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if(value.recipe.lutId.startsWith("user-")) Text("LUT 필요(${value.lutName}) · LUT는 별도로 전달하세요")
            matrix.getOrNull()?.let { qr ->
                Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    drawRect(Color.White)
                    val scale=kotlin.math.floor(size.minDimension/(qr.size+8))
                    val left=(size.width-scale*(qr.size+8))/2;val top=(size.height-scale*(qr.size+8))/2
                    for(y in qr.indices) for(x in qr.indices) if(qr[y][x]) drawRect(Color.Black,Offset(left+(x+4)*scale,top+(y+4)*scale),Size(scale,scale))
                }
            } ?: Text(matrix.exceptionOrNull()?.message ?: "QR을 표시할 수 없습니다")
            code.getOrNull()?.let { SelectionContainer { Text(it,style=MaterialTheme.typography.bodySmall) } }
        }
    },confirmButton={TextButton(enabled=code.isSuccess,onClick={
        app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,code.getOrThrow()),"레시피 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }) { Text("텍스트 공유") }},dismissButton={TextButton(onClick=close) { Text("닫기") }})
}

@Composable
internal fun RecipeManager(app: App,recipe: Recipe,name: String,changeName: (String)->Unit,apply: (Recipe,String)->Unit) {
    val scope=rememberCoroutineScope();val saved by app.db.recipes().observe().collectAsStateWithLifecycle(emptyList())
    var error by remember { mutableStateOf<String?>(null) };var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) };var sharing by remember { mutableStateOf<SharedRecipe?>(null) }
    var renaming by remember { mutableStateOf<SavedRecipe?>(null) };var rename by remember { mutableStateOf("") }
    var unresolved by remember { mutableStateOf<SavedRecipe?>(null) }
    var deleting by remember { mutableStateOf<SavedRecipe?>(null) }
    fun task(block: suspend ()->Unit) { scope.launch { try { error=null;block() } catch(e: CancellationException) { throw e } catch(e: Exception) { error="레시피 작업을 완료하지 못했습니다" } } }
    OutlinedTextField(name,changeName,label={Text("레시피 이름")})
    Row {
        TextButton(onClick={task { message="${saveNewRecipe(app,name,recipe)} 보관 완료" }}) { Text("보관") }
        TextButton(onClick={importing=true}) { Text("코드 붙여넣기") }
        TextButton(onClick={task {
            val lutName=withContext(Dispatchers.IO) { RecipeStore(app).entries().firstOrNull { it.id==recipe.lutId }?.name }
            if(lutName==null) error="공유에 필요한 LUT가 없습니다" else sharing=SharedRecipe(name.ifBlank { "내 레시피" },recipe,if(recipe.lutId.startsWith("user-")) lutName else "")
        }}) { Text("공유 / QR") }
    }
    TextButton(onClick={task {
        val available=withContext(Dispatchers.IO) { RecipeStore(app).entries().any { it.id==recipe.lutId } }
        if(!available) error="LUT를 선택한 뒤 기본 레시피로 지정하세요"
        else { app.settings.edit().putString("defaultRecipe",recipe.toJson()).apply();message="기본 레시피 지정 · 자동 적용은 설정에서 켜세요" }
    }}) { Text("기본 레시피로 지정") }
    error?.let { Text(it,color=MaterialTheme.colorScheme.error) };message?.let { Text(it) }
    saved.forEach { s ->
        Column {
            Row {
                TextButton(onClick={task {
                    val r=Recipe.fromJson(s.json)
                    val available=withContext(Dispatchers.IO) { RecipeStore(app).entries().any { it.id==r.lutId } }
                    if(available) apply(r,s.name) else {
                        unresolved=s
                    }
                }},modifier=Modifier.weight(1f)) { Text(s.name) }
                TextButton(onClick={task { app.db.recipes().favorite(s.id,!s.favorite) }}) { Text(if(s.favorite) "★" else "☆") }
            }
            Row {
                TextButton(onClick={renaming=s;rename=s.name}) { Text("이름 변경") }
                TextButton(onClick={task { message="${saveNewRecipe(app,s.name,Recipe.fromJson(s.json))} 복제 완료" }}) { Text("복제") }
                TextButton(onClick={deleting=s}) { Text("삭제") }
            }
        }
    }
    unresolved?.let { savedRecipe ->
        val pending=runCatching { Recipe.fromJson(savedRecipe.json) }.getOrNull()
        AlertDialog(onDismissRequest={unresolved=null},title={Text("LUT 필요(${pending?.requiredLutName?.ifBlank { "사용자 LUT" }})")},
            text={Text("기본 탭에서 필요한 .cube를 먼저 가져오거나 선택하세요. 현재 선택한 LUT로 연결할 때만 이 레시피를 적용합니다.")},
            confirmButton={TextButton(enabled=pending!=null,onClick={task {
                val available=withContext(Dispatchers.IO) { RecipeStore(app).entries().any { it.id==recipe.lutId } }
                if(available && pending!=null) {
                    val linked=pending.copy(lutId=recipe.lutId,requiredLutName="")
                    app.db.recipes().save(savedRecipe.copy(json=linked.toJson()))
                    apply(linked,savedRecipe.name);unresolved=null
                } else error="연결할 LUT를 먼저 선택하세요"
            }}) { Text("현재 선택 LUT로 연결·적용") }},
            dismissButton={TextButton(onClick={unresolved=null}) { Text("보류") }})
    }
    if(importing) RecipeImportDialog(app,close={importing=false},onApplied=apply)
    sharing?.let { RecipeShareDialog(app,it) { sharing=null } }
    renaming?.let { s -> AlertDialog(onDismissRequest={renaming=null},title={Text("레시피 이름 변경")},text={OutlinedTextField(rename,{rename=it.take(80)})},
        confirmButton={TextButton(onClick={task { app.db.withTransaction {
            app.db.recipes().save(s.copy(name=RecipeShare.uniqueName(rename,app.db.recipes().names(s.id))))
        };renaming=null }}) { Text("변경") }},dismissButton={TextButton(onClick={renaming=null}) { Text("취소") }}) }
    deleting?.let { s -> AlertDialog(onDismissRequest={deleting=null},title={Text("${s.name} 삭제")},text={Text("보관한 레시피를 삭제합니다. 저장 결과와 원본은 유지됩니다.")},
        confirmButton={TextButton(onClick={task { app.db.recipes().delete(s.id);deleting=null }}) { Text("삭제") }},dismissButton={TextButton(onClick={deleting=null}) { Text("취소") }}) }
}
