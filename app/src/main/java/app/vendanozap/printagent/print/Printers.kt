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
        withContext(Dispatchers.IO) {
            when (config.type) {
                PrinterType.BLUETOOTH -> printBluetooth(context, config.address, bytes)
                PrinterType.TCP -> printTcp(config.address, config.port, bytes)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun printBluetooth(context: Context, mac: String, bytes: ByteArray) {
        val adapter = bluetoothAdapter(context)
            ?: throw PrinterException("BT_UNAVAILABLE", "Bluetooth indisponível neste aparelho")
        if (!adapter.isEnabled) throw PrinterException("BT_OFF", "Bluetooth desligado")
        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            throw PrinterException("BT_BAD_ADDRESS", "Endereço Bluetooth inválido: $mac")
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
        socket.use { s ->
            try {
                s.outputStream.writeChunked(bytes)
                // Margem pro buffer interno drenar antes do close cortar o link.
                Thread.sleep(150)
            } catch (e: IOException) {
                throw PrinterException("BT_WRITE_FAILED", e.message ?: "Falha ao enviar dados")
            }
        }
    }

    private fun printTcp(host: String, port: Int, bytes: ByteArray) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.soTimeout = 5000
                socket.getOutputStream().writeChunked(bytes, chunk = 4096, pauseMs = 0)
            }
        } catch (e: IOException) {
            throw PrinterException("TCP_FAILED", "${host}:${port} — ${e.message}")
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

    private fun encode(text: String): ByteArray {
        val out = ByteArray(text.length)
        for (i in text.indices) {
            val c = text[i]
            out[i] = when {
                c.code < 0x80 -> c.code.toByte()
                CP858.containsKey(c) -> CP858.getValue(c).toByte()
                else -> '?'.code.toByte()
            }
        }
        return out
    }

    fun build(storeName: String?): ByteArray {
        val esc = 0x1B
        val gs = 0x1D
        val head = byteArrayOf(
            esc.toByte(), '@'.code.toByte(),            // init
            esc.toByte(), 't'.code.toByte(), 19,        // codepage 19 = CP858
            esc.toByte(), 'a'.code.toByte(), 1,         // center
        )
        val body = encode(
            buildString {
                append("VENDA NO ZAP\n")
                append("Impressão de teste\n")
                if (!storeName.isNullOrBlank()) append("$storeName\n")
                append("--------------------------------\n")
                append("Acentuação: ÁÉÍÓÚ áéíóú ãõ çÇ ê ô\n")
                append("Impressora conectada com sucesso!\n\n\n\n")
            },
        )
        val cut = byteArrayOf(gs.toByte(), 'V'.code.toByte(), 66, 0) // partial cut + feed
        return head + body + cut
    }
}
