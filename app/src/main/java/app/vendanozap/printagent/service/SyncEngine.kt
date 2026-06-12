package app.vendanozap.printagent.service

import android.content.Context
import android.util.Base64
import app.vendanozap.printagent.core.AgentState
import app.vendanozap.printagent.core.Prefs
import app.vendanozap.printagent.core.PrinterConfig
import app.vendanozap.printagent.net.ApiClient
import app.vendanozap.printagent.net.ApiException
import app.vendanozap.printagent.net.UnpairedException
import app.vendanozap.printagent.print.PrinterException
import app.vendanozap.printagent.print.Printers
import app.vendanozap.printagent.print.TestReceipt
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
            if (prefs.asciiMode) return drainAscii(trigger, printer)
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
     * Fluxo do modo compatibilidade (impressora genérica/GBK): mesmo fallback
     * v1.5 do agente desktop — claim individual com mode:"ascii", backend
     * devolve o cupom como texto; o app translitera e imprime sem byte alto.
     */
    private suspend fun drainAscii(trigger: String, printer: PrinterConfig): Int {
        var printed = 0
        while (true) {
            val pending = api.listQueue().items.filter { it.status == "pending" }
            if (pending.isEmpty()) break
            var progressed = false
            for (item in pending) {
                val startedAt = System.currentTimeMillis()
                val claim = try {
                    api.claimAscii(item.id)
                } catch (e: ApiException) {
                    // 409 = lease de outro agente; 404 = item já processado.
                    if (e.code == 409 || e.code == 404) continue
                    throw e
                }
                val text = claim.payload?.text
                if (text == null) {
                    api.release(item.id, "NO_PAYLOAD", "payload.text ausente", retry = false)
                    continue
                }
                try {
                    Printers.print(context, printer, TestReceipt.plainText(text))
                    api.ack(item.id, System.currentTimeMillis() - startedAt)
                    ensurePrinterReadyReported(printer.name, printer.type.name.lowercase())
                    printed++
                    progressed = true
                    AgentState.incrementPrinted()
                    AgentState.log("Pedido impresso (#${item.orderId.takeLast(6)}, compat)")
                    api.telemetry(
                        "print_success",
                        mapOf(
                            "queueId" to item.id,
                            "reason" to item.reason,
                            "durationMs" to (System.currentTimeMillis() - startedAt),
                            "attempts" to item.attempts,
                            "printerType" to printer.type.name.lowercase(),
                            "printerHost" to printer.address,
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
                            "printerType" to printer.type.name.lowercase(),
                            "printerHost" to printer.address,
                        ),
                    )
                    return printed
                }
            }
            // Nada avançou = restante está em lease alheio; evita loop infinito.
            if (!progressed) break
        }
        AgentState.markSync()
        api.ping()
        return printed
    }

    /**
     * Emite printer_state_change(ready) UMA vez por pareamento. É esse evento
     * que destrava o gate printerConnectedAt no servidor — sem ele a loja nem
     * enfileira jobs (mesmo comportamento do agente desktop).
     */
    suspend fun ensurePrinterReadyReported(
        printerName: String,
        printerType: String,
        printerModel: String? = null,
    ) {
        if (prefs.printerReadyReported) return
        api.telemetry(
            "printer_state_change",
            mapOf(
                "printerName" to printerName.ifBlank { "Impressora Android" },
                "state" to "ready",
                "printerType" to printerType,
                // Identidade GS I (ou nome BT): alimenta a base nome→modo da frota.
                "printerModel" to printerModel,
            ),
        )
        prefs.printerReadyReported = true
    }
}
