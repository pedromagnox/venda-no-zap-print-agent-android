package app.vendanozap.printagent.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.vendanozap.printagent.App
import app.vendanozap.printagent.MainActivity
import app.vendanozap.printagent.R
import app.vendanozap.printagent.core.AgentState
import app.vendanozap.printagent.core.Prefs
import app.vendanozap.printagent.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (type connectedDevice) que mantém o agente vivo.
 * Sem timers: todo trabalho é disparado por evento —
 *   - ACTION_SYNC vindo do FCM, do boot, da UI ou de "rede voltou"
 *   - callback de conectividade registrado enquanto o serviço vive
 */
class AgentForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: Prefs
    private lateinit var engine: SyncEngine
    private var wasOffline = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Evento de transição: só re-sincroniza quando estávamos offline,
            // pra não tratar troca Wi-Fi→4G como tempestade de syncs.
            if (wasOffline) {
                wasOffline = false
                scope.launch { engine.drain("network_restored") }
            }
        }

        override fun onLost(network: Network) {
            wasOffline = true
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        engine = SyncEngine(this, prefs, ApiClient(this, prefs))
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(networkCallback)
        AgentState.setServiceRunning(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val trigger = intent?.getStringExtra(EXTRA_TRIGGER) ?: "service_start"
                scope.launch { engine.drain(trigger) }
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val storeName = prefs.storeName ?: "Venda no Zap"
        val notification: Notification = NotificationCompat.Builder(this, App.CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Agente de impressão ativo")
            .setContentText(storeName)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        runCatching {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(networkCallback)
        }
        AgentState.setServiceRunning(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_SYNC = "app.vendanozap.printagent.SYNC"
        const val ACTION_STOP = "app.vendanozap.printagent.STOP"
        const val EXTRA_TRIGGER = "trigger"

        /** Inicia (ou cutuca) o serviço pra drenar a fila. */
        fun sync(context: Context, trigger: String) {
            val prefs = Prefs(context)
            if (!prefs.isPaired || prefs.printer == null) return
            val intent = Intent(context, AgentForegroundService::class.java)
                .setAction(ACTION_SYNC)
                .putExtra(EXTRA_TRIGGER, trigger)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AgentForegroundService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
