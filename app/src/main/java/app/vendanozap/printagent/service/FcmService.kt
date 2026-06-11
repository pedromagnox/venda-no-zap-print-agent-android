package app.vendanozap.printagent.service

import app.vendanozap.printagent.core.AgentState
import app.vendanozap.printagent.core.Prefs
import app.vendanozap.printagent.net.ApiClient
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Campainha FCM: data message high-priority com {type: "print_job"} acorda o
 * app (inclusive em Doze) e dispara a drenagem da fila. O conteúdo do cupom
 * NUNCA viaja pelo FCM — só o sino; os bytes vêm da API autenticada.
 *
 * Só funciona depois que o projeto Firebase existir (google-services.json) e o
 * backend ganhar o sender; até lá este serviço simplesmente nunca é invocado.
 */
class FcmService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        AgentState.log("FCM recebido (${message.data["type"] ?: "?"})")
        // FCM high-priority concede janela de execução que permite iniciar FGS.
        AgentForegroundService.sync(this, trigger = "fcm")
    }

    override fun onNewToken(token: String) {
        val prefs = Prefs(this)
        prefs.fcmToken = token
        if (!prefs.isPaired) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            ApiClient(this@FcmService, prefs).registerFcmToken(token)
        }
    }
}
