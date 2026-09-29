package com.arnyminerz.openprinterproxy.service

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.text.htmlEncode
import com.arnyminerz.openprinterproxy.OtgUtils.findPrinter
import com.arnyminerz.openprinterproxy.R
import com.arnyminerz.openprinterproxy.activity.SettingsActivity
import com.arnyminerz.openprinterproxy.ipp.IppPrinter
import com.arnyminerz.openprinterproxy.ipp.IppServer
import com.arnyminerz.openprinterproxy.settings.PrinterSettingsRepository
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Shares the printers that have "Share on the network" enabled with the local network: runs an IPP server and
 * advertises each printer with DNS-SD (`_ipp._tcp`, plus the `_universal` subtype AirPrint clients look for), so
 * other devices find it as a driverless printer.
 *
 * It's a foreground service, and runs only while at least one shared printer is connected.
 */
class NetworkPrintService : Service() {
    companion object {
        private const val TAG = "NetworkPrintService"

        /** IPP's usual port 631 needs root, so we use the conventional alternative. */
        const val PORT = 8631

        private const val CHANNEL_ID = "network_sharing"

        // Job notifications count up from 1, so this doesn't collide with them
        private const val NOTIFICATION_ID = Int.MAX_VALUE

        private const val SERVICE_TYPE = "_ipp._tcp,_universal"

        /**
         * Starts the service if a shared printer is connected, or stops it otherwise. Must be called while the app
         * is in the foreground (or from another exempt context, like boot), since it may start a foreground service.
         */
        fun update(context: Context) {
            val intent = Intent(context, NetworkPrintService::class.java)
            if (hasLocalNetworkPermission(context) && sharedConnectedPrinters(context).isNotEmpty()) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(intent)
            }
        }

