package com.arnyminerz.openprinterproxy.activity

import android.app.Activity
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.core.content.IntentCompat
import com.arnyminerz.openprinterproxy.service.NetworkPrintService
import com.arnyminerz.openprinterproxy.service.UsbPrinterDiscoverySession

/**
 * Invisible activity launched by the system when a USB printer is attached.
 *
 * When the user accepts the "Open Open Printer Proxy to handle …" prompt, the system grants this app permission to
 * access the device (permanently if "Always open" / "Use by default" is ticked), so the print service doesn't have to
 * request it. We just notify any running discovery session and finish.
 */
class UsbPrinterAttachedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            if (device != null) UsbPrinterDiscoverySession.notifyPermissionGranted(this, device)
            // Resume sharing it on the network. This activity is in the foreground, so it may start the service.
            NetworkPrintService.update(this)
        }

        finish()
    }
}
