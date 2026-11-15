package dev.sukbeomh.beta7compact.recipe

import java.io.File
import java.io.RandomAccessFile

/** Bounded TIFF IFD traversal. Extracts the largest camera JPEG; never demosaics RAW. */
object ArwPreview {
    fun extract(source: File, destination: File) {
        RandomAccessFile(source,"r").use { f ->
            require(f.length() >= 8) { "ARW 헤더가 잘못되었습니다" }
            val endian = f.readUnsignedShort(); require(endian == 0x4949 || endian == 0x4d4d)
            val little = endian == 0x4949
            fun u16(): Int { val a = f.readUnsignedByte();val b = f.readUnsignedByte();return if (little) a or (b shl 8) else (a shl 8) or b }
            fun u32(): Long { val a = u16().toLong();val b = u16().toLong();return if (little) a or (b shl 16) else (a shl 16) or b }
            require(u16() == 42) { "지원하지 않는 ARW TIFF 형식입니다" }
            val queue = ArrayDeque<Long>();queue.add(u32());val visited = mutableSetOf<Long>()
            var bestOffset = 0L;var bestSize = 0L
            fun candidate(offset: Long, size: Long) {
                if (size < 4 || offset < 8 || size > f.length()-offset || size <= bestSize) return
                f.seek(offset);if (f.readUnsignedShort() != 0xffd8) return
                f.seek(offset+size-2);if (f.readUnsignedShort() != 0xffd9) return
                bestOffset=offset;bestSize=size
            }
            while (queue.isNotEmpty()) {
                require(visited.size < 128) { "ARW 미리보기 구조가 너무 복잡합니다" }
                val offset = queue.removeFirst();if (offset == 0L || !visited.add(offset)) continue
                require(offset in 8..f.length()-2);f.seek(offset);val count = u16();require(count <= 4096 && offset+2+count*12L+4 <= f.length())
                val tags = mutableMapOf<Int,List<Long>>()
                for (i in 0 until count) {
                    f.seek(offset+2+i*12L)
                    val tag=u16();val type=u16();val n=u32();val field=f.filePointer;val value=u32()
                    if (tag !in setOf(513,514,273,279,330,34665) || type !in setOf(3,4) || n !in 1..256) continue
                    val bytes=if (type==3) 2 else 4
                    val position=if (n*bytes<=4) field else value
                    require(position >= 0 && n*bytes <= f.length()-position)
                    f.seek(position);tags[tag]=List(n.toInt()) { if (type==3) u16().toLong() else u32() }
                }
                tags[330]?.forEach { queue.add(it) };tags[34665]?.forEach { queue.add(it) }
                f.seek(offset+2+count*12L);queue.add(u32())
                candidate(tags[513]?.singleOrNull() ?: 0,tags[514]?.singleOrNull() ?: 0)
                candidate(tags[273]?.singleOrNull() ?: 0,tags[279]?.singleOrNull() ?: 0)
            }
            require(bestSize > 0) { "ARW에 지원되는 내장 JPEG가 없습니다" }
            f.seek(bestOffset);destination.outputStream().use { out ->
                val buffer=ByteArray(64*1024);var remaining=bestSize
                while(remaining>0) { val n=f.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt());check(n>0);out.write(buffer,0,n);remaining-=n }
            }
        }
    }
}
