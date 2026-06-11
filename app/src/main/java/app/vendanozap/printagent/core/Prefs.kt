package app.vendanozap.printagent.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Tipo de conexão com a impressora térmica. */
enum class PrinterType { BLUETOOTH, TCP }

data class PrinterConfig(
    val type: PrinterType,
    /** MAC do dispositivo Bluetooth pareado, ou host/IP no caso TCP. */
    val address: String,
    val port: Int = 9100,
    val name: String = "",
)

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("vnz_print_agent", Context.MODE_PRIVATE)

    var refreshToken: String?
        get() = sp.getString("refreshToken", null)
        set(v) = sp.edit { putString("refreshToken", v) }

    var storeId: String?
        get() = sp.getString("storeId", null)
        set(v) = sp.edit { putString("storeId", v) }

    var storeName: String?
        get() = sp.getString("storeName", null)
        set(v) = sp.edit { putString("storeName", v) }

    var fcmToken: String?
        get() = sp.getString("fcmToken", null)
        set(v) = sp.edit { putString("fcmToken", v) }

    /** Já emitiu printer_state_change(ready) alguma vez? (gate printerConnectedAt) */
    var printerReadyReported: Boolean
        get() = sp.getBoolean("printerReadyReported", false)
        set(v) = sp.edit { putBoolean("printerReadyReported", v) }

    var printer: PrinterConfig?
        get() {
            val type = sp.getString("printer.type", null) ?: return null
            val address = sp.getString("printer.address", null) ?: return null
            return PrinterConfig(
                type = PrinterType.valueOf(type),
                address = address,
                port = sp.getInt("printer.port", 9100),
                name = sp.getString("printer.name", "") ?: "",
            )
        }
        set(v) = sp.edit {
            if (v == null) {
                remove("printer.type"); remove("printer.address")
                remove("printer.port"); remove("printer.name")
            } else {
                putString("printer.type", v.type.name)
                putString("printer.address", v.address)
                putInt("printer.port", v.port)
                putString("printer.name", v.name)
            }
        }

    val isPaired: Boolean get() = refreshToken != null

    fun clearPairing() {
        sp.edit {
            remove("refreshToken"); remove("storeId"); remove("storeName")
            remove("printerReadyReported")
        }
    }
}
