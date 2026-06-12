package app.vendanozap.printagent.net

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import app.vendanozap.printagent.BuildConfig
import app.vendanozap.printagent.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException(message)

/** Pareamento foi revogado/inválido — exige novo token do painel. */
class UnpairedException : IOException("Pareamento inválido")

/**
 * Cliente da API de Print Agent. Mesmo contrato do agente desktop:
 * refresh token permanente (vnzpa_...) trocado por JWT de 15 min;
 * re-exchange transparente em expiração ou 401.
 */
class ApiClient(context: Context, private val prefs: Prefs) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var accessToken: String? = null
    @Volatile private var accessExpiresAt: Long = 0
    private val tokenMutex = Mutex()

    companion object {
        const val BASE_URL = "https://api.vendanozap.app"
    }

    // ---------- Pareamento ----------

    /**
     * Troca o refresh token pelo access token. Usado tanto no pareamento
     * inicial (token digitado) quanto na renovação silenciosa.
     */
    suspend fun exchange(refreshToken: String): ExchangeResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            ExchangeRequest.serializer(),
            ExchangeRequest(
                refreshToken = refreshToken,
                hostname = "${Build.MANUFACTURER} ${Build.MODEL}".take(120),
                os = "android ${Build.VERSION.RELEASE}",
                agentVersion = "android-${BuildConfig.VERSION_NAME}",
                machineIdHash = machineIdHash(),
            ),
        )
        val req = Request.Builder()
            .url("$BASE_URL/api/print-agent/token/exchange")
            .post(body.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string() ?: ""
            if (res.code == 401) throw UnpairedException()
            if (!res.isSuccessful) throw ApiException(res.code, "exchange falhou: HTTP ${res.code}")
            val parsed = json.decodeFromString(ExchangeResponse.serializer(), text)
            accessToken = parsed.accessToken
            // Renova 60s antes de expirar pra não correr atrás de 401.
            accessExpiresAt = System.currentTimeMillis() + (parsed.expiresIn - 60) * 1000
            parsed
        }
    }

    private suspend fun validAccessToken(): String {
        tokenMutex.withLock {
            val current = accessToken
            if (current != null && System.currentTimeMillis() < accessExpiresAt) return current
            val refresh = prefs.refreshToken ?: throw UnpairedException()
            val response = exchange(refresh)
            // Tokens legacy (bcrypt) rotacionam a cada exchange; permanentes não.
            if (response.refreshToken != refresh) prefs.refreshToken = response.refreshToken
            return response.accessToken
        }
    }

    // ---------- Fila ----------

    suspend fun claimLease(max: Int = 5): ClaimLeaseResponse =
        authedPost(
            "/api/print-queue/claim-lease",
            json.encodeToString(ClaimLeaseRequest.serializer(), ClaimLeaseRequest(max)),
            ClaimLeaseResponse.serializer(),
        )

    suspend fun listQueue(): QueueListResponse =
        authedGet("/api/print-queue/", QueueListResponse.serializer())

    /**
     * Claim individual em modo texto (fallback v1.5 do agente desktop pra
     * impressoras genéricas): backend devolve payload.text em vez de bytes.
     */
    suspend fun claimAscii(queueId: String): ClaimResponse =
        authedPost(
            "/api/print-queue/$queueId/claim",
            """{"mode":"ascii"}""",
            ClaimResponse.serializer(),
        )

    suspend fun ack(queueId: String, durationMs: Long?) {
        authedPostNoParse(
            "/api/print-queue/$queueId/ack",
            json.encodeToString(AckRequest.serializer(), AckRequest(durationMs)),
        )
    }

    suspend fun release(queueId: String, errorCode: String, errorMessage: String?, retry: Boolean = true) {
        authedPostNoParse(
            "/api/print-queue/$queueId/release",
            json.encodeToString(
                ReleaseRequest.serializer(),
                ReleaseRequest(errorCode, errorMessage?.take(200), retry),
            ),
        )
    }

    // ---------- Sinais ----------

    suspend fun ping() {
        authedPostNoParse("/api/print-agent/ping", "{}")
    }

    /**
     * Telemetria best-effort (formato top-level, igual ao agente Electron).
     * Nunca propaga falha — telemetria não pode derrubar impressão.
     */
    suspend fun telemetry(type: String, context: Map<String, Any?>) {
        try {
            val fields = buildString {
                append("{\"type\":").append(json.encodeToString(kotlinx.serialization.serializer<String>(), type))
                for ((k, v) in context) {
                    append(',')
                    append(json.encodeToString(kotlinx.serialization.serializer<String>(), k))
                    append(':')
                    when (v) {
                        null -> append("null")
                        is Number, is Boolean -> append(v.toString())
                        else -> append(json.encodeToString(kotlinx.serialization.serializer<String>(), v.toString()))
                    }
                }
                append('}')
            }
            authedPostNoParse("/api/print-agent/telemetry", fields)
        } catch (_: Exception) {
            // best-effort
        }
    }

    /**
     * Registra o token FCM no backend. O endpoint ainda não existe no servidor
     * (campainha FCM é o próximo passo do backend) — 404 é esperado e ignorado;
     * quando o endpoint nascer, o registro passa a funcionar sem mudar o app.
     */
    suspend fun registerFcmToken(token: String) {
        try {
            authedPostNoParse(
                "/api/print-agent/fcm-token",
                json.encodeToString(FcmTokenRequest.serializer(), FcmTokenRequest(token)),
            )
        } catch (_: Exception) {
            // endpoint ausente ou rede fora — re-tenta no próximo evento
        }
    }

    // ---------- Internos ----------

    private suspend fun <T> authedPost(
        path: String,
        body: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        val text = executeAuthed(path, body)
        json.decodeFromString(deserializer, text)
    }

    private suspend fun <T> authedGet(
        path: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        val text = executeAuthed(path, body = null)
        json.decodeFromString(deserializer, text)
    }

    private suspend fun authedPostNoParse(path: String, body: String) {
        withContext(Dispatchers.IO) { executeAuthed(path, body) }
    }

    private suspend fun executeAuthed(path: String, body: String?): String {
        var attempt = 0
        while (true) {
            val token = validAccessToken()
            val req = Request.Builder()
                .url("$BASE_URL$path")
                .header("Authorization", "Bearer $token")
                .let { if (body == null) it.get() else it.post(body.toRequestBody(jsonMedia)) }
                .build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.string() ?: ""
                if (res.code == 401 && attempt == 0) {
                    // JWT pode ter sido invalidado (ex.: rotação de segredo).
                    // Força re-exchange uma vez antes de desistir.
                    accessToken = null
                    accessExpiresAt = 0
                    attempt++
                    return@use
                }
                if (res.code == 401) throw UnpairedException()
                if (!res.isSuccessful) throw ApiException(res.code, "HTTP ${res.code} em $path: ${text.take(180)}")
                return text
            }
        }
    }

    @SuppressLint("HardwareIds")
    private fun machineIdHash(): String {
        val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val digest = MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
