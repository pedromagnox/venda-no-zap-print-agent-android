package app.vendanozap.printagent.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class StoredLog(val at: Long, val message: String, val isError: Boolean)

/**
 * Persistência de logs em arquivo (JSONL em filesDir), retenção 48h — análogo ao
 * LogsStore (SQLite) do agente desktop. Sobrevive a restart/crash pra o suporte
 * ver o que aconteceu antes. Sem dependência nova: kotlinx.serialization (já no
 * projeto) + java.io. Best-effort: qualquer falha de IO é engolida, nunca quebra
 * o app nem a impressão.
 */
class LogStore(context: Context) {
    private val file = File(context.filesDir, "agent-logs.jsonl")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun append(at: Long, message: String, isError: Boolean) {
        synchronized(lock) {
            try {
                val line = json.encodeToString(
                    StoredLog.serializer(),
                    StoredLog(at, message.take(MESSAGE_MAX_CHARS), isError),
                )
                file.appendText(line + "\n")
            } catch (_: Exception) {
            }
        }
    }

    /** Mais recentes primeiro, até [limit]. */
    fun recent(limit: Int = 200): List<LogEntry> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        return try {
            file.readLines()
                .takeLast(limit)
                .mapNotNull { runCatching { json.decodeFromString(StoredLog.serializer(), it) }.getOrNull() }
                .map { LogEntry(it.at, it.message, it.isError) }
                .reversed()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Remove entradas > 48h (reescreve o arquivo). Chamar no boot. */
    fun prune(maxAgeMs: Long = FORTY_EIGHT_HOURS_MS, maxLines: Int = 2000) {
        synchronized(lock) {
            if (!file.exists()) return
            try {
                val cutoff = System.currentTimeMillis() - maxAgeMs
                val kept = file.readLines()
                    .mapNotNull { runCatching { json.decodeFromString(StoredLog.serializer(), it) }.getOrNull() }
                    .filter { it.at >= cutoff }
                    .takeLast(maxLines)
                file.writeText(
                    kept.joinToString(separator = "\n", postfix = if (kept.isEmpty()) "" else "\n") {
                        json.encodeToString(StoredLog.serializer(), it)
                    },
                )
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val FORTY_EIGHT_HOURS_MS = 48L * 60 * 60 * 1000
        private const val MESSAGE_MAX_CHARS = 2000
    }
}