        fun hasLocalNetworkPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) ==
                PackageManager.PERMISSION_GRANTED

        /** Local ids of the printers that are shared, connected, and that we have permission to use. */
        private fun sharedConnectedPrinters(context: Context): List<String> {
            val repository = PrinterSettingsRepository(context)
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return repository.knownPrinters()
                .map { it.localId }
                .filter { localId ->
                    repository.settings(localId).shareOnNetwork &&
                        findPrinter(usbManager, localId)?.let(usbManager::hasPermission) == true
                }
        }

        /** Addresses of this device on local networks (Wi-Fi and Ethernet, not mobile data or VPNs). */
        fun localAddresses(context: Context): List<LinkAddress> {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION") // There's no replacement that lists every network synchronously
            return connectivity.allNetworks.flatMap { network ->
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return@flatMap emptyList()
                val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (local) connectivity.getLinkProperties(network)?.linkAddresses.orEmpty() else emptyList()
            }
        }

        /** The address other devices can add the printer with manually, or null when not on a local network. */
        fun printerUri(context: Context, localId: String): String? =
            localAddresses(context)
                .map { it.address }
                .firstOrNull { it is Inet4Address }
                ?.let { "ipp://${it.hostAddress}:$PORT${IppPrinter.pathFor(localId)}" }

        /** Whether [address] is inside [prefix]. */
        private fun contains(prefix: LinkAddress, address: InetAddress): Boolean {
            val network = prefix.address.address
            val bytes = address.address
            if (network.size != bytes.size) return false
            val bits = prefix.prefixLength
            for (i in 0 until bits / 8) if (network[i] != bytes[i]) return false
            val remainder = bits % 8
            if (remainder == 0) return true
            val mask = (0xFF shl (8 - remainder)) and 0xFF
            return ((network[bits / 8].toInt() xor bytes[bits / 8].toInt()) and mask) == 0
        }
    }

    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val nsdManager by lazy { getSystemService(Context.NSD_SERVICE) as NsdManager }
    private val repository by lazy { PrinterSettingsRepository(this) }
    private val notifier by lazy { PrintJobNotifier(this) }
    private val mainHandler = Handler(Looper.getMainLooper())

    // Network jobs are printed one at a time
    private val jobExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // By local id. Read from the server's connection threads.
    private val printers = ConcurrentHashMap<String, IppPrinter>()

    // DNS-SD registrations by local id. Main thread only.
    private val registrations = mutableMapOf<String, NsdManager.RegistrationListener>()

    private var server: IppServer? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val notificationId = intent.getIntExtra(PrintJobNotifier.EXTRA_NOTIFICATION_ID, -1)
            printers.values.any { it.cancelByNotification(notificationId) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Network sharing")
                .setDescription("Shown while printers are shared on the network")
                .build()
        )
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            cancelReceiver,
            IntentFilter(PrintJobNotifier.cancelAction(this)),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Keep Wi-Fi from sleeping while the screen is off, or the printers become unreachable
        @Suppress("DEPRECATION") // LOW_LATENCY only works while the app is in the foreground
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "OpenPrinterProxy:network-sharing")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    /** Starts the foreground service, or updates its notification if it's already running. */
    private fun startForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()
        if (!hasLocalNetworkPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (server == null) {
            try {
                server = IppServer(PORT, ::isAllowed, ::printerForPath, ::statusPage).also { it.start() }
            } catch (e: IOException) {
                Log.e(TAG, "Couldn't start the IPP server on port $PORT", e)
                stopSelf()
                return START_NOT_STICKY
            } catch (e: SecurityException) {
                Log.w(TAG, "Local network access was denied", e)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        refresh()
        return START_STICKY
    }

    override fun onDestroy() {
        registrations.keys.toList().forEach(::unregister)
        server?.stop()
        server = null
        // Let a job that's already printing finish
        jobExecutor.shutdown()
        unregisterReceiver(usbReceiver)
        unregisterReceiver(cancelReceiver)
        wifiLock?.release()
        super.onDestroy()
    }

    /** Updates the shared printers and their DNS-SD registrations; stops the service if there are none. */
    private fun refresh() {
        if (!hasLocalNetworkPermission(this)) {
            stopSelf()
            return
        }
        val shared = sharedConnectedPrinters(this)
        if (shared.isEmpty()) {
            Log.i(TAG, "No shared printer is connected, stopping")
            stopSelf()
            return
        }

        // Printers that are no longer shared go away; disconnected ones stay, so clients can see their jobs
        printers.keys.removeAll { !repository.settings(it).shareOnNetwork }
        for (localId in registrations.keys - shared.toSet()) unregister(localId)
        for (localId in shared) {
            printers.getOrPut(localId) { IppPrinter(this, localId, jobExecutor, notifier) }
            if (localId !in registrations) {
                try {
                    register(localId)
                } catch (e: SecurityException) {
                    registrations.remove(localId)
                    Log.w(TAG, "Local network access was denied while advertising a printer", e)
                    stopSelf()
                    return
                }
            }
        }

        startForeground()
    }

    private fun register(localId: String) {
        val printer = printers[localId] ?: return
        val host = localAddresses(this).map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress
        val info = NsdServiceInfo().apply {
            // DNS-SD instance names are at most 63 bytes. Conflicts get renamed automatically.
            serviceName = printer.name.take(63)
            serviceType = SERVICE_TYPE
            port = PORT
            for ((key, value) in printer.txtRecord(PORT, host)) setAttribute(key, value)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Advertising ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Couldn't advertise ${info.serviceName}: error $errorCode")
                mainHandler.post { if (registrations[localId] === this) registrations.remove(localId) }
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrations[localId] = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun unregister(localId: String) {
        val listener = registrations.remove(localId) ?: return
        try {
            nsdManager.unregisterService(listener)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Registration of $localId was already gone", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Local network access was denied while removing $localId", e)
        }
    }

    /** Only devices on the same local network may print, since there's no authentication. */
    private fun isAllowed(address: InetAddress): Boolean =
        address.isLoopbackAddress || localAddresses(this).any { contains(it, address) }

    private fun printerForPath(path: String): IppPrinter? {
        val path = path.trimEnd('/')
        return printers.values.firstOrNull { it.path == path }
            // The default IPP Everywhere path, when there's only one printer
            ?: printers.values.singleOrNull()?.takeIf { path == "/ipp/print" }
    }

    private fun statusPage(): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width\"><title>OpenPrinterProxy</title></head><body>")
        append("<h1>OpenPrinterProxy</h1><p>Printers shared by ${Build.MODEL.htmlEncode()}:</p><ul>")
        for (printer in printers.values) {
            append("<li>${printer.name.htmlEncode()}: <code>${printer.path.htmlEncode()}</code></li>")
        }
        append("</ul></body></html>")
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_print)
        .setContentTitle("Sharing printers on the network")
        .setContentText(
            buildList {
                addAll(printers.values.map { it.name })
                localAddresses(this@NetworkPrintService)
                    .map { it.address }
                    .firstOrNull { it is Inet4Address }
                    ?.let { add("${it.hostAddress}:$PORT") }
            }.joinToString(" · ")
        )
        .setOngoing(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, SettingsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()
}
