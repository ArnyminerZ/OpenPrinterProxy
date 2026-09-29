package com.arnyminerz.openprinterproxy.activity

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.arnyminerz.openprinterproxy.printing.PrinterLanguage
import com.arnyminerz.openprinterproxy.service.NetworkPrintService
import com.arnyminerz.openprinterproxy.settings.ColorSetting
import com.arnyminerz.openprinterproxy.settings.KnownPrinter
import com.arnyminerz.openprinterproxy.settings.PrinterSettings
import com.arnyminerz.openprinterproxy.settings.PrinterSettingsRepository
import com.arnyminerz.openprinterproxy.ui.icon.Icons
import com.arnyminerz.openprinterproxy.ui.icon.arrowBack
import com.arnyminerz.openprinterproxy.ui.theme.AppTheme
import java.util.Locale

/**
 * Lists the printers that have been connected, and edits their [PrinterSettings].
 *
 * Opened from the system print settings, from the app icon, or from a printer's info button in the print dialog
 * (with [EXTRA_PRINTER_ID], which opens that printer directly).
 */
class SettingsActivity : ComponentActivity() {
    companion object {
        /** The local id of the printer to open. */
        const val EXTRA_PRINTER_ID = "printer_id"

        fun intent(context: Context, localPrinterId: String): Intent =
            Intent(context, SettingsActivity::class.java)
                .putExtra(EXTRA_PRINTER_ID, localPrinterId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val repository by lazy { PrinterSettingsRepository(this) }

    private var hasNetworkPermission by mutableStateOf(false)

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshNetworkSharing()
        }

    private fun refreshNetworkSharing() {
        hasNetworkPermission = NetworkPrintService.hasLocalNetworkPermission(this)
        NetworkPrintService.update(this)
    }

    override fun onResume() {
        super.onResume()
        // Also pick up permissions changed in system settings.
        refreshNetworkSharing()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        hasNetworkPermission = NetworkPrintService.hasLocalNetworkPermission(this)
        // Services cannot prompt. Request together to avoid overlapping permission dialogs.
        if (savedInstanceState == null) {
            val permissions = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this@SettingsActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && !hasNetworkPermission) {
                    add(Manifest.permission.ACCESS_LOCAL_NETWORK)
                }
            }
            if (permissions.isNotEmpty()) requestPermissions.launch(permissions.toTypedArray())
        }

        val directPrinterId = intent.getStringExtra(EXTRA_PRINTER_ID)

