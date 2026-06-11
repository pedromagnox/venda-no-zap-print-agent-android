package app.vendanozap.printagent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AGENT,
                "Serviço de impressão",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Mantém o agente de impressão ativo" },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Alertas",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Problemas que precisam de atenção (ex.: re-parear)" },
        )
    }

    companion object {
        const val CHANNEL_AGENT = "agent"
        const val CHANNEL_ALERTS = "alerts"
    }
}
