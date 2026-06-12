package app.vendanozap.printagent.print

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import app.vendanozap.printagent.core.PrinterConfig
import app.vendanozap.printagent.core.PrinterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

class PrinterException(val code: String, message: String) : IOException(message)

/** Resultado da sondagem feita na configuração da impressora. */
data class ProbeResult(
    /** true = impressora genérica (GBK fixo): cupons saem transliterados. */
    val asciiMode: Boolean,
    /** Identidade respondida via GS I (ou null se a impressora ficou muda). */
    val identity: String?,
)

/**
 * Escrita em blocos pequenos com pausa: térmicas Bluetooth baratas têm buffer
 * de poucos KB e descartam o excedente silenciosamente (cupom sai pela metade).
 */
private fun OutputStream.writeChunked(bytes: ByteArray, chunk: Int = 512, pauseMs: Long = 25) {
    var offset = 0
    while (offset < bytes.size) {
        val len = minOf(chunk, bytes.size - offset)
        write(bytes, offset, len)
        flush()
        offset += len
        if (offset < bytes.size) Thread.sleep(pauseMs)
    }
}

object Printers {
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    fun bluetoothAdapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * Envia os bytes (ESC/POS prontos, vindos do servidor) pra impressora
     * configurada. Lança PrinterException com código estável pro release.
     */
    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT validada pelo chamador
    suspend fun print(context: Context, config: PrinterConfig, bytes: ByteArray) {
        val payload = withKanjiOff(bytes)
        withContext(Dispatchers.IO) {
            openConn(context, config).use { conn ->
                try {
                    conn.output.writeChunked(payload, conn.chunk, conn.pauseMs)
                    // Margem pro buffer interno drenar antes do close cortar o link.
                    Thread.sleep(150)
                } catch (e: IOException) {
                    throw PrinterException("WRITE_FAILED", e.message ?: "Falha ao enviar dados")
                }
            }
        }
    }

    /**
     * Sonda + teste numa conexão só (clonas engasgam com reconexão rápida).
     * Detecção automática do "modo sem acentos", análoga ao VID 28E9 que o
     * agente Windows usa pra chips genéricos — só que Bluetooth não expõe VID:
     *  1. nome BT bate padrão de clona conhecida → genérica;
     *  2. GS I 67 (transmit model name): Epson-compatible de verdade responde;
     *     silêncio em ~700ms → genérica (conservador: sem acento é legível
     *     em qualquer impressora; ideograma GBK não é).
     * Já imprime o cupom de teste no modo decidido.
     */
    suspend fun probeAndTest(
        context: Context,
        config: PrinterConfig,
        storeName: String?,
    ): ProbeResult = withContext(Dispatchers.IO) {
        openConn(context, config).use { conn ->
            val nameLooksGeneric = GENERIC_NAME_PATTERNS.any { it.containsMatchIn(config.name) }
            val identity = gsIdentity(conn)
            val ascii = nameLooksGeneric || identity.isNullOrBlank()
            val receipt = if (ascii) TestReceipt.buildAscii(storeName) else TestReceipt.build(storeName)
            try {
                conn.output.writeChunked(withKanjiOff(receipt), conn.chunk, conn.pauseMs)
                Thread.sleep(150)
            } catch (e: IOException) {
                throw PrinterException("WRITE_FAILED", e.message ?: "Falha ao enviar dados")
            }
            ProbeResult(ascii, identity)
        }
    }

    /** Nomes Bluetooth típicos de térmicas genéricas (base inicial; a telemetria da frota alimenta a evolução). */
    private val GENERIC_NAME_PATTERNS = listOf(
        Regex("PT-?2\\d{2}", RegexOption.IGNORE_CASE),        // Goojprt PT-210/280
        Regex("M[PT]T-?(II|2|3)", RegexOption.IGNORE_CASE),   // MTP-II / MPT-3
        Regex("ZJ-?\\d{2}", RegexOption.IGNORE_CASE),         // Zijiang ZJ-58/80
        Regex("POS-?\\d{2}", RegexOption.IGNORE_CASE),        // POS58/POS80 genéricas
        Regex("GOOJPRT", RegexOption.IGNORE_CASE),
        Regex("^Blue ?tooth ?Printer$", RegexOption.IGNORE_CASE),
        Regex("^Printer[ _-]?\\d*$", RegexOption.IGNORE_CASE),
        Regex("^JP-?\\d", RegexOption.IGNORE_CASE),
    )

