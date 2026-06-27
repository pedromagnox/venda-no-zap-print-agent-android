package app.vendanozap.printagent.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.vendanozap.printagent.App
import app.vendanozap.printagent.MainActivity
import app.vendanozap.printagent.R

/**
 * Notificação de alerta quando um PEDIDO não imprime (impressora desconectada).
 * Pedidos falham em silêncio — a lojista só descobria quando o cliente reclamava.
 * Usa CHANNEL_ALERTS (IMPORTANCE_HIGH) pra aparecer na hora. ID fixo: repetições
 * atualizam a mesma notificação em vez de empilhar. Só pro fluxo de pedidos —
 * NÃO pro botão Teste (a lojista já está olhando o app).
 */
object Alerts {
    private const val PRINT_FAILED_ID = 2001

    @SuppressLint("MissingPermission") // areNotificationsEnabled() + runCatching cobrem o caso sem permissão
    fun printFailed(context: Context, message: String) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, App.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pedido não impresso")
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(PRINT_FAILED_ID, n) }
    }

    /** Some o alerta quando volta a imprimir (impressora reconectou). */
    fun clearPrintFailed(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(PRINT_FAILED_ID) }
    }
}
