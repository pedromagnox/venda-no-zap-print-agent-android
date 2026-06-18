package app.vendanozap.printagent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.vendanozap.printagent.core.AgentState
import app.vendanozap.printagent.core.LogStore

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // Logs persistidos (retenção 48h): prune no boot, carrega recentes na UI
        // e liga o sink pra cada log novo ir pro arquivo. Igual ao agente desktop.
        val logStore = LogStore(this)
        logStore.prune()
        AgentState.seedLogs(logStore.recent(80))
        AgentState.setLogSink { logStore.append(it.at, it.message, it.isError) }

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
