package com.arnyminerz.openprinterproxy.ipp

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.print.PrintAttributes
import android.print.PrintAttributes.MediaSize
import android.util.Log
import com.arnyminerz.openprinterproxy.OtgUtils.extractIeee1284Value
import com.arnyminerz.openprinterproxy.OtgUtils.findPrinter
import com.arnyminerz.openprinterproxy.ipp.RasterToPdf.FORMAT_PDF
import com.arnyminerz.openprinterproxy.ipp.RasterToPdf.FORMAT_PWG
import com.arnyminerz.openprinterproxy.ipp.RasterToPdf.FORMAT_URF
import com.arnyminerz.openprinterproxy.printing.PrintException
import com.arnyminerz.openprinterproxy.printing.UsbPrintJobRunner
import com.arnyminerz.openprinterproxy.service.PrintJobNotifier
import com.arnyminerz.openprinterproxy.settings.PrinterSettings
import com.arnyminerz.openprinterproxy.settings.PrinterSettingsRepository
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * One USB printer shared over IPP: answers IPP requests for it, and prints the jobs it receives.
 *
 * Requests arrive on the server's connection threads; jobs are printed one at a time on [jobExecutor].
 *
 * @param notifier Only used on the main thread.
 */
class IppPrinter(
    private val context: Context,
    val localId: String,
    private val jobExecutor: ExecutorService,
    private val notifier: PrintJobNotifier,
) {
    companion object {
        private const val TAG = "IppPrinter"

        private const val PATH_PREFIX = "/ipp/print/"

        fun pathFor(localId: String) = PATH_PREFIX + localId.replace(Regex("[^A-Za-z0-9._-]"), "-")

        // Operations
        private const val OP_PRINT_JOB = 0x0002
        private const val OP_VALIDATE_JOB = 0x0004
        private const val OP_CREATE_JOB = 0x0005
        private const val OP_SEND_DOCUMENT = 0x0006
        private const val OP_CANCEL_JOB = 0x0008
        private const val OP_GET_JOB_ATTRIBUTES = 0x0009
        private const val OP_GET_JOBS = 0x000A
        private const val OP_GET_PRINTER_ATTRIBUTES = 0x000B
        private const val OP_CLOSE_JOB = 0x003B
        private const val OP_IDENTIFY_PRINTER = 0x003C
        private val OPERATIONS = intArrayOf(
            OP_PRINT_JOB, OP_VALIDATE_JOB, OP_CREATE_JOB, OP_SEND_DOCUMENT, OP_CANCEL_JOB, OP_GET_JOB_ATTRIBUTES,
            OP_GET_JOBS, OP_GET_PRINTER_ATTRIBUTES, OP_CLOSE_JOB, OP_IDENTIFY_PRINTER,
        )

        // Status codes
        const val STATUS_OK = 0x0000
        const val STATUS_BAD_REQUEST = 0x0400
        private const val STATUS_NOT_POSSIBLE = 0x0404
        const val STATUS_NOT_FOUND = 0x0406
        private const val STATUS_DOCUMENT_FORMAT_NOT_SUPPORTED = 0x040A
        private const val STATUS_OPERATION_NOT_SUPPORTED = 0x0501
        private const val STATUS_VERSION_NOT_SUPPORTED = 0x0503
        private const val STATUS_NOT_ACCEPTING_JOBS = 0x0506

        // Printer and job states
        private const val PRINTER_IDLE = 3
        private const val PRINTER_PROCESSING = 4
        private const val PRINTER_STOPPED = 5
        private const val JOB_PENDING = 3
        private const val JOB_PROCESSING = 5
        private const val JOB_CANCELED = 7
        private const val JOB_ABORTED = 8
        private const val JOB_COMPLETED = 9

        private const val FORMAT_OCTET_STREAM = "application/octet-stream"
        val DOCUMENT_FORMATS = listOf(FORMAT_PDF, FORMAT_PWG, FORMAT_URF)

        /**
         * Raster documents are converted to PDF in memory, so they're only accepted at 300 dpi. PDF documents keep
         * their full quality.
         */
        private const val RASTER_DPI = 300

        /** Apple raster capabilities: version, copies, normal quality, 300 dpi, sRGB and gray, one tray and type. */
        val URF_SUPPORTED = listOf("V1.4", "CP1", "PQ4", "RS$RASTER_DPI", "SRGB24", "W8", "IS1", "MT1")

        private const val MAX_JOBS = 50
        private const val MAX_DOCUMENT_BYTES = 512L * 1024 * 1024
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

        /** PWG media names for [PrinterSettings.MEDIA_SIZES], with their size in hundredths of millimeters. */
        private class Media(val keyword: String, val width: Int, val height: Int, val size: MediaSize)

        private val MEDIA = listOf(
            Media("iso_a4_210x297mm", 21000, 29700, MediaSize.ISO_A4),
            Media("na_letter_8.5x11in", 21590, 27940, MediaSize.NA_LETTER),
            Media("na_legal_8.5x14in", 21590, 35560, MediaSize.NA_LEGAL),
            Media("iso_a5_148x210mm", 14800, 21000, MediaSize.ISO_A5),
        )

        private fun milsToHundredthsMm(mils: Int) = mils * 254 / 100
    }

    val path = pathFor(localId)
    val uuid: UUID = UUID.nameUUIDFromBytes(localId.toByteArray())

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val repository = PrinterSettingsRepository(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val upSince = SystemClock.elapsedRealtime()

    private val jobs = mutableListOf<IppJob>()
    private val nextJobId = AtomicInteger(1)

    /** What a client asked for in the job's attributes. `null` values mean the printer's defaults. */
    private class JobTicket(val copies: Int, val media: Media?, val color: Boolean?, val dpi: Int?)

    private inner class IppJob(
        val id: Int,
        val name: String,
        val user: String,
        val ticket: JobTicket,
    ) {
        val createdAt = SystemClock.elapsedRealtime()
        val notificationId = PrintJobNotifier.newNotificationId()

        @Volatile var state = JOB_PENDING
        @Volatile var reasons = "none"
        @Volatile var message: String? = null
        @Volatile var cancelRequested = false
        @Volatile var processingAt = 0L
        @Volatile var completedAt = 0L

        // Only touched while holding the job's lock
        var document: File? = null
        var format = FORMAT_OCTET_STREAM
        var submitted = false

        val isFinished get() = state >= JOB_CANCELED
    }

    /** The printer's current details. */
    private inner class Snapshot {
        val known = repository.knownPrinter(localId)
        val name = known?.name ?: "USB printer"
        val settings = repository.settings(localId)
        val language = settings.resolveLanguage(known?.deviceId)
        val color = settings.supportsColor(language)
        val connected = findPrinter(usbManager, localId)?.let(usbManager::hasPermission) == true
        val defaultMedia = settings.resolveMediaSize(context).let { size -> MEDIA.first { it.size.id == size.id } }
        val margin = milsToHundredthsMm(settings.marginMils ?: PrinterSettings.DEFAULT_MARGIN_MILS)
    }

    val name: String get() = repository.knownPrinter(localId)?.name ?: "USB printer"

    /** Values for the DNS-SD TXT record, see the "Bonjour Printing Specification" and PWG 5100.14. */
    fun txtRecord(port: Int, host: String?): Map<String, String> {
        val snapshot = Snapshot()
        return buildMap {
            put("txtvers", "1")
            put("qtotal", "1")
            put("rp", path.removePrefix("/"))
            put("ty", snapshot.name)
            put("product", "(${snapshot.name})")
            put("note", "Shared by ${Build.MODEL}")
            put("pdl", (DOCUMENT_FORMATS + FORMAT_OCTET_STREAM).joinToString(","))
            put("URF", URF_SUPPORTED.joinToString(","))
            put("Color", if (snapshot.color) "T" else "F")
            put("Duplex", "F")
            put("UUID", uuid.toString())
            put("kind", "document")
            put("PaperMax", "legal-A4")
            put("priority", "50")
            put("air", "none")
            if (host != null) put("adminurl", "http://$host:$port/")
        }
    }

    /** Cancels the job shown in the notification [notificationId]. @return whether it's one of this printer's. */
    fun cancelByNotification(notificationId: Int): Boolean {
        val job = synchronized(jobs) { jobs.firstOrNull { it.notificationId == notificationId } } ?: return false
        cancel(job)
        return true
    }

    /**
     * Handles an IPP request. For operations that carry a document, it's read from [body].
     * @param host The host and port the client used to reach us, for URIs in the response.
     * @throws IOException if the connection breaks while reading the document.
     */
    fun handle(request: IppMessage, body: InputStream, host: String): IppMessage {
        if (request.versionMajor !in 1..2) return response(request, STATUS_VERSION_NOT_SUPPORTED)
        return when (request.code) {
            OP_GET_PRINTER_ATTRIBUTES -> getPrinterAttributes(request, host)
            OP_VALIDATE_JOB -> validateJob(request) ?: response(request, STATUS_OK)
            OP_PRINT_JOB -> printJob(request, body, host)
            OP_CREATE_JOB -> createJob(request, host)
            OP_SEND_DOCUMENT -> sendDocument(request, body, host)
            OP_CLOSE_JOB -> closeJob(request, host)
            OP_CANCEL_JOB -> cancelJob(request)
            OP_GET_JOB_ATTRIBUTES -> getJobAttributes(request, host)
            OP_GET_JOBS -> getJobs(request, host)
            OP_IDENTIFY_PRINTER -> response(request, STATUS_OK)
            else -> response(request, STATUS_OPERATION_NOT_SUPPORTED)
        }
    }

    // region Responses

    private fun response(
        request: IppMessage,
        status: Int,
        groups: List<IppGroup> = emptyList(),
        message: String? = null,
    ): IppMessage {
        val operation = ippGroup(IppTag.OPERATION) {
            charset("attributes-charset", "utf-8")
            language("attributes-natural-language", "en")
            if (message != null) text("status-message", message)
        }
        return IppMessage(request.versionMajor, request.versionMinor, status, request.requestId, listOf(operation) + groups)
    }

    private fun printerUri(host: String) = "ipp://$host$path"

    private fun upTime(at: Long = SystemClock.elapsedRealtime()) = ((at - upSince) / 1000).toInt() + 1

    private fun getPrinterAttributes(request: IppMessage, host: String): IppMessage {
        val requested = request.operationAttribute("requested-attributes")?.strings?.toSet()
        val attributes = printerAttributes(host)
        val everything = requested == null ||
            requested.any { it == "all" || it == "printer-description" || it == "job-template" }
        if (!everything) attributes.attributes.retainAll { it.name in requested }
        return response(request, STATUS_OK, listOf(attributes))
    }

    private fun mediaCol(media: Media, margin: Int) = ippCollection {
        collection("media-size", ippCollection {
            integer("x-dimension", media.width)
            integer("y-dimension", media.height)
        })
        integer("media-bottom-margin", margin)
        integer("media-left-margin", margin)
        integer("media-right-margin", margin)
        integer("media-top-margin", margin)
        keyword("media-source", "auto")
        keyword("media-type", "stationery")
    }

    private fun printerAttributes(host: String): IppGroup {
        val snapshot = Snapshot()
        val processing = synchronized(jobs) { jobs.any { it.state == JOB_PROCESSING } }
        val queued = synchronized(jobs) { jobs.count { !it.isFinished } }
        val deviceId = snapshot.known?.deviceId
        val manufacturer = deviceId?.let { extractIeee1284Value(it, "MFG:") } ?: "Unknown"
        val model = deviceId?.let { extractIeee1284Value(it, "MDL:") } ?: snapshot.name
        val defaultDpi = snapshot.settings.resolveDpi(snapshot.language)

        return ippGroup(IppTag.PRINTER) {
            uri("printer-uri-supported", printerUri(host))
            keyword("uri-security-supported", "none")
            keyword("uri-authentication-supported", "none")
            name("printer-name", snapshot.name)
            name("printer-dns-sd-name", snapshot.name)
            text("printer-info", snapshot.name)
            text("printer-make-and-model", snapshot.name)
            text("printer-location", "Shared by ${Build.MODEL}")
            text("printer-device-id", "MFG:$manufacturer;MDL:$model;CMD:PDF,PWGRaster,URF;")
            uri("printer-uuid", "urn:uuid:$uuid")
            uri("printer-more-info", "http://$host/")
            keyword("printer-kind", "document")

            when {
                !snapshot.connected -> {
                    enum("printer-state", PRINTER_STOPPED)
                    keyword("printer-state-reasons", "offline-report")
                    text("printer-state-message", "The printer isn't connected to the phone")
                }
                processing -> {
                    enum("printer-state", PRINTER_PROCESSING)
                    keyword("printer-state-reasons", "none")
                }
                else -> {
                    enum("printer-state", PRINTER_IDLE)
                    keyword("printer-state-reasons", "none")
                }
            }
            boolean("printer-is-accepting-jobs", snapshot.connected)
            integer("printer-up-time", upTime())
            integer("queued-job-count", queued)

            keyword("ipp-versions-supported", "1.1", "2.0")
            enum("operations-supported", *OPERATIONS)
            charset("charset-configured", "utf-8")
            charset("charset-supported", "utf-8")
            language("natural-language-configured", "en")
            language("generated-natural-language-supported", "en")
            mimeType("document-format-supported", FORMAT_OCTET_STREAM, *DOCUMENT_FORMATS.toTypedArray())
            mimeType("document-format-default", FORMAT_OCTET_STREAM)
            keyword("pdl-override-supported", "attempted")
            keyword("compression-supported", "none")
            boolean("multiple-document-jobs-supported", false)
            integer("multiple-operation-time-out", 60)
            keyword("which-jobs-supported", "completed", "not-completed", "all")
            keyword("printer-get-attributes-supported", "document-format")
            keyword("identify-actions-supported", "display")
            keyword("identify-actions-default", "display")
            keyword(
                "job-creation-attributes-supported",
                "copies", "media", "media-col", "print-color-mode", "printer-resolution", "print-quality", "sides",
                "orientation-requested", "print-scaling",
            )

            boolean("color-supported", snapshot.color)
            range("copies-supported", 1, 99)
            integer("copies-default", 1)
            keyword("media-supported", *MEDIA.map { it.keyword }.toTypedArray())
            keyword("media-default", snapshot.defaultMedia.keyword)
            keyword("media-ready", snapshot.defaultMedia.keyword)
            collection("media-col-database", *MEDIA.map { mediaCol(it, snapshot.margin) }.toTypedArray())
            collection("media-col-default", mediaCol(snapshot.defaultMedia, snapshot.margin))
            collection("media-col-ready", mediaCol(snapshot.defaultMedia, snapshot.margin))
            keyword(
                "media-col-supported",
                "media-size", "media-bottom-margin", "media-left-margin", "media-right-margin", "media-top-margin",
                "media-source", "media-type",
            )
            integer("media-bottom-margin-supported", snapshot.margin)
            integer("media-left-margin-supported", snapshot.margin)
            integer("media-right-margin-supported", snapshot.margin)
            integer("media-top-margin-supported", snapshot.margin)
            keyword("media-source-supported", "auto")
            keyword("media-type-supported", "stationery")
            keyword("sides-supported", "one-sided")
            keyword("sides-default", "one-sided")
            if (snapshot.color) {
                keyword("print-color-mode-supported", "auto", "color", "monochrome")
                keyword("print-color-mode-default", "color")
            } else {
                keyword("print-color-mode-supported", "auto", "monochrome")
                keyword("print-color-mode-default", "monochrome")
            }
            resolution("printer-resolution-supported", *PrinterSettings.SUPPORTED_DPI.toIntArray())
            resolution("printer-resolution-default", defaultDpi)
            enum("print-quality-supported", 3, 4, 5)
            enum("print-quality-default", 4)
            enum("orientation-requested-supported", 3, 4, 5, 6)
            enum("orientation-requested-default", 3)
            enum("finishings-supported", 3)
            enum("finishings-default", 3)
            keyword("output-bin-supported", "face-down")
            keyword("output-bin-default", "face-down")
            keyword("print-scaling-supported", "auto", "fit")
            keyword("print-scaling-default", "fit")
            integer("number-up-supported", 1)
            integer("number-up-default", 1)
            boolean("page-ranges-supported", false)

            resolution("pwg-raster-document-resolution-supported", RASTER_DPI)
            keyword("pwg-raster-document-type-supported", "black_1", "sgray_8", "srgb_8")
            keyword("pwg-raster-document-sheet-back", "normal")
            keyword("urf-supported", *URF_SUPPORTED.toTypedArray())
        }
    }

    private fun jobGroup(job: IppJob, host: String, full: Boolean) = ippGroup(IppTag.JOB) {
        integer("job-id", job.id)
        uri("job-uri", "${printerUri(host)}/${job.id}")
        enum("job-state", job.state)
        keyword("job-state-reasons", job.reasons)
        if (!full) return@ippGroup
        uri("job-printer-uri", printerUri(host))
        name("job-name", job.name)
        name("job-originating-user-name", job.user)
        job.message?.let { text("job-state-message", it) }
        integer("time-at-creation", upTime(job.createdAt))
        if (job.processingAt > 0) integer("time-at-processing", upTime(job.processingAt))
        else add("time-at-processing", listOf(IppValue.RawValue(IppTag.NO_VALUE, ByteArray(0))))
        if (job.completedAt > 0) integer("time-at-completed", upTime(job.completedAt))
        else add("time-at-completed", listOf(IppValue.RawValue(IppTag.NO_VALUE, ByteArray(0))))
        integer("job-printer-up-time", upTime())
        integer("copies", job.ticket.copies)
        mimeType("document-format", job.format)
    }

    // endregion

    // region Operations

    /** @return an error response if the job can't be accepted, or null. */
    private fun validateJob(request: IppMessage): IppMessage? {
        val format = request.operationAttribute("document-format")?.string ?: FORMAT_OCTET_STREAM
        if (format != FORMAT_OCTET_STREAM && format !in DOCUMENT_FORMATS) {
            return response(request, STATUS_DOCUMENT_FORMAT_NOT_SUPPORTED, message = "Unsupported format $format")
        }
        if (!Snapshot().connected) {
            return response(request, STATUS_NOT_ACCEPTING_JOBS, message = "The printer isn't connected")
        }
        return null
    }

    private fun printJob(request: IppMessage, body: InputStream, host: String): IppMessage {
        validateJob(request)?.let { return it }
        val job = newJob(request)
        val format = request.operationAttribute("document-format")?.string ?: FORMAT_OCTET_STREAM
        synchronized(job) {
            if (!receiveDocument(job, body, format)) {
                finish(job, JOB_ABORTED, "aborted-by-system", "The document is empty")
                return response(request, STATUS_BAD_REQUEST, message = "The document is empty")
            }
            submit(job)
        }
        return response(request, STATUS_OK, listOf(jobGroup(job, host, full = false)))
    }

    private fun createJob(request: IppMessage, host: String): IppMessage {
        validateJob(request)?.let { return it }
        val job = newJob(request)
        job.reasons = "job-incoming"
        return response(request, STATUS_OK, listOf(jobGroup(job, host, full = false)))
    }

    private fun sendDocument(request: IppMessage, body: InputStream, host: String): IppMessage {
        val job = findJob(request) ?: return response(request, STATUS_NOT_FOUND, message = "No such job")
        val lastDocument = request.operationAttribute("last-document")?.boolean ?: false
        val format = request.operationAttribute("document-format")?.string ?: FORMAT_OCTET_STREAM
        if (format != FORMAT_OCTET_STREAM && format !in DOCUMENT_FORMATS) {
            return response(request, STATUS_DOCUMENT_FORMAT_NOT_SUPPORTED, message = "Unsupported format $format")
        }
        synchronized(job) {
            if (job.submitted || job.isFinished) {
                return response(request, STATUS_NOT_POSSIBLE, message = "The job already has its document")
            }
            if (job.document != null) {
                // We only support one document per job, but clients may send an empty one to close the job
                if (body.read() != -1) {
                    return response(request, STATUS_NOT_POSSIBLE, message = "Only one document per job is supported")
                }
            } else {
                receiveDocument(job, body, format)
            }
            if (lastDocument) {
                if (job.document == null) {
                    finish(job, JOB_ABORTED, "aborted-by-system", "No document was sent")
                } else {
                    submit(job)
                }
            }
        }
        return response(request, STATUS_OK, listOf(jobGroup(job, host, full = false)))
    }

    private fun closeJob(request: IppMessage, host: String): IppMessage {
        val job = findJob(request) ?: return response(request, STATUS_NOT_FOUND, message = "No such job")
        synchronized(job) {
            if (!job.submitted && !job.isFinished) {
                if (job.document == null) finish(job, JOB_ABORTED, "aborted-by-system", "No document was sent")
                else submit(job)
            }
        }
        return response(request, STATUS_OK, listOf(jobGroup(job, host, full = false)))
    }

    private fun cancelJob(request: IppMessage): IppMessage {
        val job = findJob(request) ?: return response(request, STATUS_NOT_FOUND, message = "No such job")
        if (job.isFinished) return response(request, STATUS_NOT_POSSIBLE, message = "The job has already finished")
        cancel(job)
        return response(request, STATUS_OK)
    }

    private fun getJobAttributes(request: IppMessage, host: String): IppMessage {
        val job = findJob(request) ?: return response(request, STATUS_NOT_FOUND, message = "No such job")
        return response(request, STATUS_OK, listOf(jobGroup(job, host, full = true)))
    }

    private fun getJobs(request: IppMessage, host: String): IppMessage {
        val which = request.operationAttribute("which-jobs")?.string ?: "not-completed"
        val limit = request.operationAttribute("limit")?.int?.takeIf { it > 0 } ?: Int.MAX_VALUE
        val requested = request.operationAttribute("requested-attributes")?.strings.orEmpty()
        val full = requested.any { it != "job-id" && it != "job-uri" }
        val selected = synchronized(jobs) {
            jobs.filter {
                when (which) {
                    "completed" -> it.isFinished
                    "all" -> true
                    else -> !it.isFinished
                }
            }.reversed().take(limit)
        }
        return response(request, STATUS_OK, selected.map { jobGroup(it, host, full) })
    }

    // endregion

    // region Jobs

    private fun newJob(request: IppMessage): IppJob {
        val jobAttributes = request.group(IppTag.JOB)
        fun attribute(name: String) = jobAttributes?.get(name) ?: request.operationAttribute(name)

        val media = attribute("media")?.string?.let { keyword -> MEDIA.firstOrNull { it.keyword == keyword } }
            ?: attribute("media-col")?.collection?.member("media-size")?.collection?.let { size ->
                val width = size.member("x-dimension")?.int ?: return@let null
                val height = size.member("y-dimension")?.int ?: return@let null
                MEDIA.firstOrNull { abs(it.width - width) < 100 && abs(it.height - height) < 100 }
            }
        val color = when (attribute("print-color-mode")?.string) {
            "color" -> true
            "monochrome", "process-monochrome", "bi-level", "process-bi-level" -> false
            else -> null
        }
        val dpi = (attribute("printer-resolution")?.values?.firstOrNull() as? IppValue.ResolutionValue)?.x
        val ticket = JobTicket(attribute("copies")?.int?.coerceIn(1, 99) ?: 1, media, color, dpi)

        val name = request.operationAttribute("job-name")?.string
            ?: request.operationAttribute("document-name")?.string
            ?: "Untitled"
        val user = request.operationAttribute("requesting-user-name")?.string ?: "anonymous"
        val job = IppJob(nextJobId.getAndIncrement(), name, user, ticket)

        synchronized(jobs) {
            jobs += job
            // Forget the oldest finished jobs
            while (jobs.size > MAX_JOBS) {
                val oldest = jobs.firstOrNull { it.isFinished } ?: break
                jobs.remove(oldest)
            }
        }
        return job
    }

    private fun findJob(request: IppMessage): IppJob? {
        val id = request.operationAttribute("job-id")?.int
            ?: request.operationAttribute("job-uri")?.string?.substringAfterLast('/')?.toIntOrNull()
            ?: return null
        return synchronized(jobs) { jobs.firstOrNull { it.id == id } }
    }

    /**
     * Stores the document from [body] in a file. Must hold the job's lock.
     * @return false if the document is empty.
     * @throws IOException if reading fails or the document is too large.
     */
    private fun receiveDocument(job: IppJob, body: InputStream, format: String): Boolean {
        val file = File.createTempFile("ipp", ".doc", context.cacheDir)
        try {
            var total = 0L
            file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = body.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_DOCUMENT_BYTES) throw IOException("The document is too large")
                    out.write(buffer, 0, read)
                }
            }
            if (total == 0L) {
                file.delete()
                return false
            }
        } catch (e: IOException) {
            file.delete()
            finish(job, JOB_ABORTED, "aborted-by-system", "The document couldn't be received")
            throw e
        }
        job.document = file
        job.format = format
        return true
    }

    /** Queues the job for printing. Must hold the job's lock. */
    private fun submit(job: IppJob) {
        job.submitted = true
        job.reasons = "none"
        jobExecutor.execute { process(job) }
    }

    private fun cancel(job: IppJob) {
        synchronized(job) {
            if (job.isFinished) return
            if (job.submitted) {
                // The job thread notices between pages and transfers
                job.cancelRequested = true
            } else {
                finish(job, JOB_CANCELED, "job-canceled-by-user")
            }
        }
    }

    private fun finish(job: IppJob, state: Int, reasons: String, message: String? = null) {
        job.state = state
        job.reasons = reasons
        job.message = message
        job.completedAt = SystemClock.elapsedRealtime()
        synchronized(job) {
            job.document?.delete()
            job.document = null
        }
    }

    private fun onMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    private fun process(job: IppJob) {
        if (job.cancelRequested || job.isFinished) {
            if (!job.isFinished) finish(job, JOB_CANCELED, "job-canceled-by-user")
            return
        }
        job.state = JOB_PROCESSING
        job.reasons = "job-printing"
        job.processingAt = SystemClock.elapsedRealtime()

        val printerName = name
        onMain { notifier.showProgress(job.notificationId, job.name, printerName, 0f, force = true) }

        // Converting and sending can take a while, and the screen is probably off
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenPrinterProxy:ipp-job")
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)

        var converted: File? = null
        try {
            val device = findPrinter(usbManager, localId)?.takeIf(usbManager::hasPermission)
                ?: throw PrintException("The printer isn't connected")
            val document = synchronized(job) { job.document } ?: throw PrintException("The job has no document")
            val format = job.format.takeIf { it != FORMAT_OCTET_STREAM }
                ?: RasterToPdf.detectFormat(document)
                ?: throw PrintException("Unsupported document format")

            val pdf = if (format == FORMAT_PDF) {
                document
            } else {
                File.createTempFile("ipp", ".pdf", context.cacheDir).also {
                    converted = it
                    RasterToPdf.convert(document, format, it) { job.cancelRequested }
                }
            }

            val snapshot = Snapshot()
            val dpi = job.ticket.dpi ?: snapshot.settings.resolveDpi(snapshot.language)
            val attributes = PrintAttributes.Builder()
                .setMediaSize(job.ticket.media?.size ?: snapshot.defaultMedia.size)
                .setResolution(PrintAttributes.Resolution("ipp", "$dpi dpi", dpi, dpi))
                .setColorMode(
                    if (job.ticket.color ?: snapshot.color) PrintAttributes.COLOR_MODE_COLOR
                    else PrintAttributes.COLOR_MODE_MONOCHROME
                )
                .setMinMargins(snapshot.settings.margins())
                .build()

            var lastPercent = -1
            val runner = UsbPrintJobRunner(
                usbManager,
                context.cacheDir,
                isCancelled = { job.cancelRequested },
                onProgress = { progress ->
                    val percent = (progress * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onMain { notifier.showProgress(job.notificationId, job.name, printerName, progress) }
                    }
                },
            )
            runner.printPdf(device, pdf, attributes, job.ticket.copies, job.name, snapshot.settings)

            finish(job, JOB_COMPLETED, "job-completed-successfully")
            onMain { notifier.showCompleted(job.notificationId, job.name, printerName) }
        } catch (_: CancellationException) {
            finish(job, JOB_CANCELED, "job-canceled-by-user")
            onMain { notifier.dismiss(job.notificationId) }
        } catch (e: Exception) {
            val message = if (e is PrintException) {
                e.message.orEmpty()
            } else {
                Log.e(TAG, "Network job ${job.id} failed", e)
                "Printing failed: ${e.message ?: e.javaClass.simpleName}"
            }
            finish(job, JOB_ABORTED, "aborted-by-system", message)
            onMain { notifier.showFailed(job.notificationId, job.name, printerName, message) }
        } finally {
            converted?.delete()
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    // endregion
}
