package com.arnyminerz.openprinterproxy.ipp

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.createBitmap
import com.arnyminerz.openprinterproxy.printing.PrintException
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.util.concurrent.CancellationException

/**
 * Converts the raster formats driverless clients send (PWG Raster, and Apple Raster / URF for AirPrint) into a PDF,
 * so they can go through the same pipeline as PDF documents.
 */
object RasterToPdf {
    const val FORMAT_PDF = "application/pdf"
    const val FORMAT_PWG = "image/pwg-raster"
    const val FORMAT_URF = "image/urf"

    private const val PWG_HEADER_SIZE = 1796
    private const val URF_HEADER_SIZE = 32
    private const val BAND_HEIGHT = 64

    // CUPS/PWG color spaces
    private const val CSPACE_W = 0
    private const val CSPACE_RGB = 1
    private const val CSPACE_K = 3
    private const val CSPACE_SW = 18
    private const val CSPACE_SRGB = 19
    private const val CSPACE_ADOBE_RGB = 20

    /** Guesses the format of a document from its first bytes. */
    fun detectFormat(file: File): String? {
        val magic = ByteArray(8)
        val read = file.inputStream().use { it.read(magic) }
        val text = String(magic, 0, read.coerceAtLeast(0), Charsets.US_ASCII)
        return when {
            text.startsWith("%PDF") -> FORMAT_PDF
            text.startsWith("RaS2") -> FORMAT_PWG
            text.startsWith("UNIRAST") -> FORMAT_URF
            else -> null
        }
    }

    private enum class PixelType { BLACK_1, BLACK_8, GRAY_8, RGB_24 }

    /** One page of raster data. */
    private class PageHeader(
        val width: Int,
        val height: Int,
        val dpi: Int,
        val bytesPerLine: Int,
        val type: PixelType,
        val widthPoints: Int,
        val heightPoints: Int,
    ) {
        /** The compression works on pixels, except for 1 bit data, where it works on bytes. */
        val bytesPerPixel = when (type) {
            PixelType.BLACK_1, PixelType.BLACK_8, PixelType.GRAY_8 -> 1
            PixelType.RGB_24 -> 3
        }

        /** Value of a white byte. */
        val white: Byte = if (type == PixelType.BLACK_1 || type == PixelType.BLACK_8) 0 else 0xFF.toByte()
    }

    /**
     * @param format [FORMAT_PWG] or [FORMAT_URF].
     * @throws PrintException if the data is malformed or uses an unsupported color space.
     * @throws CancellationException if [isCancelled] returns true while converting.
     */
    fun convert(input: File, format: String, output: File, isCancelled: () -> Boolean) {
        val document = PdfDocument()
        try {
            DataInputStream(input.inputStream().buffered()).use { data ->
                var pageNumber = 0
                when (format) {
                    FORMAT_PWG -> {
                        data.skipBytes(4) // "RaS2"
                        while (true) {
                            val header = readPwgHeader(data) ?: break
                            drawPage(document, data, header, ++pageNumber, isCancelled)
                        }
                    }
                    FORMAT_URF -> {
                        data.skipBytes(8) // "UNIRAST\0"
                        // Clients that stream the pages don't know the total and write 0, so read until the end then
                        val declaredPages = data.readInt()
                        while (declaredPages == 0 || pageNumber < declaredPages) {
                            val header = readUrfHeader(data) ?: break
                            drawPage(document, data, header, ++pageNumber, isCancelled)
                        }
                    }
                    else -> throw PrintException("Unsupported document format: $format")
                }
                if (pageNumber == 0) throw PrintException("The document has no pages")
            }
            output.outputStream().use(document::writeTo)
        } catch (_: EOFException) {
            throw PrintException("The document is truncated")
        } finally {
            document.close()
        }
    }

