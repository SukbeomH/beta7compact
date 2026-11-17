package dev.sukbeomh.beta7compact.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import dev.sukbeomh.beta7compact.R
import dev.sukbeomh.beta7compact.ui.components.SymbolButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.unit.dp
import dev.sukbeomh.beta7compact.recipe.*
import kotlin.math.*

@Composable
internal fun Control(label: String,value: Float,range: ClosedFloatingPointRange<Float>,reset: Float=0f,valueLabel: String="%.2f".format(value),change: (Float)->Unit) {
    val latestChange by rememberUpdatedState(change)
    val resetGesture=Modifier.pointerInput(reset) {
        // Observe the initial pass without consuming events, so Slider keeps its drag/tap handling.
        awaitPointerEventScope {
            var lastUp=0L;var lastPosition=Offset.Zero;var downTime=0L;var downPosition=Offset.Zero;var moved=false
            while(true) {
                val event=awaitPointerEvent(PointerEventPass.Initial)
                if(event.changes.size!=1) { moved=true;lastUp=0L;continue }
                val p=event.changes.first()
                if(p.pressed && !p.previousPressed) { downTime=p.uptimeMillis;downPosition=p.position;moved=false }
                if((p.position-downPosition).getDistance()>viewConfiguration.touchSlop) moved=true
                if(!p.pressed && p.previousPressed) {
                    if(!moved && p.uptimeMillis-downTime<300L) {
                        if(lastUp!=0L && downTime-lastUp in 0L..300L && (p.position-lastPosition).getDistance()<viewConfiguration.touchSlop*2) {
                            latestChange(reset);lastUp=0L
                        } else { lastUp=p.uptimeMillis;lastPosition=p.position }
                    } else lastUp=0L
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().then(resetGesture),verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
        Text(valueLabel,style=MaterialTheme.typography.labelLarge)
        SymbolButton(R.drawable.pixel_reset,"$label 초기화") { change(reset) }
    }
    val tick=MaterialTheme.colorScheme.onSurfaceVariant
    Slider(value,onValueChange=change,valueRange=range,modifier=resetGesture.heightIn(min=48.dp).drawBehind {
        val x=8.dp.toPx()+(size.width-16.dp.toPx())*((reset-range.start)/(range.endInclusive-range.start))
        drawLine(tick,Offset(x,size.height/2-7.dp.toPx()),Offset(x,size.height/2+7.dp.toPx()),1.dp.toPx())
    })
}

@Composable
internal fun ColorControls(tab: Int,recipe: Recipe,change: (Recipe)->Unit) {
    val e=recipe.edits
    fun edit(next: ColorEdits) = change(recipe.copy(version=2,edits=next))
    when(tab) {
        0 -> {
            Row { Text("S-Log3/S-Gamut3.Cine JPEG",Modifier.weight(1f));Switch(recipe.inputTransform!=InputTransform.NONE,{change(recipe.copy(inputTransform=if(it) InputTransform.SLOG3_SGAMUT3_CINE else InputTransform.NONE))}) }
            Control("WB 온도",recipe.wb.temperature,-1f..1f) { change(recipe.copy(wb=recipe.wb.copy(temperature=it))) }
            Control("WB 틴트",recipe.wb.tint,-1f..1f) { change(recipe.copy(wb=recipe.wb.copy(tint=it))) }
            Control("노출 EV",recipe.ev,-5f..5f) { change(recipe.copy(ev=it)) }
            Control("하이라이트",e.highlights,-100f..100f) { edit(e.copy(highlights=it)) }
            Control("섀도",e.shadows,-100f..100f) { edit(e.copy(shadows=it)) }
            Control("대비",e.contrast,-100f..100f) { edit(e.copy(contrast=it)) }
            Control("채도",e.saturation,-100f..100f) { edit(e.copy(saturation=it)) }
            Control("바이브런스",e.vibrance,-100f..100f) { edit(e.copy(vibrance=it)) }
        }
        1 -> CurveEditor(e.curves) { edit(e.copy(curves=it)) }
        2 -> {
            var band by rememberSaveable { mutableIntStateOf(0) }
            val names=listOf("빨강","주황","노랑","초록","청록","파랑","보라","자홍")
            for(row in 0..1) Row { for(i in row*4 until row*4+4) FilterChip(band==i,{band=i},label={Text(names[i])}) }
            val h=e.hsl[band]
            fun update(next: HslBand) = edit(e.copy(hsl=e.hsl.toMutableList().also { it[band]=next }))
            Control("색조",h.hue,-100f..100f) { update(h.copy(hue=it)) }
            Control("채도",h.saturation,-100f..100f) { update(h.copy(saturation=it)) }
            Control("휘도",h.luminance,-100f..100f) { update(h.copy(luminance=it)) }
        }
        3 -> {
            FoldableGroup("그레인") {
            Control("그레인 크기 px",recipe.grain.size,1f..32f,1f) { change(recipe.copy(grain=recipe.grain.copy(size=it))) }
            Control("그레인 강도",recipe.grain.amount,0f..1f) { change(recipe.copy(grain=recipe.grain.copy(amount=it))) }
            TextButton(onClick={change(recipe.copy(grain=recipe.grain.copy(seed=recipe.grain.seed+1)))}) { Text("그레인 시드 ${recipe.grain.seed} · 변경") }
            }
            FoldableGroup("비네트") {
                Control("비네트",recipe.vignette,0f..1f) { change(recipe.copy(vignette=it)) }
            }
            FoldableGroup("스플릿 톤") {
            val t=e.split
            Control("섀도 색조",t.shadowHue,0f..360f) { edit(e.copy(split=t.copy(shadowHue=it))) }
            Control("섀도 채도",t.shadowSaturation,0f..100f) { edit(e.copy(split=t.copy(shadowSaturation=it))) }
            Control("하이라이트 색조",t.highlightHue,0f..360f) { edit(e.copy(split=t.copy(highlightHue=it))) }
            Control("하이라이트 채도",t.highlightSaturation,0f..100f) { edit(e.copy(split=t.copy(highlightSaturation=it))) }
            Control("스플릿 톤 균형",t.balance,-100f..100f) { edit(e.copy(split=t.copy(balance=it))) }
            }
        }
    }
    Text("슬라이더 또는 이름을 두 번 누르면 초기화")
}

@Composable
private fun FoldableGroup(title: String, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick={open=!open},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("$title · ${if(open) "접기" else "펼치기"}")}
    if(open) content()
}

@Composable
private fun CurveEditor(curves: Curves,change: (Curves)->Unit) {
    var channel by rememberSaveable { mutableIntStateOf(0) };var cursor by rememberSaveable(channel) { mutableFloatStateOf(.5f) }
    val curve=curves.list()[channel]
    val latest by rememberUpdatedState(curve);val onChange by rememberUpdatedState(change)
    val latestCurves by rememberUpdatedState(curves);val latestCursor by rememberUpdatedState(cursor)
    fun update(points: List<CurvePoint>) {
        val c=ToneCurve(points)
        onChange(when(channel) {0->latestCurves.copy(rgb=c);1->latestCurves.copy(r=c);2->latestCurves.copy(g=c);else->latestCurves.copy(b=c)})
    }
    val active=CurveTouch.pointAt(curve.points,cursor)
    Row { listOf("RGB","R","G","B").forEachIndexed { i,n -> FilterChip(channel==i,{channel=i},label={Text(n)}) } }
    val accent=MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(200.dp)
        .pointerInput(channel) {
            // The slider picks where on the curve to work; sliding up or down anywhere in the graph moves that spot.
            awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=false)
                var points=latest.points;var index=-1;var y=latest.sample(latestCursor);var last=down.position.y
                drag(down.id) { event ->
                    event.consume()
                    y=(y-(event.position.y-last)/size.height).coerceIn(0f,1f);last=event.position.y
                    if(index<0) CurveTouch.place(points,latestCursor,y)?.let { (placed,i) -> points=placed;index=i }
                    if(index>=0) { points=points.toMutableList().also { it[index]=CurvePoint(it[index].x,y) };update(points) }
                }
            }
        }) {
        drawRect(Color.DarkGray)
        for(i in 0..4) { val t=i/4f;drawLine(Color.Gray,Offset(size.width*t,0f),Offset(size.width*t,size.height));drawLine(Color.Gray,Offset(0f,size.height*t),Offset(size.width,size.height*t)) }
        drawLine(accent,Offset(cursor*size.width,0f),Offset(cursor*size.width,size.height),2.dp.toPx())
        val path=Path()
        for(i in 0..256) { val x=i/256f;val y=curve.sample(x);if(i==0) path.moveTo(x*size.width,(1-y)*size.height) else path.lineTo(x*size.width,(1-y)*size.height) }
        drawPath(path,Color.White,style=Stroke(3f))
        curve.points.forEachIndexed { i,p -> drawCircle(if(i==active) Color.Yellow else Color.White,if(i==active) 6.dp.toPx() else 4.dp.toPx(),Offset(p.x*size.width,(1-p.y)*size.height)) }
        if(active==null) drawCircle(accent,4.dp.toPx(),Offset(cursor*size.width,(1-curve.sample(cursor))*size.height),style=Stroke(2.dp.toPx()))
    }
    Slider(cursor,onValueChange={ cursor=CurveTouch.snap(latest.points,it) },valueRange=0f..1f,modifier=Modifier.heightIn(min=48.dp))
    Text("위치 ${(cursor*100).roundToInt()}% · 밝기 ${(curve.sample(cursor)*100).roundToInt()}% · 점 ${curve.points.size}/16")
    Text("슬라이더로 위치를 고르고 그래프를 위아래로 밀어 조절",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Row {
        TextButton(enabled=active!=null && active in 1 until curve.points.lastIndex,onClick={ active?.let { a -> update(curve.points.filterIndexed { i,_->i!=a }) } }) { Text("이 점 삭제") }
        TextButton(onClick={update(ToneCurve().points)}) { Text("채널 초기화") }
    }
}
