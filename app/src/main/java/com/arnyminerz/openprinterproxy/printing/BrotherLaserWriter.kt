// This file is a Kotlin port of parts of the brlaser printer driver
// (https://github.com/pdewacht/brlaser): src/job.cc, src/line.cc and src/block.h.
//
// Copyright 2013 Peter De Wachter
//
// brlaser is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 2 of the License, or
// (at your option) any later version.
//
// brlaser is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with brlaser.  If not, see <http://www.gnu.org/licenses/>.

package com.arnyminerz.openprinterproxy.printing

import android.print.PrintAttributes.Margins
import android.print.PrintAttributes.MediaSize
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.math.min

/**
 * Writes jobs for Brother host-based ("GDI") laser printers such as the DCP-1510, HL-1110 or DCP-7030. They report
 * `CMD:PJL,XL2HB` and `CID:Brother Laser Type1` in their IEEE 1284 device ID, and take a PCL-like stream of
 * monochrome raster lines compressed with Brother's own delta encoding.
 *
 * @param mediaSize The paper, in portrait orientation.
 * @param dpi 300 or 600. brlaser notes that 300 dpi doesn't work on every model, but it does on the DCP-1510.
 * @param economode Brother's toner save mode.
 */
class BrotherLaserWriter(
    private val mediaSize: MediaSize,
    private val dpi: Int,
    private val economode: Boolean,
    jobName: String,
    private val isCancelled: () -> Boolean,
) : PrintDataWriter {
    private companion object {
        const val ESC = "\u001B"

        // XXX brother driver uses 128 lines per band
        const val LINES_PER_BAND = 64

        const val MAX_BLOCK_SIZE = 16350
    }

    /** The job name without characters that would break the PJL command. */
    private val jobName = jobName
        .map { if (it.code < 32 || it.code >= 127 || it == '"' || it == '\\') ' ' else it }
        .joinToString("")
        .take(79)
        .ifBlank { "OpenPrinterProxy" }

    private val paper = when (mediaSize.id) {
        MediaSize.NA_LETTER.id -> "LETTER"
        MediaSize.NA_LEGAL.id -> "LEGAL"
        MediaSize.ISO_A5.id -> "A5"
        else -> "A4"
    }

    override fun write(pdf: File, out: OutputStream, onProgress: (Float) -> Unit) {
        // The raster covers the whole sheet; the printer itself leaves out what it can't print
        PdfRasterizer(pdf, mediaSize, dpi, Margins.NO_MARGINS, isCancelled).use { rasterizer ->
            beginJob(out)
            writePageHeader(out)
            for (index in 0 until rasterizer.pageCount) {
                encodePage(rasterizer, index, out)
                onProgress((index + 1f) / rasterizer.pageCount)
            }
            endJob(out)
        }
    }

    override fun resetCommands(): ByteArray = "$UEL\n".toByteArray(Charsets.US_ASCII)

    private fun beginJob(out: OutputStream) {
        out.write(ByteArray(128))
        out.writeAscii("$UEL@PJL\n")
        out.writeAscii("@PJL JOB NAME=\"$jobName\"\n")
    }

    private fun endJob(out: OutputStream) {
        out.writeAscii("$UEL@PJL\n")
        out.writeAscii("@PJL EOJ NAME=\"$jobName\"\n")
        out.writeAscii("$UEL\n")
    }

    private fun writePageHeader(out: OutputStream) {
        out.writeAscii("$UEL@PJL\n")
        out.writeAscii("@PJL SET RAS1200MODE = FALSE\n")
        out.writeAscii("@PJL SET RESOLUTION = $dpi\n")
        out.writeAscii("@PJL SET ECONOMODE = ${if (economode) "ON" else "OFF"}\n")
        out.writeAscii("@PJL SET SOURCETRAY = AUTO\n")
        out.writeAscii("@PJL SET MEDIATYPE = PLAIN\n")
        out.writeAscii("@PJL SET PAPER = $paper\n")
        out.writeAscii("@PJL SET PAGEPROTECT = AUTO\n")
        out.writeAscii("@PJL SET ORIENTATION = PORTRAIT\n")
        out.writeAscii("@PJL ENTER LANGUAGE = PCL\n")

        out.writeAscii("${ESC}E")
        // Copies are made by sending the job again, so they come out collated
        out.writeAscii("$ESC&l1X")
    }

    private fun encodePage(rasterizer: PdfRasterizer, index: Int, out: OutputStream) {
        val reference = ByteArray(rasterizer.rowBytes)
        val block = Block()
        var lineIndex = 0

        rasterizer.rasterizePage(index) { line, _ ->
            if (lineIndex == 0) {
                block.addLine(BrotherLineEncoder.encodeLine(line))
                out.writeAscii("$ESC*b1030m")
            } else {
                val encoded = if (lineIndex % LINES_PER_BAND == 0) {
                    block.flush(out)
                    BrotherLineEncoder.encodeLine(line)
                } else {
                    val delta = BrotherLineEncoder.encodeLine(line, reference)
                    if (block.lineFits(delta.size)) {
                        delta
                    } else {
                        block.flush(out)
                        BrotherLineEncoder.encodeLine(line)
                    }
                }
                block.addLine(encoded)
            }
            line.copyInto(reference)
            lineIndex++
        }

        block.flush(out)
        out.writeAscii("1030M\u000C")
    }

    /** A group of encoded lines, sent with a single length-prefixed command. */
    private class Block {
        private val lines = mutableListOf<ByteArray>()
        private var lineBytes = 0

        fun addLine(line: ByteArray) {
            check(line.isNotEmpty())
            check(lineFits(line.size))
            lineBytes += line.size
            lines += line
        }

        fun lineFits(size: Int) = lineBytes + size < MAX_BLOCK_SIZE

        fun flush(out: OutputStream) {
            if (lineBytes == 0) return
            out.writeAscii("${lineBytes + 2}w")
            out.write(0)
            out.write(lines.size)
            for (line in lines) out.write(line)
            lineBytes = 0
            lines.clear()
        }
    }
}

