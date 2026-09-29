package com.arnyminerz.openprinterproxy.printing

import java.io.File
import java.io.OutputStream

/** Converts a PDF into the data a printer understands. */
interface PrintDataWriter {
    /**
     * Writes the whole job for [pdf] into [out].
     * @param onProgress Called with the fraction of the conversion done.
     * @throws java.util.concurrent.CancellationException if the job is cancelled while converting.
     */
    fun write(pdf: File, out: OutputStream, onProgress: (Float) -> Unit)

    /** Commands that bring the printer back to a clean state, for when a job is aborted halfway. */
    fun resetCommands(): ByteArray
}

internal fun OutputStream.writeAscii(text: String) = write(text.toByteArray(Charsets.US_ASCII))

/** Universal Exit Language: hands control back to PJL. */
internal const val UEL = "\u001B%-12345X"

/** Sends the PDF as-is, for printers that render PDF themselves. */
class PdfPassthroughWriter(private val usePjl: Boolean) : PrintDataWriter {
    override fun write(pdf: File, out: OutputStream, onProgress: (Float) -> Unit) {
        if (usePjl) out.writeAscii("$UEL@PJL JOB\r\n@PJL ENTER LANGUAGE=PDF\r\n")
        pdf.inputStream().use { it.copyTo(out) }
        if (usePjl) out.writeAscii("$UEL@PJL EOJ\r\n$UEL")
        onProgress(1f)
    }

    override fun resetCommands(): ByteArray = if (usePjl) UEL.toByteArray(Charsets.US_ASCII) else ByteArray(0)
}
