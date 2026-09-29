package com.arnyminerz.openprinterproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.print.PrintJobId
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import androidx.core.content.ContextCompat
import com.arnyminerz.openprinterproxy.OtgUtils.findPrinter
import com.arnyminerz.openprinterproxy.printing.PrintException
import com.arnyminerz.openprinterproxy.printing.UsbPrintJobRunner
import com.arnyminerz.openprinterproxy.settings.PrinterSettingsRepository
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CustomPrintService : PrintService() {
    private companion object {
        const val TAG = "CustomPrintService"
    }

    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }

    private val settingsRepository by lazy { PrinterSettingsRepository(this) }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Jobs are sent one at a time, in the order they were queued
    private val jobExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Jobs the user cancelled while they were waiting or printing. Read from the job thread.
    private val cancelRequested: MutableSet<PrintJobId> = ConcurrentHashMap.newKeySet()

    private val notifier by lazy { PrintJobNotifier(this) }

    // Jobs with a progress notification, by notification id, so its cancel action can find them
    private val notifiedJobs = mutableMapOf<Int, PrintJob>()

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val notificationId = intent.getIntExtra(PrintJobNotifier.EXTRA_NOTIFICATION_ID, -1)
            notifiedJobs[notificationId]?.let(::onRequestCancelPrintJob)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            cancelReceiver,
            IntentFilter(PrintJobNotifier.cancelAction(this)),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return UsbPrinterDiscoverySession(this)
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        // PrintJob may only be used on the main thread, so take everything the job thread needs here
        val info = printJob.info
        val document = printJob.document.data ?: run {
            printJob.fail("The document to print is missing")
            return
        }
        val device = info.printerId?.let { findPrinter(usbManager, it.localId) }
        if (device == null || !usbManager.hasPermission(device)) {
            document.close()
            printJob.fail(
                if (device == null) "The printer isn't connected"
                else "No permission to use the printer. Select it in the print dialog to grant it."
            )
            return
        }

        val localId = info.printerId!!.localId
        val settings = settingsRepository.settings(localId)
        val jobName = info.label
        val printerName = settingsRepository.knownPrinter(localId)?.name ?: device.productName ?: "USB printer"

        printJob.start()
        val jobId = printJob.id
        val notificationId = PrintJobNotifier.newNotificationId()
        notifiedJobs[notificationId] = printJob
        notifier.showProgress(notificationId, jobName, printerName, 0f, force = true)

        val runner = UsbPrintJobRunner(
            usbManager,
            cacheDir,
            isCancelled = { jobId in cancelRequested },
            onProgress = throttledProgress(printJob) { progress ->
                notifier.showProgress(notificationId, jobName, printerName, progress)
            },
        )

        jobExecutor.execute {
            val result = runCatching {
                if (jobId in cancelRequested) {
                    document.close()
                    throw CancellationException()
                }
                runner.print(device, document, info.attributes, info.copies.coerceAtLeast(1), jobName, settings)
            }
            mainHandler.post {
                cancelRequested.remove(jobId)
                notifiedJobs.remove(notificationId)
                result.onSuccess {
                    printJob.complete()
                    notifier.showCompleted(notificationId, jobName, printerName)
                }.onFailure { error ->
                    if (error is CancellationException) {
                        printJob.cancel()
                        notifier.dismiss(notificationId)
                        return@onFailure
                    }
                    val message = if (error is PrintException) {
                        error.message.orEmpty()
                    } else {
                        Log.e(TAG, "Print job $jobId failed", error)
                        "Printing failed: ${error.message ?: error.javaClass.simpleName}"
                    }
                    printJob.fail(message)
                    notifier.showFailed(notificationId, jobName, printerName, message)
                }
            }
        }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        if (printJob.isQueued) {
            printJob.cancel()
        } else {
            // The job thread notices this between pages and transfers, and cancels the job
            cancelRequested += printJob.id
        }
    }

    override fun onDestroy() {
        unregisterReceiver(cancelReceiver)
        notifiedJobs.keys.forEach(notifier::dismiss)
        jobExecutor.shutdownNow()
        super.onDestroy()
    }

    /**
     * Posts progress updates to the main thread, at most once per percent, since they arrive for every chunk sent.
     * @param onUpdate Also called on the main thread with each update, while the job is running.
     */
    private fun throttledProgress(printJob: PrintJob, onUpdate: (Float) -> Unit): (Float) -> Unit {
        var lastPercent = -1
        return { progress ->
            val percent = (progress * 100).toInt()
            if (percent != lastPercent) {
                lastPercent = percent
                mainHandler.post {
                    if (printJob.isStarted) {
                        printJob.setProgress(progress)
                        onUpdate(progress)
                    }
                }
            }
        }
    }
}
