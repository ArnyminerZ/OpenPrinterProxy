package com.arnyminerz.openprinterproxy.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintAttributes.Resolution
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.arnyminerz.openprinterproxy.OtgUtils.connectedPrinters
import com.arnyminerz.openprinterproxy.OtgUtils.extractIeee1284Value
import com.arnyminerz.openprinterproxy.OtgUtils.localPrinterId
import com.arnyminerz.openprinterproxy.OtgUtils.readDeviceId
import com.arnyminerz.openprinterproxy.OtgUtils.withPrinterInterface
import com.arnyminerz.openprinterproxy.activity.SettingsActivity
import com.arnyminerz.openprinterproxy.settings.PrinterSettings
import com.arnyminerz.openprinterproxy.settings.PrinterSettingsRepository
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class UsbPrinterDiscoverySession(private val printService: PrintService) : PrinterDiscoverySession() {
    companion object {
        private const val TAG = "UsbPrinterDiscovery"

        private fun usbPermissionAction(context: Context) = "${context.packageName}.USB_PERMISSION"

        /**
         * Tells a running discovery session that permission for [device] was granted outside of it (e.g. through
         * the USB attach prompt handled by UsbPrinterAttachedActivity).
         */
        fun notifyPermissionGranted(context: Context, device: UsbDevice) {
            context.sendBroadcast(
                Intent(usbPermissionAction(context))
                    .setPackage(context.packageName)
                    .putExtra(UsbManager.EXTRA_DEVICE, device)
                    .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true)
            )
        }
    }

    private val usbManager = printService.getSystemService(Context.USB_SERVICE) as UsbManager
    private val settingsRepository = PrinterSettingsRepository(printService)
    private val actionUsbPermission = usbPermissionAction(printService)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var receiverRegistered = false

    // Devices we already asked permission for during this session, so we don't spam the dialog
    private val permissionRequested = mutableSetOf<String>()

    // The id each connected device is currently published under, keyed by UsbDevice.deviceName. The id can change
    // once permission is granted (the serial number becomes readable), and on detach we need to know what to remove.
    private val publishedIds = mutableMapOf<String, PrinterId>()

    // Printers the print UI currently shows (the selected one), which need capabilities attached
    private val trackedIds = mutableSetOf<PrinterId>()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                // Don't request permission here: if the user accepts the system's attach prompt for
                // UsbPrinterAttachedActivity, permission is granted that way, and we'd show a second dialog.
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshPrinters(requestMissingPermissions = false)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null) {
                        permissionRequested.remove(device.deviceName)
                        publishedIds.remove(device.deviceName)?.let { removePrinters(listOf(it)) }
                    }
                }
                actionUsbPermission -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.d(TAG, "USB permission for ${device?.deviceName}: granted=$granted")
                    if (granted && device != null) publishPrinter(device, connectedPrinters())
                }
            }
        }
    }

    override fun onStartPrinterDiscovery(priorityList: List<PrinterId?>) {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(actionUsbPermission)
            }
            // The permission result is delivered through our own PendingIntent, so the receiver doesn't need to be
            // exported. System broadcasts (attach/detach) are still delivered.
            ContextCompat.registerReceiver(printService, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }

        refreshPrinters(requestMissingPermissions = true)
    }

    private fun connectedPrinters(): List<UsbDevice> = connectedPrinters(usbManager)

    private fun printerIdFor(device: UsbDevice, connected: Collection<UsbDevice>): PrinterId =
        printService.generatePrinterId(localPrinterId(usbManager, device, connected))

    private fun refreshPrinters(requestMissingPermissions: Boolean) {
        val devices = connectedPrinters()

        // Drop printers that are no longer connected
        val connectedNames = devices.map { it.deviceName }.toSet()
        val staleNames = publishedIds.keys.filterNot { it in connectedNames }
        if (staleNames.isNotEmpty()) removePrinters(staleNames.mapNotNull { publishedIds.remove(it) })

        for (device in devices) {
            if (usbManager.hasPermission(device)) {
                publishPrinter(device, devices)
            } else {
                // Show the printer right away with the info available without permission, and ask for access
                addPrinter(device, printerIdFor(device, devices), fallbackName(device), hasPermission = false)
                if (requestMissingPermissions) requestPermission(device)
            }
        }
    }

    /** Publishes the printer, replacing any entry previously published for the same device under a different id. */
    private fun addPrinter(device: UsbDevice, id: PrinterId, name: String, hasPermission: Boolean) {
        val previousId = publishedIds.put(device.deviceName, id)
        if (previousId != null && previousId != id) removePrinters(listOf(previousId))
        addPrinters(listOf(buildPrinterInfo(id, name, hasPermission)))
    }

    private fun requestPermission(device: UsbDevice) {
        if (!permissionRequested.add(device.deviceName)) return

        // The system fills in EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED, so the PendingIntent must be mutable
        // (API 31+), and mutable PendingIntents must be explicit (API 34+).
        val intent = Intent(actionUsbPermission).setPackage(printService.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(printService, 0, intent, flags)
        usbManager.requestPermission(device, pendingIntent)
    }

    /**
     * Reads the IEEE 1284 device ID on a background thread (it's blocking I/O with a timeout, and discovery callbacks
     * run on the main thread), then publishes the printer on the main thread.
     */
    private fun publishPrinter(device: UsbDevice, connected: Collection<UsbDevice>) {
        val id = printerIdFor(device, connected)
        ioExecutor.execute {
            val deviceId = withPrinterInterface(usbManager, device, ::readDeviceId)
            Log.d(TAG, "Device ID of ${device.deviceName}: $deviceId")
            val name = deviceId?.let(::nameFromDeviceId) ?: fallbackName(device)
            mainHandler.post {
                // The device may have been unplugged while we were reading from it
                if (isDestroyed || connectedPrinters().none { it.deviceName == device.deviceName }) return@post
                // So it shows up in the settings, even when it's disconnected
                settingsRepository.rememberPrinter(id.localId, name, deviceId)
                addPrinter(device, id, name, hasPermission = true)
            }
        }
    }

    private fun buildPrinterInfo(id: PrinterId, name: String, hasPermission: Boolean): PrinterInfo =
        PrinterInfo.Builder(
            id,
            name,
            if (hasPermission) PrinterInfo.STATUS_IDLE else PrinterInfo.STATUS_UNAVAILABLE
        ).apply {
            if (!hasPermission) setDescription("Waiting for USB permission")
            // Opens the printer's settings from the print dialog
            setInfoIntent(
                PendingIntent.getActivity(
                    printService,
                    id.localId.hashCode(),
                    SettingsActivity.intent(printService, id.localId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            // Keep the capabilities when re-publishing a tracked printer, or the print UI goes back to loading
            if (hasPermission && id in trackedIds) setCapabilities(buildCapabilities(id))
        }.build()

    private fun buildCapabilities(printerId: PrinterId): PrinterCapabilitiesInfo {
        val settings = settingsRepository.settings(printerId.localId)
        val language = settings.resolveLanguage(settingsRepository.knownPrinter(printerId.localId)?.deviceId)
        val defaultMediaSize = settings.resolveMediaSize(printService)
        val defaultDpi = settings.resolveDpi(language)

        return PrinterCapabilitiesInfo.Builder(printerId).apply {
            for (mediaSize in PrinterSettings.MEDIA_SIZES) addMediaSize(mediaSize, mediaSize == defaultMediaSize)
            for (dpi in PrinterSettings.SUPPORTED_DPI) {
                addResolution(Resolution("${dpi}dpi", "$dpi dpi", dpi, dpi), dpi == defaultDpi)
            }
            setMinMargins(settings.margins())
            if (settings.supportsColor(language)) {
                setColorModes(
                    PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME,
                    PrintAttributes.COLOR_MODE_COLOR
                )
            } else {
                setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
            }
            setDuplexModes(PrintAttributes.DUPLEX_MODE_NONE, PrintAttributes.DUPLEX_MODE_NONE)
        }.build()
    }

    private fun fallbackName(device: UsbDevice): String =
        device.productName
            ?: device.manufacturerName?.let { "$it Printer" }
            ?: "USB Printer (%04X:%04X)".format(device.vendorId, device.productId)

    /** @return "<MFG> <MDL>" (or just the model) from an IEEE 1284 device ID, or null if it has no model. */
    private fun nameFromDeviceId(deviceIdString: String): String? {
        val manufacturer = extractIeee1284Value(deviceIdString, "MFG:")
            ?: extractIeee1284Value(deviceIdString, "MANUFACTURER:")
        val model = extractIeee1284Value(deviceIdString, "MDL:")
            ?: extractIeee1284Value(deviceIdString, "MODEL:")

        return when {
            model == null -> null
            // Some printers already include the manufacturer in the model
            manufacturer != null && !model.startsWith(manufacturer, ignoreCase = true) -> "$manufacturer $model"
            else -> model
        }
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        printService.unregisterReceiver(usbReceiver)
        receiverRegistered = false
    }

    override fun onStopPrinterDiscovery() {
        // Unregister to prevent memory leaks when the UI closes
        unregisterReceiver()
    }

    // Other overrides will go here
    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}

    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        trackedIds += printerId

        // Printers from the print history that aren't connected right now stay unavailable
        val deviceName = publishedIds.entries.firstOrNull { it.value == printerId }?.key ?: return
        val device = usbManager.deviceList[deviceName] ?: return

        if (!usbManager.hasPermission(device)) {
            // The user picked this printer, so ask again even if they dismissed the dialog earlier. The printer is
            // re-published with capabilities once permission is granted.
            permissionRequested.remove(deviceName)
            requestPermission(device)
            return
        }

        val current = printers.firstOrNull { it.id == printerId } ?: return
        if (current.capabilities == null) {
            addPrinters(listOf(PrinterInfo.Builder(current).setCapabilities(buildCapabilities(printerId)).build()))
        }
    }

    override fun onStopPrinterStateTracking(printerId: PrinterId) {
        trackedIds -= printerId
    }

    override fun onDestroy() {
        unregisterReceiver()
        ioExecutor.shutdownNow()
    }
}
