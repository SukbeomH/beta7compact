package dev.sukbeomh.beta7compact.recipe

import java.io.File
import java.io.RandomAccessFile

/** Structural check of a JPEG's header segments, used to catch metadata that was written past the format's limits. */
object JpegFile {
    /** Longest EXIF text value copied from an original; larger values are binary blobs rendered as text. */
    const val MAX_TAG_CHARS = 2000

    /** True when every segment from the start of the file up to the image data begins where the previous one ends. */
    fun isWellFormed(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 4 || input.readUnsignedShort() != 0xFFD8) return@use false
            while (true) {
                if (input.filePointer + 4 > input.length() || input.readUnsignedByte() != 0xFF) return@use false
                val marker = input.readUnsignedByte()
                if (marker == 0xFF) { input.seek(input.filePointer - 1); continue }
                if (marker == 0xD8 || marker == 0xD9 || marker == 0x00) return@use false
                val length = input.readUnsignedShort()
                if (length < 2) return@use false
                if (marker == 0xDA) return@use true
                input.seek(input.filePointer + length - 2)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
    }.getOrDefault(false)
}