    /**
     * GS I 67 → string de modelo. Lê via available()+poll porque InputStream
     * de BluetoothSocket não tem timeout nativo. Clonas ignoram o comando em
     * silêncio (0x1D não imprime), então a sonda não suja o papel.
     */
    private fun gsIdentity(conn: Conn): String? = try {
        conn.output.write(byteArrayOf(0x1D, 0x49, 67))
        conn.output.flush()
        val deadline = System.currentTimeMillis() + 700
        val buf = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            val available = conn.input.available()
            if (available > 0) {
                val bytes = ByteArray(available)
                val read = conn.input.read(bytes)
                for (i in 0 until read) {
                    val c = bytes[i].toInt() and 0xFF
                    if (c in 0x20..0x7E) buf.append(c.toChar())
                }
                Thread.sleep(80) // espera curta por resto da resposta
                if (conn.input.available() == 0) break
            } else {
                Thread.sleep(40)
            }
        }
        buf.toString().trim().take(60).ifBlank { null }
    } catch (_: Exception) {
        null
    }

    /**
     * Térmicas chinesas baratas ligam modo CJK por padrão: bytes altos são
     * consumidos em PARES (dois acentos viram um ideograma) e o cupom sai
     * "AcentuaÚ". FS . (cancel Kanji) desliga. Como ESC @ reseta o modo, o
     * comando precisa vir DEPOIS do init do payload — injeta lá quando o job
     * começa com ESC @, senão prefixa. Inofensivo em Epson-compatible real.
     */
    private fun withKanjiOff(bytes: ByteArray): ByteArray {
        val fsDot = byteArrayOf(0x1C, 0x2E)
        return if (bytes.size >= 2 && bytes[0] == 0x1B.toByte() && bytes[1] == 0x40.toByte()) {
            byteArrayOf(bytes[0], bytes[1]) + fsDot + bytes.copyOfRange(2, bytes.size)
        } else {
            fsDot + bytes
        }
    }

    private interface Conn : java.io.Closeable {
        val input: java.io.InputStream
        val output: OutputStream
        val chunk: Int
        val pauseMs: Long
    }

    @SuppressLint("MissingPermission")
    private fun openConn(context: Context, config: PrinterConfig): Conn = when (config.type) {
        PrinterType.BLUETOOTH -> {
            val adapter = bluetoothAdapter(context)
                ?: throw PrinterException("BT_UNAVAILABLE", "Bluetooth indisponível neste aparelho")
            if (!adapter.isEnabled) throw PrinterException("BT_OFF", "Bluetooth desligado")
            val device = try {
                adapter.getRemoteDevice(config.address)
            } catch (e: IllegalArgumentException) {
                throw PrinterException("BT_BAD_ADDRESS", "Endereço Bluetooth inválido: ${config.address}")
            }
            // Impressoras SPP: tenta o socket seguro primeiro, cai pro insecure
            // (muitas térmicas chinesas não suportam pairing autenticado no RFCOMM).
            val socket = try {
                device.createRfcommSocketToServiceRecord(SPP_UUID).apply { connect() }
            } catch (_: IOException) {
                try {
                    device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).apply { connect() }
                } catch (e: IOException) {
                    throw PrinterException("BT_CONNECT_FAILED", e.message ?: "Falha ao conectar na impressora")
                }
            }
            object : Conn {
                override val input get() = socket.inputStream
                override val output get() = socket.outputStream
                override val chunk = 512
                override val pauseMs = 25L
                override fun close() = socket.close()
            }
        }
        PrinterType.TCP -> {
            val socket = try {
                Socket().apply {
                    connect(InetSocketAddress(config.address, config.port), 5000)
                    soTimeout = 5000
                }
            } catch (e: IOException) {
                throw PrinterException("TCP_FAILED", "${config.address}:${config.port} — ${e.message}")
            }
            object : Conn {
                override val input get() = socket.getInputStream()
                override val output get() = socket.getOutputStream()
                override val chunk = 4096
                override val pauseMs = 0L
                override fun close() = socket.close()
            }
        }
    }
}

/**
 * Cupom de teste gerado localmente, com acentos codificados em CP858 na mão
 * (não dependemos do charset IBM858 existir no aparelho). Teste ASCII não
 * prova acentuação — e acento errado é a dor clássica de codepage.
 */
object TestReceipt {
    private val CP858: Map<Char, Int> = mapOf(
        'Ç' to 0x80, 'ü' to 0x81, 'é' to 0x82, 'â' to 0x83, 'ä' to 0x84, 'à' to 0x85,
        'ç' to 0x87, 'ê' to 0x88, 'è' to 0x8A, 'í' to 0xA1, 'ì' to 0x8D, 'É' to 0x90,
        'ô' to 0x93, 'ò' to 0x95, 'û' to 0x96, 'ù' to 0x97, 'ÿ' to 0x98, 'Ü' to 0x9A,
        'á' to 0xA0, 'ó' to 0xA2, 'ú' to 0xA3, 'ñ' to 0xA4, 'Ñ' to 0xA5, 'ª' to 0xA6,
        'º' to 0xA7, '¿' to 0xA8, 'Á' to 0xB5, 'Â' to 0xB6, 'À' to 0xB7, 'ã' to 0xC6,
        'Ã' to 0xC7, 'Ê' to 0xD2, 'Í' to 0xD6, 'Ó' to 0xE0, 'Ô' to 0xE2, 'õ' to 0xE4,
        'Õ' to 0xE5, 'Ú' to 0xE9, '°' to 0xF8,
    )

