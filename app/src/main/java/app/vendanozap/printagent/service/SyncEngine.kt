package app.vendanozap.printagent.service

import android.content.Context
import android.util.Base64
import app.vendanozap.printagent.core.AgentState
import app.vendanozap.printagent.core.Prefs
import app.vendanozap.printagent.net.ApiClient
import app.vendanozap.printagent.net.UnpairedException
import app.vendanozap.printagent.print.PrinterException
import app.vendanozap.printagent.print.Printers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drena a fila de impressão quando um EVENTO dispara (FCM, boot, rede voltou,
 * botão manual). Não existe timer aqui de propósito: a campainha é o FCM;
 * polling não é usado em nenhuma hipótese.
 */
class SyncEngine(
    private val context: Context,
    private val prefs: Prefs,
    private val api: ApiClient,
) {
    private val mutex = Mutex()

    /** @return quantidade impressa, ou -1 se despareado. */
    suspend fun drain(trigger: String): Int = mutex.withLock {
        val printer = prefs.printer
        if (printer == null) {
            AgentState.log("Sync ($trigger): impressora não configurada", isError = true)
            return -1
        }
        var printed = 0
        try {
            while (true) {
                val batch = api.claimLease(max = 5).items
                if (batch.isEmpty()) break
                for (item in batch) {
                    val startedAt = System.currentTimeMillis()
                    try {
                        val bytes = Base64.decode(item.bytesBase64, Base64.DEFAULT)
                        Printers.print(context, printer, bytes)
                        val durationMs = System.currentTimeMillis() - startedAt
                        api.ack(item.id, durationMs)
                        ensurePrinterReadyReported(printer.name, printer.type.name.lowercase())
                        printed++
                        AgentState.incrementPrinted()
                        AgentState.log("Pedido impresso (#${item.orderId.takeLast(6)})")
                        api.telemetry(
                            "print_success",
                            mapOf(
                                "queueId" to item.id,
                                "reason" to item.reason,
                                "paperWidth" to item.paperWidth,
                                "durationMs" to (System.currentTimeMillis() - startedAt),
                                "attempts" to item.attempts,
                                "printerType" to printer.type.name.lowercase(),
                                "printerHost" to printer.address,
                                "agentVersion" to "android",
                            ),
                        )
                    } catch (e: PrinterException) {
                        AgentState.log("Falha na impressora: ${e.code} ${e.message}", isError = true)
                        api.release(item.id, e.code, e.message)
                        api.telemetry(
                            "print_failure",
                            mapOf(
                                "queueId" to item.id,
                                "errorCode" to e.code,
                                "errorMessage" to e.message,
                                "attempts" to item.attempts,
                                "printerType" to printer.type.name.lowercase(),
                                "printerHost" to printer.address,
                            ),
                        )
                        // Impressora indisponível: devolve o lote e para — o
                        // próximo evento (ou retry manual) tenta de novo.
                        return printed
                    }
                }
            }
            AgentState.markSync()
            api.ping()
        } catch (e: UnpairedException) {
            AgentState.setNeedsRepair(true)
            AgentState.log("Pareamento inválido — gere um novo código no painel", isError = true)
            return -1
        } catch (e: Exception) {
            AgentState.log("Sync ($trigger) falhou: ${e.message}", isError = true)
        }
        printed
    }

    /**
     * Emite printer_state_change(ready) UMA vez por pareamento. É esse evento
     * que destrava o gate printerConnectedAt no servidor — sem ele a loja nem
     * enfileira jobs (mesmo comportamento do agente desktop).
     */
    suspend fun ensurePrinterReadyReported(printerName: String, printerType: String) {
        if (prefs.printerReadyReported) return
        api.telemetry(
            "printer_state_change",
            mapOf(
                "printerName" to printerName.ifBlank { "Impressora Android" },
                "state" to "ready",
                "printerType" to printerType,
            ),
        )
        prefs.printerReadyReported = true
    }
}
