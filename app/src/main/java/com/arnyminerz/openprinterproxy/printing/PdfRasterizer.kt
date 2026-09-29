package com.arnyminerz.openprinterproxy.printing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes.Margins
import android.print.PrintAttributes.MediaSize
import androidx.core.graphics.createBitmap
import java.io.Closeable
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.min

/**
 * Renders the pages of a PDF into 1 bit per pixel rows (1 = black, most significant bit first), in horizontal bands
 * so that high resolution pages don't need a full-page bitmap.
 *
 * Every page is rendered onto portrait paper; landscape pages are rotated, and pages that don't match the paper
 * size are scaled to fit and centered. Grays and colors are ordered-dithered.
 *
 * @param mediaSize The paper, in portrait orientation.
 * @param crop Area around the edges of the paper that is left out of the raster. [Margins.NO_MARGINS] for the full
 * page.
 */
class PdfRasterizer(
    pdf: File,
    mediaSize: MediaSize,
    val dpi: Int,
    crop: Margins,
    private val isCancelled: () -> Boolean,
) : Closeable {
    private companion object {
        const val BAND_HEIGHT = 128

        /** 8x8 Bayer threshold matrix, values 0 to 63. */
        val BAYER_8 = arrayOf(
            intArrayOf(0, 32, 8, 40, 2, 34, 10, 42),
            intArrayOf(48, 16, 56, 24, 50, 18, 58, 26),
            intArrayOf(12, 44, 4, 36, 14, 46, 6, 38),
            intArrayOf(60, 28, 52, 20, 62, 30, 54, 22),
            intArrayOf(3, 35, 11, 43, 1, 33, 9, 41),
            intArrayOf(51, 19, 59, 27, 49, 17, 57, 25),
            intArrayOf(15, 47, 7, 39, 13, 45, 5, 37),
            intArrayOf(63, 31, 55, 23, 61, 29, 53, 21),
        )

        /** Luminance thresholds (0-255) from [BAYER_8], so pure black is always black and pure white always white. */
        val THRESHOLDS = Array(8) { y -> IntArray(8) { x -> BAYER_8[y][x] * 4 + 2 } }
    }

    private fun milsToDots(mils: Int) = mils * dpi / 1000

    private val pageWidthDots = milsToDots(mediaSize.widthMils)
    private val pageHeightDots = milsToDots(mediaSize.heightMils)
    private val leftDots = milsToDots(crop.leftMils)
    private val topDots = milsToDots(crop.topMils)

    /** Width of the raster, in pixels. */
    val width = pageWidthDots - leftDots - milsToDots(crop.rightMils)

    /** Height of the raster, in pixels. */
    val height = pageHeightDots - topDots - milsToDots(crop.bottomMils)

    /** Bytes per row. */
    val rowBytes = (width + 7) / 8

    private val fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(fd)
    } catch (e: Exception) {
        fd.close()
        throw e
    }
    private val bitmap = createBitmap(width, BAND_HEIGHT, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(width * BAND_HEIGHT)
    private val row = ByteArray(rowBytes)

    val pageCount: Int get() = renderer.pageCount

    /**
     * Renders page [index] and calls [onRow] for every row, top to bottom.
     *
     * [onRow] receives a buffer of [rowBytes] bytes that is reused for the next row, and the number of bytes up to
     * the last one containing black pixels (0 for a blank row).
     * @throws CancellationException if [isCancelled] returns true while rendering.
     */
    fun rasterizePage(index: Int, onRow: (row: ByteArray, length: Int) -> Unit) {
        renderer.openPage(index).use { page ->
            val pageTransform = pageTransform(page)
            for (bandTop in 0 until height step BAND_HEIGHT) {
                if (isCancelled()) throw CancellationException()

                val bandRows = min(BAND_HEIGHT, height - bandTop)
                bitmap.eraseColor(Color.WHITE)
                val transform = Matrix(pageTransform).apply { postTranslate(0f, -bandTop.toFloat()) }
                page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, bandRows)

                for (r in 0 until bandRows) {
                    val length = ditherRow(r * width, bandTop + r)
                    onRow(row, length)
                }
            }
        }
    }

    override fun close() {
        bitmap.recycle()
        renderer.close()
        fd.close()
    }

    /**
     * Maps PDF page coordinates (points) into raster pixels: rotates landscape pages onto portrait paper, scales
     * the page to fit the paper, centers it, and offsets it by the crop.
     */
    private fun pageTransform(page: PdfRenderer.Page): Matrix {
        val rotate = page.width > page.height
        val effectiveWidth = (if (rotate) page.height else page.width).toFloat()
        val effectiveHeight = (if (rotate) page.width else page.height).toFloat()
        val scale = min(pageWidthDots / effectiveWidth, pageHeightDots / effectiveHeight)
        val dx = (pageWidthDots - effectiveWidth * scale) / 2
        val dy = (pageHeightDots - effectiveHeight * scale) / 2

        return Matrix().apply {
            if (rotate) {
                postRotate(90f)
                postTranslate(page.height.toFloat(), 0f)
            }
            postScale(scale, scale)
            postTranslate(dx - leftDots, dy - topDots)
        }
    }

    /**
     * Converts one row of ARGB pixels from [pixels] into [row].
     * @return The number of bytes up to the last one containing black pixels; 0 for a blank row.
     */
    private fun ditherRow(offset: Int, y: Int): Int {
        row.fill(0)
        val thresholds = THRESHOLDS[y and 7]
        var length = 0
        for (x in 0 until width) {
            val pixel = pixels[offset + x]
            val luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
            if (luminance < thresholds[x and 7]) {
                val byteIndex = x shr 3
                row[byteIndex] = (row[byteIndex].toInt() or (0x80 ushr (x and 7))).toByte()
                length = byteIndex + 1
            }
        }
        return length
    }
}
