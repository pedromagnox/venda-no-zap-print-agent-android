package app.vendanozap.printagent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Religa o agente após boot do aparelho ou update do APK — essencial pro
 * cenário "celular dedicado no balcão" sobreviver a queda de energia.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> AgentForegroundService.sync(context, trigger = "boot")
        }
    }
}