    /** CP860 (português clássico) — vários acentos vivem em posições diferentes do CP850/858. */
    private val CP860: Map<Char, Int> = mapOf(
        'Ç' to 0x80, 'ü' to 0x81, 'é' to 0x82, 'â' to 0x83, 'ã' to 0x84, 'à' to 0x85,
        'Á' to 0x86, 'ç' to 0x87, 'ê' to 0x88, 'Ê' to 0x89, 'è' to 0x8A, 'Í' to 0x8B,
        'Ô' to 0x8C, 'ì' to 0x8D, 'Ã' to 0x8E, 'Â' to 0x8F, 'É' to 0x90, 'À' to 0x91,
        'È' to 0x92, 'ô' to 0x93, 'õ' to 0x94, 'ò' to 0x95, 'Ú' to 0x96, 'ù' to 0x97,
        'Ì' to 0x98, 'Õ' to 0x99, 'Ü' to 0x9A, 'Ù' to 0x9D, 'Ó' to 0x9F,
        'á' to 0xA0, 'í' to 0xA1, 'ó' to 0xA2, 'ú' to 0xA3, 'ñ' to 0xA4, 'Ñ' to 0xA5,
        'ª' to 0xA6, 'º' to 0xA7, 'Ò' to 0xA9,
    )

    private fun encodeWith(table: Map<Char, Int>, text: String): ByteArray {
        val out = ByteArray(text.length)
        for (i in text.indices) {
            val c = text[i]
            out[i] = when {
                c.code < 0x80 -> c.code.toByte()
                table.containsKey(c) -> table.getValue(c).toByte()
                else -> '?'.code.toByte()
            }
        }
        return out
    }

    private fun ascii(text: String): ByteArray =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .map { if (it.code < 0x80) it.code.toByte() else '?'.code.toByte() }
            .toByteArray()

    private const val SAMPLE = "ÁÉÍÓÚ áéíóú ãõ çÇ êô\n"
    private val ESC = 0x1B.toByte()
    private val GS = 0x1D.toByte()
    private fun escT(table: Int) = byteArrayOf(ESC, 't'.code.toByte(), table.toByte())

    /**
     * Cupom de DIAGNÓSTICO: imprime a mesma frase acentuada em três codepages
     * (ESC t 19/2/3 = CP858/CP850/CP860). Clonas chinesas variam o mapa de
     * tabelas entre firmwares — a linha que sair correta identifica a config
     * certa pra esta impressora. FS . desliga o modo CJK antes de tudo.
     */
    fun build(storeName: String?): ByteArray {
        var out = byteArrayOf(
            ESC, '@'.code.toByte(),          // init
            0x1C, 0x2E,                       // FS . — cancela modo CJK (clonas)
            ESC, 'a'.code.toByte(), 1,        // center
        )
        out += ascii(
            buildString {
                append("VENDA NO ZAP\n")
                append("Teste de impressora\n")
                if (!storeName.isNullOrBlank()) append("$storeName\n")
            },
        )
        out += byteArrayOf(ESC, 'a'.code.toByte(), 0) // left
        out += ascii("--------------------------------\n")
        out += ascii("Qual linha saiu com acentos\ncertos? Informe no app/suporte:\n\n")
        out += ascii("[1] ") + escT(19) + encodeWith(CP858, SAMPLE)
        out += ascii("[2] ") + escT(2) + encodeWith(CP858, SAMPLE)
        out += ascii("[3] ") + escT(3) + encodeWith(CP860, SAMPLE)
        out += ascii("\nImpressora conectada!\n\n\n")
        out += byteArrayOf(GS, 'V'.code.toByte(), 66, 0) // partial cut + feed
        return out
    }

    /** Teste do modo compatibilidade: tudo transliterado, sem byte alto. */
    fun buildAscii(storeName: String?): ByteArray {
        var out = byteArrayOf(
            ESC, '@'.code.toByte(),
            0x1C, 0x2E,
            ESC, 'a'.code.toByte(), 1,
        )
        out += ascii(
            buildString {
                append("VENDA NO ZAP\n")
                append("Modo compatibilidade\n")
                if (!storeName.isNullOrBlank()) append("$storeName\n")
            },
        )
        out += byteArrayOf(ESC, 'a'.code.toByte(), 0)
        out += ascii("--------------------------------\n")
        out += ascii("Acentuacao: AEIOU aeiou ao cC eo\n")
        out += ascii("Se este cupom saiu legivel, os\npedidos vao imprimir assim.\n\n\n")
        out += byteArrayOf(GS, 'V'.code.toByte(), 66, 0)
        return out
    }

    /**
     * Cupom a partir do payload.text do backend (fallback do agente desktop
     * pra impressora genérica): translitera e envolve em ESC/POS mínimo.
     */
    fun plainText(text: String): ByteArray =
        byteArrayOf(ESC, '@'.code.toByte(), 0x1C, 0x2E) +
            ascii(text) +
            ascii("\n\n\n") +
            byteArrayOf(GS, 'V'.code.toByte(), 66, 0)
}
