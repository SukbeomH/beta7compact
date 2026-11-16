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
    var shareError by remember { mutableStateOf<String?>(null) }
    val code=remember(value) { runCatching { RecipeShare.encode(value) } }
    val matrix=remember(code) { code.mapCatching { RecipeQr.encode(it) } }
    AlertDialog(onDismissRequest=close,title={Row { dev.sukbeomh.beta7compact.ui.components.Symbol(dev.sukbeomh.beta7compact.R.drawable.symbol_qr_code_2);Text("레시피 공유") }},text={
        Column(Modifier.verticalScroll(rememberScrollState())) {
            shareError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
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
        try { app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,code.getOrThrow()),"레시피 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch(e: Exception) { shareError="텍스트 공유 앱을 선택할 수 없습니다. 코드를 복사해 전달하세요" }
    }) { Text("텍스트 공유") }},dismissButton={TextButton(onClick=close) { Text("닫기") }})
}
