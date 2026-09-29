package com.arnyminerz.openprinterproxy

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

object OtgUtils {
    private const val TAG = "OtgUtils"

    // 0xA1 = USB_DIR_IN (0x80) | USB_TYPE_CLASS (0x20) | USB_RECIP_INTERFACE (0x01)
    private const val REQUEST_TYPE_CLASS_INTERFACE_IN = 0xA1
    private const val REQUEST_GET_DEVICE_ID = 0x00
    private const val GET_DEVICE_ID_TIMEOUT_MS = 5000

    /**
     * Held while talking to a printer, so that discovery (reading the device ID) and a print job don't claim the
     * same interface at the same time.
     */
    val usbIoLock = Any()

    fun isPrinter(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                return true
            }
        }
        return false
    }

    fun connectedPrinters(usbManager: UsbManager): List<UsbDevice> = usbManager.deviceList.values.filter(::isPrinter)

    /** Finds the connected printer whose [localPrinterId] is [localId]. */
    fun findPrinter(usbManager: UsbManager, localId: String): UsbDevice? {
        val connected = connectedPrinters(usbManager)
        return connected.firstOrNull { localPrinterId(usbManager, it, connected) == localId }
    }

    fun findPrinterInterface(device: UsbDevice): UsbInterface? =
        (0 until device.interfaceCount)
            .map(device::getInterface)
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_PRINTER }

    /**
     * Builds a printer id that stays the same across reconnections, so the print framework keeps its history and
     * settings for the printer. `deviceName` (/dev/bus/usb/BBB/DDD) changes on every replug, so it's only used to
     * tell apart identical printers that don't expose a serial number.
     * @param connected All the printers currently connected.
     */
    fun localPrinterId(usbManager: UsbManager, device: UsbDevice, connected: Collection<UsbDevice>): String {
        // Reading the serial number requires permission on API 29+, and throws SecurityException otherwise
        val serial = if (usbManager.hasPermission(device)) {
            runCatching { device.serialNumber }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        } else null
        val base = "usb:%04x:%04x".format(device.vendorId, device.productId)
        val hasTwin = connected.count { it.vendorId == device.vendorId && it.productId == device.productId } > 1
        return when {
            serial != null -> "$base:$serial"
            hasTwin -> "$base@${device.deviceName}"
            else -> base
        }
    }

    /**
     * Queries the printer with the USB Printer Class GET_DEVICE_ID request, on an open [connection] whose
     * [printerInterface] is already claimed. Blocking.
     * @return The IEEE 1284 device ID, e.g. "MFG:Brother;CMD:GDI;MDL:DCP-1510;CLS:PRINTER;", or null on failure.
     */
    fun readDeviceId(connection: UsbDeviceConnection, printerInterface: UsbInterface): String? {
        val buffer = ByteArray(1024)
        val bytesRead = connection.controlTransfer(
            REQUEST_TYPE_CLASS_INTERFACE_IN,
            REQUEST_GET_DEVICE_ID,
            0, // Configuration index
            // Printer class spec: high byte = interface number, low byte = alternate setting
            (printerInterface.id shl 8) or printerInterface.alternateSetting,
            buffer,
            buffer.size,
            GET_DEVICE_ID_TIMEOUT_MS
        )
        if (bytesRead <= 2) {
            Log.w(TAG, "GET_DEVICE_ID failed (result=$bytesRead)")
            return null
        }

        // The first two bytes are the big-endian length of the whole ID, including those two bytes
        val declaredLength = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
        val length = declaredLength.coerceIn(2, bytesRead)
        return String(buffer, 2, length - 2, Charsets.US_ASCII)
    }

    /**
     * Opens [device], claims its printer interface and runs [block] while holding [usbIoLock]. Blocking.
     * @return The result of [block], or null if the device couldn't be opened or claimed.
     */
    fun <T> withPrinterInterface(
        usbManager: UsbManager,
        device: UsbDevice,
        block: (UsbDeviceConnection, UsbInterface) -> T
    ): T? = synchronized(usbIoLock) {
        val printerInterface = findPrinterInterface(device) ?: return null
        val connection = usbManager.openDevice(device) ?: run {
            Log.w(TAG, "Could not open ${device.deviceName}")
            return null
        }
        try {
            if (!connection.claimInterface(printerInterface, true)) {
                Log.w(TAG, "Could not claim printer interface of ${device.deviceName}")
                return null
            }
            try {
                block(connection, printerInterface)
            } finally {
                connection.releaseInterface(printerInterface)
            }
        } finally {
            connection.close()
        }
    }

    // Helper function to extract keys from the semicolon-separated string
    fun extractIeee1284Value(deviceIdString: String, key: String): String? {
        val upperString = deviceIdString.uppercase()
        val startIndex = upperString.indexOf(key)

        if (startIndex == -1) return null

        val valueStart = startIndex + key.length
        val endIndex = upperString.indexOf(";", valueStart)

        return if (endIndex != -1) {
            deviceIdString.substring(valueStart, endIndex).trim()
        } else {
            deviceIdString.substring(valueStart).trim()
        }
    }
}
