package app.vendanozap.printagent.print

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
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
            val socket = connectRfcomm(adapter, device)
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

    // Cada socket.connect() é limitado por ATTEMPT, e a rotina toda por OVERALL.
    // socket.connect() pode pendurar (clone que não responde) e não tem timeout
    // nativo. Bound garante: (a) falha rápida e previsível, dentro do lease de
    // 2min do servidor (sem 409 por lease expirado); (b) o botão Teste nunca fica
    // preso. Conexão legítima resolve em poucos segundos, bem abaixo do limite.
    private const val CONNECT_ATTEMPT_TIMEOUT_MS = 12_000L
    private const val CONNECT_OVERALL_TIMEOUT_MS = 40_000L

    /**
     * Conecta no RFCOMM da térmica tentando estratégias em ordem. Clonas baratas
     * (MPT-II, PT-2xx, ZJ…) NÃO expõem o SDP do SPP e morrem no caminho padrão com
     * "read failed, socket might closed or timeout, read ret: -1". Ordem:
     *   1. cancelDiscovery() — discovery em curso mata o connect.
     *   2. SPP seguro (SDP) → 3. SPP inseguro (SDP) → 4. canal 1 direto via
     *      reflexão (createRfcommSocket), que PULA o SDP que essas clonas não têm.
     * Fecha o socket a cada falha; uma 2ª rodada (com pausa) cobre o caso "1ª
     * conexão ok, reconexão falha" típico dessas impressoras. A reflexão pode ser
     * barrada por hidden-API em Android novo — aí simplesmente cai fora (não piora).
     * Mensagem de erro é amigável ("fora de alcance ou desligada"): o "read ret -1"
     * cru não diz nada pro lojista, e a causa real é sempre alcance/energia.
     */
    @SuppressLint("MissingPermission")
    private fun connectRfcomm(adapter: BluetoothAdapter, device: BluetoothDevice): BluetoothSocket {
        val deadline = System.currentTimeMillis() + CONNECT_OVERALL_TIMEOUT_MS
        repeat(2) { attempt ->
            runCatching { adapter.cancelDiscovery() }
            for (makeSocket in rfcommStrategies(device)) {
                if (System.currentTimeMillis() >= deadline) {
                    throw PrinterException("BT_CONNECT_FAILED", "Impressora fora de alcance ou desligada")
                }
                val socket = runCatching { makeSocket() }.getOrNull() ?: continue
                try {
                    connectWithTimeout(socket, CONNECT_ATTEMPT_TIMEOUT_MS)
                    return socket
                } catch (e: Exception) {
                    runCatching { socket.close() }
                }
            }
            if (attempt == 0) Thread.sleep(400)
        }
        throw PrinterException("BT_CONNECT_FAILED", "Impressora fora de alcance ou desligada")
    }

    /**
     * BluetoothSocket.connect() não aceita timeout e pode bloquear por minutos num
     * clone que não responde. Roda o connect numa thread; se passar do prazo, fecha
     * o socket (o que desbloqueia o connect pendurado com IOException) e desiste.
     */
    private fun connectWithTimeout(socket: BluetoothSocket, timeoutMs: Long) {
        var err: Exception? = null
        val t = Thread {
            try { socket.connect() } catch (e: Exception) { err = e }
        }.apply { isDaemon = true; start() }
        t.join(timeoutMs)
        if (t.isAlive) {
            runCatching { socket.close() } // desbloqueia o connect pendurado
            t.join(1_000)
            throw IOException("connect timeout")
        }
        err?.let { throw it }
    }

    @SuppressLint("MissingPermission")
    private fun rfcommStrategies(device: BluetoothDevice): List<() -> BluetoothSocket> = listOf(
        { device.createRfcommSocketToServiceRecord(SPP_UUID) },
        { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
        {
            device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                .invoke(device, 1) as BluetoothSocket
        },
    )
}