        setContent {
            AppTheme {
                var printers by remember { mutableStateOf(repository.knownPrinters()) }
                var selectedId by rememberSaveable { mutableStateOf(directPrinterId) }
                val selected = selectedId?.let { id -> printers.firstOrNull { it.localId == id } }

                if (selected == null) {
                    PrinterListScreen(printers, onBack = ::finish, onSelect = { selectedId = it.localId })
                } else {
                    // When opened for a specific printer, going back returns to the print dialog
                    val closeDetails = { if (directPrinterId != null) finish() else selectedId = null }
                    BackHandler(onBack = closeDetails)
                    PrinterSettingsScreen(
                        printer = selected,
                        initialSettings = remember(selected.localId) { repository.settings(selected.localId) },
                        hasNetworkPermission = hasNetworkPermission,
                        onSave = { settings ->
                            val wasShared = repository.settings(selected.localId).shareOnNetwork
                            repository.saveSettings(selected.localId, settings)
                            if (!wasShared && settings.shareOnNetwork &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
                                !NetworkPrintService.hasLocalNetworkPermission(this)
                            ) {
                                requestPermissions.launch(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK))
                            }
                            if (wasShared != settings.shareOnNetwork) refreshNetworkSharing()
                        },
                        onForget = {
                            repository.forgetPrinter(selected.localId)
                            printers = repository.knownPrinters()
                            closeDetails()
                        },
                        onBack = closeDetails,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrinterListScreen(printers: List<KnownPrinter>, onBack: () -> Unit, onSelect: (KnownPrinter) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.arrowBack, contentDescription = "Back") }
                },
                title = { Text("Printers") },
            )
        }
    ) { paddingValues ->
        if (printers.isEmpty()) {
            Text(
                "No printers yet. Connect a USB printer and open the print dialog, and it will show up here.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(paddingValues).padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = paddingValues) {
                items(printers, key = { it.localId }) { printer ->
                    val language = printer.deviceId?.let(PrinterLanguage::detect)
                    ListItem(
                        headlineContent = { Text(printer.name) },
                        supportingContent = { Text(language?.displayName ?: "Unsupported language") },
                        modifier = Modifier.clickable { onSelect(printer) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrinterSettingsScreen(
    printer: KnownPrinter,
    initialSettings: PrinterSettings,
    hasNetworkPermission: Boolean,
    onSave: (PrinterSettings) -> Unit,
    onForget: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(initialSettings) }
    fun update(newSettings: PrinterSettings) {
        settings = newSettings
        onSave(newSettings)
    }

    val detectedLanguage = printer.deviceId?.let(PrinterLanguage::detect)
    val language = settings.resolveLanguage(printer.deviceId)
    var confirmForget by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.arrowBack, contentDescription = "Back") }
                },
                title = { Text(printer.name) },
            )
        }
    ) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            ChoicePreference(
                title = "Printer language",
                options = listOf<PrinterLanguage?>(null) + PrinterLanguage.entries,
                selected = settings.language,
                label = {
                    it?.displayName ?: "Automatic (${detectedLanguage?.displayName ?: "not supported"})"
                },
                onSelect = { update(settings.copy(language = it)) },
            )
            ChoicePreference(
                title = "Color",
                options = ColorSetting.entries,
                selected = settings.color,
                label = {
                    if (it == ColorSetting.AUTO) {
                        "Automatic (${if (settings.copy(color = it).supportsColor(language)) "color" else "black and white"})"
                    } else {
                        it.displayName
                    }
                },
                onSelect = { update(settings.copy(color = it)) },
            )
            ChoicePreference(
                title = "Default paper size",
                options = listOf<String?>(null) + PrinterSettings.MEDIA_SIZES.map { it.id },
                selected = settings.defaultMediaSizeId,
                label = { id ->
                    val regional = PrinterSettings.regionalMediaSize(context)
                    PrinterSettings.MEDIA_SIZES.firstOrNull { it.id == id }?.getLabel(context.packageManager)
                        ?: "Automatic (${regional.getLabel(context.packageManager)})"
                },
                onSelect = { update(settings.copy(defaultMediaSizeId = it)) },
            )
            ChoicePreference(
                title = "Default resolution",
                options = listOf<Int?>(null) + PrinterSettings.SUPPORTED_DPI,
                selected = settings.defaultDpi,
                label = { it?.let { dpi -> "$dpi dpi" } ?: "Automatic (${settings.resolveDpi(language)} dpi)" },
                onSelect = { update(settings.copy(defaultDpi = it)) },
            )
            MarginPreference(
                marginMils = settings.marginMils,
                onChange = { update(settings.copy(marginMils = it)) },
            )
            ListItem(
                headlineContent = { Text("Toner save") },
                supportingContent = { Text("Brother laser printers only") },
                trailingContent = {
                    Switch(
                        checked = settings.tonerSave,
                        onCheckedChange = null,
                        enabled = language == PrinterLanguage.BROTHER_LASER,
                    )
                },
                modifier = Modifier.selectable(
                    selected = settings.tonerSave,
                    enabled = language == PrinterLanguage.BROTHER_LASER,
                    role = Role.Switch,
                    onClick = { update(settings.copy(tonerSave = !settings.tonerSave)) },
                ),
            )

            ListItem(
                headlineContent = { Text("Share on the network") },
                supportingContent = {
                    Text(
                        if (settings.shareOnNetwork && !hasNetworkPermission) {
                            "Network sharing is paused. Allow local network access in the app's system settings."
                        } else if (settings.shareOnNetwork) {
                            val uri = remember(printer.localId) { NetworkPrintService.printerUri(context, printer.localId) }
                            "Other devices on this Wi-Fi find it as a printer while it's connected to this phone. " +
                                "Anyone on the network can print, without a password." +
                                (uri?.let { "\n\nTo add it manually: $it" } ?: "\n\nNot connected to Wi-Fi.")
                        } else {
                            "Let other devices on this Wi-Fi print to it through this phone"
                        }
                    )
                },
                trailingContent = { Switch(checked = settings.shareOnNetwork, onCheckedChange = null) },
                modifier = Modifier.selectable(
                    selected = settings.shareOnNetwork,
                    role = Role.Switch,
                    onClick = {
                        update(settings.copy(shareOnNetwork = !settings.shareOnNetwork))
                    },
                ),
            )

            Text(
                "Changes apply the next time you select this printer in the print dialog.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )

            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Device ID") },
                supportingContent = { Text(printer.deviceId ?: "Couldn't be read") },
            )
            ListItem(
                headlineContent = { Text("Printer ID") },
                supportingContent = { Text(printer.localId) },
            )
            TextButton(onClick = { confirmForget = true }, modifier = Modifier.padding(8.dp)) {
                Text("Forget printer")
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget ${printer.name}?") },
            text = { Text("Its settings are deleted. It shows up again the next time it's connected.") },
            confirmButton = {
                TextButton(onClick = { confirmForget = false; onForget() }) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text("Cancel") }
            },
        )
    }
}

/** A setting showing its current value, which opens a dialog to pick one of [options]. */
@Composable
private fun <T> ChoicePreference(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(label(selected)) },
        modifier = Modifier.clickable { showDialog = true },
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                Column(Modifier.selectableGroup()) {
                    for (option in options) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = option == selected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        onSelect(option)
                                        showDialog = false
                                    },
                                )
                                .padding(vertical = 8.dp),
                        ) {
                            RadioButton(selected = option == selected, onClick = null)
                            Text(label(option))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            },
        )
    }
}

private const val MILS_PER_MM = 1000 / 25.4

private fun formatMm(mils: Int) = String.format(Locale.getDefault(), "%.1f mm", mils / MILS_PER_MM)

/** Minimum margins, edited in millimeters and stored in mils. */
@Composable
private fun MarginPreference(marginMils: Int?, onChange: (Int?) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("Minimum margins") },
        supportingContent = {
            Text(marginMils?.let(::formatMm) ?: "Automatic (${formatMm(PrinterSettings.DEFAULT_MARGIN_MILS)})")
        },
        modifier = Modifier.clickable { showDialog = true },
    )

    if (showDialog) {
        val initial = (marginMils ?: PrinterSettings.DEFAULT_MARGIN_MILS) / MILS_PER_MM
        var text by remember { mutableStateOf(String.format(Locale.getDefault(), "%.1f", initial)) }
        val value = text.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..50.0 }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Minimum margins") },
            text = {
                Column {
                    Text("Space the printer can't print on, on every side of the paper.")
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        suffix = { Text("mm") },
                        isError = value == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = value != null,
                    onClick = {
                        onChange(Math.round(value!! * MILS_PER_MM).toInt())
                        showDialog = false
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { onChange(null); showDialog = false }) { Text("Automatic") }
            },
        )
    }
}