    private fun ByteArray.int(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or (this[offset + 3].toInt() and 0xFF)

    /** @return null at the end of the document. */
    private fun readPwgHeader(data: DataInputStream): PageHeader? {
        val header = ByteArray(PWG_HEADER_SIZE)
        val first = data.read(header)
        if (first <= 0) return null
        data.readFully(header, first, PWG_HEADER_SIZE - first)

        val dpi = header.int(276)
        val width = header.int(372)
        val height = header.int(376)
        val bitsPerPixel = header.int(388)
        val bytesPerLine = header.int(392)
        val colorOrder = header.int(396)
        val colorSpace = header.int(400)
        if (colorOrder != 0) throw PrintException("Unsupported raster color order $colorOrder")

        val type = when {
            colorSpace == CSPACE_K && bitsPerPixel == 1 -> PixelType.BLACK_1
            colorSpace == CSPACE_K && bitsPerPixel == 8 -> PixelType.BLACK_8
            (colorSpace == CSPACE_SW || colorSpace == CSPACE_W) && bitsPerPixel == 8 -> PixelType.GRAY_8
            (colorSpace == CSPACE_SRGB || colorSpace == CSPACE_RGB || colorSpace == CSPACE_ADOBE_RGB) &&
                bitsPerPixel == 24 -> PixelType.RGB_24
            else -> throw PrintException("Unsupported raster type (color space $colorSpace, $bitsPerPixel bits)")
        }
        return validated(
            PageHeader(width, height, dpi, bytesPerLine, type, header.int(352), header.int(356))
        )
    }

    /** @return null at the end of the document. */
    private fun readUrfHeader(data: DataInputStream): PageHeader? {
        val header = ByteArray(URF_HEADER_SIZE)
        val first = data.read(header)
        if (first <= 0) return null
        data.readFully(header, first, URF_HEADER_SIZE - first)

        val bitsPerPixel = header[0].toInt() and 0xFF
        val colorSpace = header[1].toInt() and 0xFF
        val width = header.int(12)
        val height = header.int(16)
        val dpi = header.int(20)
        // URF color spaces: 0 = sGray, 1 = sRGB, 2 = CIELab, 3 = AdobeRGB, 4 = gray, 5 = RGB, 6 = CMYK
        val type = when {
            bitsPerPixel == 8 && (colorSpace == 0 || colorSpace == 4) -> PixelType.GRAY_8
            bitsPerPixel == 24 && (colorSpace == 1 || colorSpace == 3 || colorSpace == 5) -> PixelType.RGB_24
            else -> throw PrintException("Unsupported raster type (color space $colorSpace, $bitsPerPixel bits)")
        }
        return validated(PageHeader(width, height, dpi, width * bitsPerPixel / 8, type, 0, 0))
    }

    private fun validated(header: PageHeader): PageHeader {
        val expectedBytesPerLine = when (header.type) {
            PixelType.BLACK_1 -> (header.width + 7) / 8
            PixelType.BLACK_8, PixelType.GRAY_8 -> header.width
            PixelType.RGB_24 -> header.width * 3
        }
        if (header.width !in 1..20_000 || header.height !in 1..40_000 || header.dpi !in 50..2400 ||
            header.bytesPerLine != expectedBytesPerLine
        ) throw PrintException("Malformed raster page")
        return header
    }

    private fun drawPage(
        document: PdfDocument,
        data: DataInputStream,
        header: PageHeader,
        pageNumber: Int,
        isCancelled: () -> Boolean,
    ) {
        val scale = 72f / header.dpi
        val widthPoints = header.widthPoints.takeIf { it > 0 } ?: Math.round(header.width * scale)
        val heightPoints = header.heightPoints.takeIf { it > 0 } ?: Math.round(header.height * scale)
        val page = document.startPage(PdfDocument.PageInfo.Builder(widthPoints, heightPoints, pageNumber).create())
        val bands = mutableListOf<Bitmap>()
        try {
            val canvas = page.canvas
            val pixels = IntArray(header.width * BAND_HEIGHT)
            var bandTop = 0
            var bandRows = 0

            fun flushBand() {
                if (bandRows == 0) return
                // A new bitmap per band, since the PDF may keep a reference to it until the page is finished
                val band = createBitmap(header.width, bandRows, Bitmap.Config.ARGB_8888)
                band.setPixels(pixels, 0, header.width, 0, 0, header.width, bandRows)
                bands += band
                canvas.drawBitmap(
                    band,
                    Rect(0, 0, header.width, bandRows),
                    RectF(0f, bandTop * scale, header.width * scale, (bandTop + bandRows) * scale),
                    null
                )
                bandTop += bandRows
                bandRows = 0
            }

            decodeLines(data, header) { line ->
                toArgb(line, header, pixels, bandRows * header.width)
                if (++bandRows == BAND_HEIGHT) {
                    if (isCancelled()) throw CancellationException()
                    flushBand()
                }
            }
            flushBand()
        } finally {
            document.finishPage(page)
            bands.forEach(Bitmap::recycle)
        }
    }

    /**
     * Decodes the PWG/Apple raster compression: every line starts with a repeat count, followed by runs of repeated
     * pixels (0..127: the next pixel n + 1 times), literal pixels (129..255: 257 - n pixels), or 128 to fill the rest
     * of the line with white.
     */
    private fun decodeLines(data: DataInputStream, header: PageHeader, onLine: (ByteArray) -> Unit) {
        val line = ByteArray(header.bytesPerLine)
        val pixel = ByteArray(header.bytesPerPixel)
        var y = 0
        while (y < header.height) {
            val lineRepeat = data.readUnsignedByte() + 1
            var x = 0
            while (x < line.size) {
                val n = data.readUnsignedByte()
                when {
                    n == 128 -> {
                        line.fill(header.white, x)
                        x = line.size
                    }
                    n < 128 -> {
                        data.readFully(pixel)
                        repeat(n + 1) {
                            if (x < line.size) pixel.copyInto(line, x, 0, minOf(pixel.size, line.size - x))
                            x += pixel.size
                        }
                    }
                    else -> {
                        val count = (257 - n) * pixel.size
                        val fits = minOf(count, line.size - x)
                        data.readFully(line, x, fits)
                        data.skipBytes(count - fits)
                        x += count
                    }
                }
            }
            repeat(minOf(lineRepeat, header.height - y)) {
                onLine(line)
                y++
            }
        }
    }

    private fun toArgb(line: ByteArray, header: PageHeader, pixels: IntArray, offset: Int) {
        for (x in 0 until header.width) {
            val gray = when (header.type) {
                PixelType.BLACK_1 -> if ((line[x shr 3].toInt() shr (7 - (x and 7))) and 1 == 1) 0 else 255
                PixelType.BLACK_8 -> 255 - (line[x].toInt() and 0xFF)
                PixelType.GRAY_8 -> line[x].toInt() and 0xFF
                PixelType.RGB_24 -> {
                    val r = line[x * 3].toInt() and 0xFF
                    val g = line[x * 3 + 1].toInt() and 0xFF
                    val b = line[x * 3 + 2].toInt() and 0xFF
                    pixels[offset + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    continue
                }
            }
            pixels[offset + x] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
    }
}
