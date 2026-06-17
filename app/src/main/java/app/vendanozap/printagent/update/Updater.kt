package app.vendanozap.printagent.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.vendanozap.printagent.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class AndroidLatest(
    val versionCode: Int? = null,
    val versionName: String = "",
    val apkUrl: String,
)

/**
 * Updater in-app por sideload (não tem Play Store). Lê os metadados da release
 * latest pelo Worker (/download/android/latest.json, com cache de borda), baixa
 * o APK e abre o instalador do sistema. Tudo iniciado pelo usuário (botão) e
 * isolado do caminho de impressão — qualquer falha aqui é silenciosa e nunca
 * afeta a fila/impressão. Sem timer: a checagem roda quando a tela abre.
 */
object Updater {
    private const val LATEST_URL = "https://vendanozap.app/download/android/latest.json"
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** @return a release nova se houver uma com versionCode maior; null se já está atualizado ou em qualquer falha. */
    suspend fun check(): AndroidLatest? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(LATEST_URL).build()).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val body = res.body?.string() ?: return@withContext null
                val latest = json.decodeFromString(AndroidLatest.serializer(), body)
                val vc = latest.versionCode ?: return@withContext null
                if (vc > BuildConfig.VERSION_CODE) latest else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Baixa o APK pro cache (limpando baixados antigos). Lança IOException em falha. */
    suspend fun download(context: Context, latest: AndroidLatest): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "print-agent-${latest.versionCode}.apk")
        http.newCall(Request.Builder().url(latest.apkUrl).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val body = res.body ?: throw IOException("resposta vazia")
            file.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        file
    }

    /** Abre o instalador do sistema pro APK; o usuário confirma (e libera "apps desconhecidos" se pedir). */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