/** Brother's line compression: each raster line is sent as edits (substitutes and repeats) to the previous one. */
internal object BrotherLineEncoder {
    private const val MAX_EDITS = 254

    private fun writeOverflow(value: Int, out: ByteArrayOutputStream) {
        if (value < 0) return
        if (value < 255) {
            out.write(value)
        } else {
            repeat(value / 255) { out.write(255) }
            out.write(value % 255)
        }
    }

    /** Replaces the bytes at [offset] (relative to the previous edit) with `line[first until last]`. */
    private fun writeSubstitute(offset: Int, line: ByteArray, first: Int, last: Int, out: ByteArrayOutputStream) {
        check(offset in 0 until 10000)
        check(first != last)

        val offsetMax = 15
        val countMax = 7
        val count = last - first - 1

        val offsetLow = min(offset, offsetMax)
        val countLow = min(count, countMax)
        out.write((offsetLow shl 3) or countLow)
        writeOverflow(offset - offsetMax, out)
        writeOverflow(count - countMax, out)
        out.write(line, first, last - first)
    }

    /** Replaces [count] bytes at [offset] (relative to the previous edit) with [value]. */
    private fun writeRepeat(offset: Int, count: Int, value: Byte, out: ByteArrayOutputStream) {
        check(offset in 0 until 10000)
        check(count in 2 until 10000)

        val offsetMax = 3
        val countMax = 31
        val adjustedCount = count - 2

        val offsetLow = min(offset, offsetMax)
        val countLow = min(adjustedCount, countMax)
        out.write(128 or (offsetLow shl 5) or countLow)
        writeOverflow(offset - offsetMax, out)
        writeOverflow(adjustedCount - countMax, out)
        out.write(value.toInt())
    }

    private fun ByteArray.allZeros() = all { it.toInt() == 0 }

    /** Number of bytes from [first] that are equal to `line[first]`. */
    private fun repeatLength(line: ByteArray, first: Int, last: Int): Int {
        if (first == last) return 0
        val k = line[first]
        var i = first + 1
        while (i < last && line[i] == k) i++
        return i - first
    }

    /**
     * Number of bytes from [first] worth sending as a substitute: stops before two bytes that match the
     * reference, or before a run of three equal bytes (better sent as a repeat).
     */
    private fun substituteLength(line: ByteArray, reference: ByteArray, first: Int, last: Int): Int {
        if (first != last) {
            var it = first
            var next = first + 1
            var prev = first
            while (next != last) {
                if (line[it] == reference[it] && line[next] == reference[next]) {
                    return it - first
                }
                if (line[it] == line[next] && line[it] == line[prev]) {
                    return prev - first
                }
                prev = it
                it = next
                next++
            }
        }
        return last - first
    }

    /** Encodes [line] as a list of edits to [reference], the previous line. */
    fun encodeLine(line: ByteArray, reference: ByteArray): ByteArray {
        check(line.size == reference.size)
        if (line.allZeros()) return byteArrayOf(0xFF.toByte())

        val output = ByteArrayOutputStream(line.size + 16)
        output.write(0) // The first byte is the edit count

        var numEdits = 0

        // Everything after the last difference from the reference stays the same
        var lineEnd = line.size
        while (lineEnd > 0 && line[lineEnd - 1] == reference[lineEnd - 1]) lineEnd--

        // The line and the reference are always at the same position
        var pos = 0
        while (true) {
            val start = pos
            while (pos < lineEnd && line[pos] == reference[pos]) pos++
            val offset = pos - start
            if (pos == lineEnd) {
                // No more differences, we're done.
                break
            }

            if (++numEdits == MAX_EDITS) {
                // We've run out of edits. Just output the rest of the line in a big substitute command.
                writeSubstitute(offset, line, pos, lineEnd, output)
                break
            }

            val s = substituteLength(line, reference, pos, lineEnd)
            if (s > 0) {
                writeSubstitute(offset, line, pos, pos + s, output)
                pos += s
            } else {
                val r = repeatLength(line, pos, lineEnd)
                check(r >= 2)
                writeRepeat(offset, r, line[pos], output)
                pos += r
            }
        }

        check(numEdits <= MAX_EDITS)
        return output.toByteArray().also { it[0] = numEdits.toByte() }
    }

    /** Encodes [line] on its own, as a single substitute of the whole line. */
    fun encodeLine(line: ByteArray): ByteArray {
        if (line.allZeros()) return byteArrayOf(0xFF.toByte())
        val output = ByteArrayOutputStream(line.size + 16)
        output.write(1)
        writeSubstitute(0, line, 0, line.size, output)
        return output.toByteArray()
    }
}
