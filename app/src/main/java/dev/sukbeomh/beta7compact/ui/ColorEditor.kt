package dev.sukbeomh.beta7compact.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
internal fun Control(label: String,value: Float,range: ClosedFloatingPointRange<Float>,reset: Float=0f,change: (Float)->Unit) {
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
    Text("$label · ${"%.2f".format(value)}",Modifier.fillMaxWidth().then(resetGesture))
    Slider(value,onValueChange=change,valueRange=range,modifier=resetGesture)
}

@Composable
internal fun ColorControls(tab: Int,recipe: Recipe,change: (Recipe)->Unit) {
    val e=recipe.edits
    fun edit(next: ColorEdits) = change(recipe.copy(version=2,edits=next))
    when(tab) {
        0 -> {
            Control("LUT 강도",recipe.strength,0f..1f,1f) { change(recipe.copy(strength=it)) }
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
            var band by remember { mutableIntStateOf(0) }
            val names=listOf("빨강","주황","노랑","초록","청록","파랑","보라","자홍")
            for(row in 0..1) Row { for(i in row*4 until row*4+4) FilterChip(band==i,{band=i},label={Text(names[i])}) }
            val h=e.hsl[band]
            fun update(next: HslBand) = edit(e.copy(hsl=e.hsl.toMutableList().also { it[band]=next }))
            Control("색조",h.hue,-100f..100f) { update(h.copy(hue=it)) }
            Control("채도",h.saturation,-100f..100f) { update(h.copy(saturation=it)) }
            Control("휘도",h.luminance,-100f..100f) { update(h.copy(luminance=it)) }
        }
        3 -> {
            Control("그레인 크기 px",recipe.grain.size,1f..32f,1f) { change(recipe.copy(grain=recipe.grain.copy(size=it))) }
            Control("그레인 강도",recipe.grain.amount,0f..1f) { change(recipe.copy(grain=recipe.grain.copy(amount=it))) }
            TextButton(onClick={change(recipe.copy(grain=recipe.grain.copy(seed=recipe.grain.seed+1)))}) { Text("그레인 시드 ${recipe.grain.seed} · 변경") }
            Control("비네트",recipe.vignette,0f..1f) { change(recipe.copy(vignette=it)) }
            val t=e.split
            Control("섀도 색조",t.shadowHue,0f..360f) { edit(e.copy(split=t.copy(shadowHue=it))) }
            Control("섀도 채도",t.shadowSaturation,0f..100f) { edit(e.copy(split=t.copy(shadowSaturation=it))) }
            Control("하이라이트 색조",t.highlightHue,0f..360f) { edit(e.copy(split=t.copy(highlightHue=it))) }
            Control("하이라이트 채도",t.highlightSaturation,0f..100f) { edit(e.copy(split=t.copy(highlightSaturation=it))) }
            Control("스플릿 톤 균형",t.balance,-100f..100f) { edit(e.copy(split=t.copy(balance=it))) }
        }
    }
    Text("슬라이더 또는 이름을 두 번 누르면 초기화")
}

@Composable
private fun CurveEditor(curves: Curves,change: (Curves)->Unit) {
    var channel by remember { mutableIntStateOf(0) };var selected by remember(channel) { mutableIntStateOf(0) }
    val curve=curves.list()[channel]
    val latest by rememberUpdatedState(curve);val onChange by rememberUpdatedState(change)
    val latestCurves by rememberUpdatedState(curves)
    fun update(points: List<CurvePoint>) {
        val c=ToneCurve(points)
        onChange(when(channel) {0->latestCurves.copy(rgb=c);1->latestCurves.copy(r=c);2->latestCurves.copy(g=c);else->latestCurves.copy(b=c)})
    }
    Row { listOf("RGB","R","G","B").forEachIndexed { i,n -> FilterChip(channel==i,{channel=i},label={Text(n)}) } }
    Canvas(Modifier.fillMaxWidth().height(240.dp)
        .pointerInput(channel) {
            detectTapGestures(onTap={ position ->
                val points=latest.points
                val x=(position.x/size.width).coerceIn(0f,1f);val y=(1-position.y/size.height).coerceIn(0f,1f)
                val closest=points.indices.minBy { hypot(points[it].x-x,points[it].y-y) }
                if(hypot(points[closest].x-x,points[closest].y-y)<.06f) selected=closest
                else if(points.size<16 && points.all { abs(it.x-x)>=.002f }) {
                    val added=(points+CurvePoint(x,y)).sortedBy { it.x };update(added);selected=added.indexOfFirst { it.x==x }
                }
            })
        }.pointerInput(channel) {
            var dragging=0
            detectDragGestures(onDragStart={ pos ->
                val x=pos.x/size.width;val y=1-pos.y/size.height
                dragging=latest.points.indices.minBy { hypot(latest.points[it].x-x,latest.points[it].y-y) };selected=dragging
            },onDrag={ event,_ ->
                event.consume();val points=latest.points.toMutableList();val i=dragging.coerceIn(0,points.lastIndex)
                val x=when(i) { 0->0f;points.lastIndex->1f;else->run {
                    val low=points[i-1].x+.001f;val high=points[i+1].x-.001f
                    if(low<=high) (event.position.x/size.width).coerceIn(low,high) else points[i].x
                } }
                points[i]=CurvePoint(x,(1-event.position.y/size.height).coerceIn(0f,1f));update(points)
            })
        }) {
        drawRect(Color.DarkGray)
        for(i in 0..4) { val t=i/4f;drawLine(Color.Gray,Offset(size.width*t,0f),Offset(size.width*t,size.height));drawLine(Color.Gray,Offset(0f,size.height*t),Offset(size.width,size.height*t)) }
        val path=Path()
        for(i in 0..256) { val x=i/256f;val y=curve.sample(x);if(i==0) path.moveTo(x*size.width,(1-y)*size.height) else path.lineTo(x*size.width,(1-y)*size.height) }
        drawPath(path,Color.White,style=Stroke(3f))
        curve.points.forEachIndexed { i,p -> drawCircle(if(i==selected) Color.Yellow else Color.White,8f,Offset(p.x*size.width,(1-p.y)*size.height)) }
    }
    Text("빈 곳 탭: 점 추가 · 드래그: 이동 · ${curve.points.size}/16점")
    Row {
        TextButton(enabled=selected in 1 until curve.points.lastIndex,onClick={update(curve.points.filterIndexed { i,_->i!=selected });selected=0}) { Text("선택 점 삭제") }
        TextButton(onClick={update(ToneCurve().points);selected=0}) { Text("채널 초기화") }
    }
}
