package com.arnyminerz.openprinterproxy.viewmodel

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AddPrintersViewModel(application: Application) : AndroidViewModel(application) {
    val printers: StateFlow<List<UsbDevice>>
        field = MutableStateFlow(emptyList())

    fun listPrinters() {
        val usbManager = getApplication<Application>().getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList: HashMap<String, UsbDevice> = usbManager.deviceList

        val list = mutableListOf<UsbDevice>()

        for (device in deviceList.values) {
            // Check if any interface on the device is a printer
            for (i in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(i)
                if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                    list += device
                }
            }
        }
        printers.value = list
    }
}