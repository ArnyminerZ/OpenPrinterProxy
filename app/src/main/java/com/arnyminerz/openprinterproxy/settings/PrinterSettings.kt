package com.arnyminerz.openprinterproxy.settings

import android.content.Context
import android.print.PrintAttributes.Margins
import android.print.PrintAttributes.MediaSize
import com.arnyminerz.openprinterproxy.printing.PrinterLanguage

enum class ColorSetting(val displayName: String) {
    /** Color only if the printer renders PDF itself; our raster output is black and white. */
    AUTO("Automatic"),
    COLOR("Color"),
    MONOCHROME("Black and white"),
}

/**
 * Per-printer preferences. `null` values mean "automatic".
 *
 * @param language Overrides the language detected from the printer's device ID.
 * @param defaultMediaSizeId [MediaSize.id] preselected in the print dialog. Automatic picks by region.
 * @param defaultDpi 300 or 600. Automatic is 600 for Brother lasers and 300 otherwise.
 * @param marginMils Minimum margins on every side, in mils (1/1000 inch).
 * @param tonerSave Brother's toner save mode.
 * @param shareOnNetwork Whether other devices on the local network can print to it over IPP.
 */
data class PrinterSettings(
    val language: PrinterLanguage? = null,
    val color: ColorSetting = ColorSetting.AUTO,
    val defaultMediaSizeId: String? = null,
    val defaultDpi: Int? = null,
    val marginMils: Int? = null,
    val tonerSave: Boolean = false,
    val shareOnNetwork: Boolean = false,
) {
    companion object {
        // USB printers don't report their capabilities over the printer class protocol, so these are conservative
        // defaults that most office printers support.

        val MEDIA_SIZES: List<MediaSize> =
            listOf(MediaSize.ISO_A4, MediaSize.NA_LETTER, MediaSize.NA_LEGAL, MediaSize.ISO_A5)

        val SUPPORTED_DPI = listOf(300, 600)

        /**
         * Most USB printers can't print borderless. 250 mils = 6.35 mm, which is also where the PCL logical page
         * starts, so PCL raster output lines up with it.
         */
        const val DEFAULT_MARGIN_MILS = 250

        /** Countries that use US Letter as their default paper size; everywhere else defaults to A4. */
        private val LETTER_COUNTRIES = setOf("US", "CA", "MX", "PH", "CL", "CO", "VE", "PR")

        fun regionalMediaSize(context: Context): MediaSize {
            val country = context.resources.configuration.locales[0].country
            return if (country in LETTER_COUNTRIES) MediaSize.NA_LETTER else MediaSize.ISO_A4
        }
    }

    /** The language to use, given the printer's IEEE 1284 [deviceId]. */
    fun resolveLanguage(deviceId: String?): PrinterLanguage? =
        language ?: deviceId?.let(PrinterLanguage::detect)

    fun supportsColor(language: PrinterLanguage?): Boolean = when (color) {
        ColorSetting.AUTO -> language == PrinterLanguage.PDF
        ColorSetting.COLOR -> true
        ColorSetting.MONOCHROME -> false
    }

    fun resolveDpi(language: PrinterLanguage?): Int =
        defaultDpi ?: if (language == PrinterLanguage.BROTHER_LASER) 600 else 300

    fun resolveMediaSize(context: Context): MediaSize =
        MEDIA_SIZES.firstOrNull { it.id == defaultMediaSizeId } ?: regionalMediaSize(context)

    fun margins(): Margins = (marginMils ?: DEFAULT_MARGIN_MILS).let { Margins(it, it, it, it) }
}
