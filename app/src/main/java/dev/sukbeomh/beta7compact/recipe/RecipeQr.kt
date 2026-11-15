package dev.sukbeomh.beta7compact.recipe

import kotlin.math.abs
import kotlin.math.max

/** Independently written QR Model 2 byte encoder: L, mask 0, versions 1/20/40.
 * Only ASCII share codes; no scanner, dependencies, copied encoder, or network service.
 * Bit layout/BCH/RS are ISO/IEC 18004 conventions. Quiet zone is drawn by the UI.
 */
object RecipeQr {
    fun encode(text: String, version: Int = when { text.length<=17->1; text.length<=858->20;else->40 }): Array<BooleanArray> {
        require(text.all { it.code in 32..126 }) { "QR은 ASCII 공유 코드만 지원합니다" }
        val dataCount: Int;val blockSizes: List<Int>;val ec: Int;val align: IntArray
        when(version) {
            1 -> { dataCount=19;blockSizes=listOf(19);ec=7;align=intArrayOf() }
            20 -> { dataCount=861;blockSizes=List(3){107}+List(5){108};ec=28;align=intArrayOf(6,34,62,90) }
            40 -> { dataCount=2956;blockSizes=List(19){118}+List(6){119};ec=30;align=intArrayOf(6,30,58,86,114,142,170) }
            else -> error("지원하지 않는 QR 버전입니다")
        }
        val bits=ArrayList<Boolean>(dataCount*8)
        fun add(value: Int,n: Int) { for(i in n-1 downTo 0) bits.add((value ushr i and 1)!=0) }
        add(4,4);add(text.length,if(version<10) 8 else 16);text.forEach { add(it.code,8) }
        require(bits.size<=dataCount*8) { "QR 용량을 초과했습니다 · 텍스트 코드를 공유하세요" }
        repeat(minOf(4,dataCount*8-bits.size)) { bits.add(false) }
        while(bits.size%8!=0) bits.add(false)
        var pad=0
        while(bits.size<dataCount*8) { add(if(pad++%2==0) 0xec else 0x11,8) }
        val data=IntArray(dataCount) { i -> (0..7).fold(0) { v,b -> (v shl 1) or if(bits[i*8+b]) 1 else 0 } }
        var offset=0
        val blocks=blockSizes.map { size -> data.copyOfRange(offset,offset+size).also { offset+=size } }
        val parity=blocks.map { rs(it,ec) }
        val stream=ArrayList<Int>()
        for(i in 0 until blockSizes.max()) for(block in blocks) if(i<block.size) stream.add(block[i])
        for(i in 0 until ec) for(block in parity) stream.add(block[i])
        val side=version*4+17;val matrix=Array(side) { BooleanArray(side) };val reserved=Array(side) { BooleanArray(side) }
        fun put(x: Int,y: Int,dark: Boolean) { if(x in 0 until side && y in 0 until side) { matrix[y][x]=dark;reserved[y][x]=true } }
        for(i in 0 until side) { put(6,i,i%2==0);put(i,6,i%2==0) }
        for((cx,cy) in listOf(3 to 3,side-4 to 3,3 to side-4)) for(dy in -4..4) for(dx in -4..4) {
            val distance=max(abs(dx),abs(dy));put(cx+dx,cy+dy,distance!=2 && distance!=4)
        }
        for(ay in align.indices) for(ax in align.indices) {
            if((ax==0 && ay==0) || (ax==0 && ay==align.lastIndex) || (ax==align.lastIndex && ay==0)) continue
            for(dy in -2..2) for(dx in -2..2) put(align[ax]+dx,align[ay]+dy,max(abs(dx),abs(dy))!=1)
        }
        fun bch(value: Int,degree: Int,poly: Int): Int {
            var remainder=value shl degree
            for(i in 30 downTo degree) if(remainder ushr i and 1!=0) remainder=remainder xor (poly shl (i-degree))
            return (value shl degree) or remainder
        }
        val format=bch(8,10,0x537) xor 0x5412 // L=01, mask=000
        fun bit(value: Int,i: Int)=value ushr i and 1!=0
        for(i in 0..5) put(8,i,bit(format,i))
        put(8,7,bit(format,6));put(8,8,bit(format,7));put(7,8,bit(format,8))
        for(i in 9..14) put(14-i,8,bit(format,i))
        for(i in 0..7) put(side-1-i,8,bit(format,i))
        for(i in 8..14) put(8,side-15+i,bit(format,i))
        put(8,side-8,true)
        if(version>=7) {
            val v=bch(version,12,0x1f25)
            for(i in 0..17) { val a=side-11+i%3;val b=i/3;put(a,b,bit(v,i));put(b,a,bit(v,i)) }
        }
        var index=0;var right=side-1;var upward=true
        while(right>=1) {
            if(right==6) right--
            for(row in 0 until side) {
                val y=if(upward) side-1-row else row
                for(x in right downTo right-1) if(!reserved[y][x]) {
                    val dark=if(index<stream.size*8) bit(stream[index/8],7-index%8) else false
                    matrix[y][x]=dark xor ((x+y)%2==0);index++
                }
            }
            upward=!upward;right-=2
        }
        check(index>=stream.size*8)
        return matrix
    }
    private fun multiply(a: Int,b: Int): Int {
        var x=a;var y=b;var result=0
        repeat(8) { if(y and 1!=0) result=result xor x;y=y ushr 1;x=x shl 1;if(x and 256!=0) x=x xor 0x11d }
        return result
    }
    private fun rs(data: IntArray,count: Int): IntArray {
        var polynomial=intArrayOf(1);var alpha=1
        repeat(count) {
            val next=IntArray(polynomial.size+1)
            for(i in polynomial.indices) { next[i]=next[i] xor polynomial[i];next[i+1]=next[i+1] xor multiply(polynomial[i],alpha) }
            polynomial=next;alpha=multiply(alpha,2)
        }
        val buffer=IntArray(data.size+count);data.copyInto(buffer)
        for(i in data.indices) { val factor=buffer[i];for(j in polynomial.indices) buffer[i+j]=buffer[i+j] xor multiply(factor,polynomial[j]) }
        return buffer.copyOfRange(data.size,buffer.size)
    }
}
