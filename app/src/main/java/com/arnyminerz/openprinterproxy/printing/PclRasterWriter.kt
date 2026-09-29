package com.arnyminerz.openprinterproxy.printing

import android.print.PrintAttributes.Margins
import android.print.PrintAttributes.MediaSize
import java.io.File
import java.io.OutputStream

/**
 * Converts a PDF into monochrome PCL 5 raster graphics, which almost every PCL printer supports.
 *
 * @param mediaSize The paper, in portrait orientation.
 * @param dpi 300 or 600.
 * @param margins Area around the edges that isn't printed. The raster starts at the top left margin.
 */
class PclRasterWriter(
    private val mediaSize: MediaSize,
    private val dpi: Int,
    private val margins: Margins,
    private val usePjl: Boolean,
    private val isCancelled: () -> Boolean,
) : PrintDataWriter {
    private companion object {
        const val ESC = "\u001B"
    }

    /** PCL page size (`ESC &l#A`) codes. */
    private val pclPageSize = when (mediaSize.id) {
        MediaSize.NA_LETTER.id -> 2
        MediaSize.NA_LEGAL.id -> 3
        MediaSize.ISO_A5.id -> 25
        else -> 26 // A4
    }

    /**
     * Horizontal distance from the physical left edge of the paper to the PCL logical page, in mils. The raster
     * position is relative to the logical page.
     */
    private val logicalPageOffsetMils = when (mediaSize.id) {
        MediaSize.NA_LETTER.id, MediaSize.NA_LEGAL.id -> 250
        else -> 237 // ISO sizes: 71 dots at 300 dpi
    }

    override fun write(pdf: File, out: OutputStream, onProgress: (Float) -> Unit) {
        writeHeader(out)
        PdfRasterizer(pdf, mediaSize, dpi, margins, isCancelled).use { rasterizer ->
            val compressed = ByteArray(rasterizer.rowBytes + rasterizer.rowBytes / 128 + 2)
            for (index in 0 until rasterizer.pageCount) {
                writePage(rasterizer, index, compressed, out)
                onProgress((index + 1f) / rasterizer.pageCount)
            }
        }
        writeFooter(out)
    }

    override fun resetCommands(): ByteArray = ("${ESC}E" + if (usePjl) UEL else "").toByteArray(Charsets.US_ASCII)

    private fun writeHeader(out: OutputStream) {
        if (usePjl) out.writeAscii("$UEL@PJL JOB\r\n@PJL ENTER LANGUAGE=PCL\r\n")
        out.writeAscii("${ESC}E") // Reset
        out.writeAscii("$ESC&l${pclPageSize}A") // Page size
        out.writeAscii("$ESC&l0O") // Portrait
        out.writeAscii("$ESC&l0E") // No top margin
        out.writeAscii("$ESC*t${dpi}R") // Raster resolution
    }

    private fun writeFooter(out: OutputStream) {
        out.writeAscii("${ESC}E")
        if (usePjl) out.writeAscii("$UEL@PJL EOJ\r\n$UEL")
    }

    private fun writePage(rasterizer: PdfRasterizer, index: Int, compressed: ByteArray, out: OutputStream) {
        // Position the raster at the top left margin. Decipoints (1/720 inch) work on every PCL 5 printer.
        val x = (margins.leftMils - logicalPageOffsetMils).coerceAtLeast(0) * 720 / 1000
        val y = margins.topMils * 720 / 1000
        out.writeAscii("$ESC&a${x}H$ESC&a${y}V")
        out.writeAscii("$ESC*r${rasterizer.width}S$ESC*r${rasterizer.height}T") // Raster width and height
        out.writeAscii("$ESC*r1A") // Start raster graphics at the cursor
        out.writeAscii("$ESC*b2M") // TIFF (PackBits) compression

        rasterizer.rasterizePage(index) { row, length ->
            if (length == 0) {
                out.writeAscii("$ESC*b0W") // Blank row
            } else {
                val size = packBits(row, length, compressed)
                out.writeAscii("$ESC*b${size}W")
                out.write(compressed, 0, size)
            }
        }

        out.writeAscii("$ESC*rC") // End raster graphics
        out.writeAscii("\u000C") // Eject the page
    }

    /**
     * TIFF PackBits compression (PCL compression method 2).
     * @return The number of bytes written into [dst].
     */
    private fun packBits(src: ByteArray, length: Int, dst: ByteArray): Int {
        var i = 0
        var o = 0
        while (i < length) {
            var run = 1
            while (i + run < length && run < 128 && src[i + run] == src[i]) run++

            if (run >= 3) {
                dst[o++] = (1 - run).toByte()
                dst[o++] = src[i]
                i += run
            } else {
                // Literal bytes, up to the next run of 3 or more
                val start = i
                i++
                while (i < length && i - start < 128 &&
                    !(i + 2 < length && src[i] == src[i + 1] && src[i] == src[i + 2])
                ) i++
                val count = i - start
                dst[o++] = (count - 1).toByte()
                System.arraycopy(src, start, dst, o, count)
                o += count
            }
        }
        return o
    }
}
