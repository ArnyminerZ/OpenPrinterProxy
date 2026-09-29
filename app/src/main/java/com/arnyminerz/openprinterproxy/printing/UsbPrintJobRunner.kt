package com.arnyminerz.openprinterproxy.printing

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintAttributes.MediaSize
import android.util.Log
import com.arnyminerz.openprinterproxy.OtgUtils.readDeviceId
import com.arnyminerz.openprinterproxy.OtgUtils.withPrinterInterface
import com.arnyminerz.openprinterproxy.settings.PrinterSettings
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException

/** A print failure with a message that can be shown to the user. */
class PrintException(message: String) : Exception(message)

/**
 * Sends a print job to a USB printer: detects the printer's language, converts the document if needed, and
 * streams it over the printer's bulk OUT endpoint. Everything here is blocking, so run it off the main thread.
 */
class UsbPrintJobRunner(
    private val usbManager: UsbManager,
    private val cacheDir: File,
    private val isCancelled: () -> Boolean,
    private val onProgress: (Float) -> Unit,
) {
    private companion object {
        const val TAG = "UsbPrintJobRunner"

        /** Older Android versions can't do bulk transfers larger than 16 KB. */
        const val CHUNK_SIZE = 16 * 1024

        /** Printers stop accepting data while warming up or feeding paper, so allow for long stalls. */
        const val TRANSFER_TIMEOUT_MS = 60_000
    }

    /**
     * @param document The PDF to print. It's closed when this returns.
     * @param jobName Shown by printers that display the job name.
     * @throws PrintException with a user facing message if printing fails.
     * @throws CancellationException if the job was cancelled.
     */
    fun print(
        device: UsbDevice,
        document: ParcelFileDescriptor,
        attributes: PrintAttributes,
        copies: Int,
        jobName: String,
        settings: PrinterSettings,
    ) {
        val pdf = File.createTempFile("job", ".pdf", cacheDir)
        try {
            // The document is usually a pipe, and PdfRenderer needs a seekable file
            ParcelFileDescriptor.AutoCloseInputStream(document).use { input ->
                pdf.outputStream().use { input.copyTo(it) }
            }
            printPdf(device, pdf, attributes, copies, jobName, settings)
        } finally {
            pdf.delete()
        }
    }

    /**
     * Like [print], for a PDF that's already in a file. The file isn't deleted.
     */
    fun printPdf(
        device: UsbDevice,
        pdf: File,
        attributes: PrintAttributes,
        copies: Int,
        jobName: String,
        settings: PrinterSettings,
    ) {
        val output = File.createTempFile("job", ".prn", cacheDir)
        try {
            val deviceId = withPrinterInterface(usbManager, device, ::readDeviceId)
                ?: throw PrintException("Couldn't communicate with the printer")
            Log.d(TAG, "Printing to $deviceId")
            val language = settings.resolveLanguage(deviceId)
                ?: throw PrintException(
                    "This printer's language isn't supported " +
                        "(${PrinterLanguage.commandSet(deviceId).joinToString().ifEmpty { "unknown" }}). " +
                        "You can choose one in the printer's settings."
                )
            val usePjl = PrinterLanguage.supportsPjl(deviceId)

            val mediaSize = (attributes.mediaSize ?: MediaSize.ISO_A4).asPortrait()
            val dpi = if ((attributes.resolution?.horizontalDpi ?: settings.resolveDpi(language)) >= 600) 600 else 300
            val margins = attributes.minMargins ?: settings.margins()
            val writer = when (language) {
                PrinterLanguage.PDF -> PdfPassthroughWriter(usePjl)
                PrinterLanguage.PCL -> PclRasterWriter(mediaSize, dpi, margins, usePjl, isCancelled)
                PrinterLanguage.BROTHER_LASER ->
                    BrotherLaserWriter(mediaSize, dpi, settings.tonerSave, jobName, isCancelled)
            }

            // Generating output is the first half of the progress, sending it the second
            output.outputStream().buffered().use { out ->
                writer.write(pdf, out) { onProgress(it / 2) }
            }
            onProgress(0.5f)

            send(device, output, copies, writer.resetCommands())
        } finally {
            output.delete()
        }
    }

    /** Sends [file] to the printer [copies] times, which gives collated copies. */
    private fun send(device: UsbDevice, file: File, copies: Int, resetCommands: ByteArray) {
        val totalBytes = file.length() * copies
        var sentBytes = 0L

        withPrinterInterface(usbManager, device) { connection, printerInterface ->
            val endpoint = findBulkOutEndpoint(printerInterface)
                ?: throw PrintException("The printer doesn't accept data")
            try {
                repeat(copies) {
                    file.inputStream().use { input ->
                        sendStream(connection, endpoint, input) { sent ->
                            sentBytes += sent
                            onProgress(0.5f + 0.5f * sentBytes / totalBytes)
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Don't leave the printer waiting for the rest of the job
                if (resetCommands.isNotEmpty()) {
                    connection.bulkTransfer(endpoint, resetCommands, resetCommands.size, TRANSFER_TIMEOUT_MS)
                }
                throw e
            }
        } ?: throw PrintException("Couldn't open the printer. Is it still connected?")
    }

    private fun sendStream(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        input: InputStream,
        onSent: (Int) -> Unit,
    ) {
        val buffer = ByteArray(CHUNK_SIZE)
        while (true) {
            if (isCancelled()) throw CancellationException()
            val read = input.read(buffer)
            if (read < 0) break

            var offset = 0
            while (offset < read) {
                val sent = connection.bulkTransfer(endpoint, buffer, offset, read - offset, TRANSFER_TIMEOUT_MS)
                if (sent < 0) throw PrintException("The printer stopped responding")
                offset += sent
                onSent(sent)
            }
        }
    }

    private fun findBulkOutEndpoint(printerInterface: UsbInterface): UsbEndpoint? =
        (0 until printerInterface.endpointCount)
            .map(printerInterface::getEndpoint)
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
}
