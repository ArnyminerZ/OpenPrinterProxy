package com.arnyminerz.openprinterproxy.printing

import com.arnyminerz.openprinterproxy.OtgUtils.extractIeee1284Value

/** Page description languages we can produce, in order of preference. */
enum class PrinterLanguage(val displayName: String) {
    /** The printer renders the PDF itself: best quality, and keeps color. */
    PDF("PDF"),

    /** We rasterize the PDF into monochrome PCL raster graphics, which almost every PCL printer supports. */
    PCL("PCL (black and white)"),

    /** Brother host-based lasers (DCP-1510, HL-1110, ...), see [BrotherLaserWriter]. */
    BROTHER_LASER("Brother laser (GDI)");

    companion object {
        /**
         * Picks the language to use from the `CMD` (command set) field of the printer's IEEE 1284 device ID, e.g.
         * "MFG:HP;MDL:LaserJet 1320;CMD:PJL,PCL,PCL5,POSTSCRIPT;".
         * @return null if the printer doesn't support any language we can produce (e.g. other brands' host-based
         * printers).
         */
        fun detect(deviceId: String): PrinterLanguage? {
            val commands = commandSet(deviceId)
            return when {
                "PDF" in commands -> PDF
                // PCL XL (PCL 6 "enhanced") and PCLm are different formats from the classic PCL we produce
                commands.any { it.startsWith("PCL") && it != "PCLXL" && it != "PCLM" } -> PCL
                // e.g. "MFG:Brother;CMD:PJL,XL2HB;MDL:DCP-1510 series;CLS:PRINTER;CID:Brother Laser Type1;"
                "XL2HB" in commands ||
                    extractIeee1284Value(deviceId, "CID:")?.uppercase()?.startsWith("BROTHER LASER TYPE") == true ->
                    BROTHER_LASER
                else -> null
            }
        }

        /** Whether the printer understands PJL, which we use to switch it to the right language. */
        fun supportsPjl(deviceId: String): Boolean = "PJL" in commandSet(deviceId)

        fun commandSet(deviceId: String): Set<String> =
            (extractIeee1284Value(deviceId, "CMD:") ?: extractIeee1284Value(deviceId, "COMMAND SET:"))
                ?.split(',')
                ?.map { it.trim().uppercase() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                .orEmpty()
    }
}
