package app.vendanozap.printagent.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.vendanozap.printagent.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Enviar logs ao suporte": monta uma mensagem com versão, loja, impressora,
 * status e os logs recentes e abre o WhatsApp do suporte com tudo pré-preenchido
 * (mesmo fluxo do agente desktop — deep link wa.me, sem POST). O lojista só
 * confirma o envio.
 */
object Support {
    private const val WHATSAPP_NUMBER = "5511921048695"
    private const val MAX_CHARS = 3000

    fun buildText(prefs: Prefs, logs: List<LogEntry>): String {
        val sb = StringBuilder()
        sb.append("Olá, preciso de suporte com o Print Agent (Android).\n\n")
        sb.append("Versão: v${BuildConfig.VERSION_NAME}\n")
        prefs.storeId?.let { sb.append("id da Loja: $it\n") }
        prefs.storeName?.takeIf { it.isNotBlank() }?.let { sb.append("Loja: $it\n") }
        prefs.printer?.let {
            val tipo = it.type.name.lowercase()
            sb.append("Impressora: ${it.name.ifBlank { it.address }} ($tipo)\n")
            val m = prefs.printerMode
            sb.append("Modo de impressão: ${m.label} (${m.wire})\n")
        }
        sb.append("Data: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(Date())}\n\n")
        sb.append("— Logs recentes —\n")
        if (logs.isEmpty()) {
            sb.append("(sem logs)\n")
        } else {
            for (l in logs) {
                sb.append("${l.timeLabel} ${if (l.isError) "[ERRO] " else ""}${l.message}\n")
            }
        }
        var text = sb.toString()
        if (text.length > MAX_CHARS) text = text.take(MAX_CHARS) + "\n…(mensagem truncada)"
        return text
    }

    fun openSupport(context: Context, prefs: Prefs, logs: List<LogEntry>) {
        val uri = Uri.parse("https://wa.me/$WHATSAPP_NUMBER")
            .buildUpon()
            .appendQueryParameter("text", buildText(prefs, logs))
            .build()
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            AgentState.log("Não foi possível abrir o WhatsApp: ${e.message}", isError = true)
        }
    }
}
