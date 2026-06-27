package app.vendanozap.printagent.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(val at: Long, val message: String, val isError: Boolean) {
    val timeLabel: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))
}

/** Resultado da última tentativa de conexão com a impressora (impressão/teste). */
data class PrinterStatus(val ok: Boolean, val at: Long, val detail: String) {
    val timeLabel: String
        get() = SimpleDateFormat("HH:mm", Locale.US).format(Date(at))
}

/**
 * Estado observável compartilhado entre o serviço e a UI. Mantido em memória
 * de processo (singleton) — a UI sempre roda no mesmo processo do serviço.
 */
object AgentState {
    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning

    private val _lastSyncAt = MutableStateFlow<Long?>(null)
    val lastSyncAt: StateFlow<Long?> = _lastSyncAt

    private val _printedCount = MutableStateFlow(0)
    val printedCount: StateFlow<Int> = _printedCount

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log

    private val _needsRepair = MutableStateFlow(false)
    val needsRepair: StateFlow<Boolean> = _needsRepair

    // Última conexão com a impressora: alimenta o indicador "conectada/desconectada"
    // na UI. Não é link ao vivo (o agente conecta sob demanda) — é o resultado real
    // da última impressão/teste, que é o sinal honesto de alcance pra esses clones.
    private val _printerStatus = MutableStateFlow<PrinterStatus?>(null)
    val printerStatus: StateFlow<PrinterStatus?> = _printerStatus

    // Sink de persistência (LogStore). Configurado no App.onCreate; cada log novo
    // também é gravado no arquivo. Igual ao setLogSink do agente desktop.
    private var logSink: ((LogEntry) -> Unit)? = null

    fun setServiceRunning(running: Boolean) { _serviceRunning.value = running }
    fun markSync() { _lastSyncAt.value = System.currentTimeMillis() }
    fun incrementPrinted() { _printedCount.value += 1 }
    fun setNeedsRepair(v: Boolean) { _needsRepair.value = v }
    fun setPrinterOk() { _printerStatus.value = PrinterStatus(true, System.currentTimeMillis(), "") }
    fun setPrinterFailed(detail: String) { _printerStatus.value = PrinterStatus(false, System.currentTimeMillis(), detail) }

    fun setLogSink(sink: (LogEntry) -> Unit) { logSink = sink }

    /** Carrega os logs persistidos na UI no boot (mais recentes primeiro). */
    fun seedLogs(entries: List<LogEntry>) { _log.value = entries.take(80) }

    fun log(message: String, isError: Boolean = false) {
        val entry = LogEntry(System.currentTimeMillis(), message, isError)
        _log.value = (listOf(entry) + _log.value).take(80)
        logSink?.invoke(entry)
    }
}
