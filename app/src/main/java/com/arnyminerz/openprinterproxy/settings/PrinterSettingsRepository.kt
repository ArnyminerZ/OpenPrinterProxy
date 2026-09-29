package com.arnyminerz.openprinterproxy.settings

import android.content.Context
import androidx.core.content.edit
import com.arnyminerz.openprinterproxy.printing.PrinterLanguage

/**
 * A printer that has been connected at least once.
 * @param localId The printer id's local part, see [com.arnyminerz.openprinterproxy.OtgUtils.localPrinterId].
 * @param deviceId The printer's IEEE 1284 device ID, if it could be read.
 */
data class KnownPrinter(val localId: String, val name: String, val deviceId: String?)

/** Stores [PrinterSettings] and the list of [KnownPrinter]s, keyed by the printer's local id. */
class PrinterSettingsRepository(context: Context) {
    private companion object {
        const val KEY_KNOWN = "known_printers"
    }

    private val prefs = context.applicationContext.getSharedPreferences("printer_settings", Context.MODE_PRIVATE)

    private fun key(localId: String, name: String) = "$localId/$name"

    fun knownPrinters(): List<KnownPrinter> =
        prefs.getStringSet(KEY_KNOWN, emptySet()).orEmpty()
            .mapNotNull(::knownPrinter)
            .sortedBy { it.name.lowercase() }

    fun knownPrinter(localId: String): KnownPrinter? {
        if (localId !in prefs.getStringSet(KEY_KNOWN, emptySet()).orEmpty()) return null
        return KnownPrinter(
            localId,
            prefs.getString(key(localId, "name"), null) ?: localId,
            prefs.getString(key(localId, "deviceId"), null),
        )
    }

    fun rememberPrinter(localId: String, name: String, deviceId: String?) {
        val known = prefs.getStringSet(KEY_KNOWN, emptySet()).orEmpty()
        prefs.edit {
            putStringSet(KEY_KNOWN, known + localId)
            putString(key(localId, "name"), name)
            if (deviceId != null) putString(key(localId, "deviceId"), deviceId)
        }
    }

    /** Removes the printer and its settings. */
    fun forgetPrinter(localId: String) {
        val known = prefs.getStringSet(KEY_KNOWN, emptySet()).orEmpty()
        prefs.edit {
            putStringSet(KEY_KNOWN, known - localId)
            prefs.all.keys.filter { it.startsWith("$localId/") }.forEach(::remove)
        }
    }

    fun settings(localId: String): PrinterSettings = PrinterSettings(
        language = prefs.getString(key(localId, "language"), null)
            ?.let { name -> PrinterLanguage.entries.firstOrNull { it.name == name } },
        color = prefs.getString(key(localId, "color"), null)
            ?.let { name -> ColorSetting.entries.firstOrNull { it.name == name } }
            ?: ColorSetting.AUTO,
        defaultMediaSizeId = prefs.getString(key(localId, "mediaSize"), null),
        defaultDpi = prefs.getInt(key(localId, "dpi"), 0).takeIf { it > 0 },
        marginMils = prefs.getInt(key(localId, "marginMils"), -1).takeIf { it >= 0 },
        tonerSave = prefs.getBoolean(key(localId, "tonerSave"), false),
        shareOnNetwork = prefs.getBoolean(key(localId, "shareOnNetwork"), false),
    )

    fun saveSettings(localId: String, settings: PrinterSettings) {
        prefs.edit {
            putOrRemove(key(localId, "language"), settings.language?.name)
            putString(key(localId, "color"), settings.color.name)
            putOrRemove(key(localId, "mediaSize"), settings.defaultMediaSizeId)
            if (settings.defaultDpi != null) putInt(key(localId, "dpi"), settings.defaultDpi)
            else remove(key(localId, "dpi"))
            if (settings.marginMils != null) putInt(key(localId, "marginMils"), settings.marginMils)
            else remove(key(localId, "marginMils"))
            putBoolean(key(localId, "tonerSave"), settings.tonerSave)
            putBoolean(key(localId, "shareOnNetwork"), settings.shareOnNetwork)
        }
    }

    private fun android.content.SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
        if (value != null) putString(key, value) else remove(key)
    }
}
