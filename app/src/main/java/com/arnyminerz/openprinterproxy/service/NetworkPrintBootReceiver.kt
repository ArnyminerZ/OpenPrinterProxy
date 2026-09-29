package com.arnyminerz.openprinterproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Resumes network sharing after a reboot or an app update, if a shared printer is connected. */
class NetworkPrintBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            NetworkPrintService.update(context)
        }
    }
}
