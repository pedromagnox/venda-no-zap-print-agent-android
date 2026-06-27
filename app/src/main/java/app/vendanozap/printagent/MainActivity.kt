package app.vendanozap.printagent

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.vendanozap.printagent.core.AgentState
import app.vendanozap.printagent.core.Prefs
import app.vendanozap.printagent.core.PrinterConfig
import app.vendanozap.printagent.core.PrinterMode
import app.vendanozap.printagent.core.PrinterType
import app.vendanozap.printagent.core.Support
import app.vendanozap.printagent.net.ApiClient
import app.vendanozap.printagent.net.UnpairedException
import app.vendanozap.printagent.print.PrinterException
import app.vendanozap.printagent.print.Printers
import app.vendanozap.printagent.service.AgentForegroundService
import app.vendanozap.printagent.service.SyncEngine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Laranja = Color(0xFFF47527)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Laranja,
                    secondary = Laranja,
                ),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Root(prefs)
                    }
                }
            }
        }
        // Abrir o app é um evento: aproveita pra drenar pendências.
        AgentForegroundService.sync(this, trigger = "app_opened")
    }
}

private enum class Screen { PAIRING, PRINTER, PRINTER_MODE, STATUS }

@Composable
private fun Root(prefs: Prefs) {
    var screen by remember {
        mutableStateOf(
            when {
                !prefs.isPaired -> Screen.PAIRING
                prefs.printer == null -> Screen.PRINTER
                else -> Screen.STATUS
            },
        )
    }
    when (screen) {
        Screen.PAIRING -> PairingScreen(prefs) { screen = Screen.PRINTER }
        // Escolher a impressora leva ao teste guiado (acha o modo certo), e só
        // depois ao status. "Trocar impressora" reusa o mesmo caminho.
        Screen.PRINTER -> PrinterScreen(prefs) { screen = Screen.PRINTER_MODE }
        Screen.PRINTER_MODE -> PrinterModeWizard(prefs, onDone = { screen = Screen.STATUS })
        Screen.STATUS -> StatusScreen(
            prefs,
            onRepair = { screen = Screen.PAIRING },
            onChangePrinter = { screen = Screen.PRINTER },
            onRetest = { screen = Screen.PRINTER_MODE },
        )
    }
}

// ---------------------------------------------------------------- Pareamento

@Composable
private fun PairingScreen(prefs: Prefs, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Venda no Zap", color = Laranja, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("Conectar à sua loja", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Cole abaixo o token gerado no Venda no Zap", fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
            Column(Modifier.padding(12.dp)) {
                Text("DICA", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Laranja)
                Text(
                    "fica abaixo do botão que você usou para baixar esse aplicativo",
                    fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        Text("Token de conexão", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = token,
            onValueChange = {}, // ninguém digita um token desses — só Colar preenche
            readOnly = true, // input bloqueado pro teclado
            placeholder = { Text("Cole o token aqui") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Button(
                onClick = {
                    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                        ?.primaryClip
                    val pasted = clip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.text?.toString()?.trim()
                    if (!pasted.isNullOrBlank()) {
                        token = pasted; error = null
                    } else {
                        error = "Nada para colar — copie o token no painel primeiro."
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Colar") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { token = ""; error = null },
                enabled = token.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text("Limpar") }
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val api = ApiClient(context, prefs)
                        val res = api.exchange(token)
                        prefs.refreshToken = res.refreshToken
                        prefs.storeId = res.storeId
                        prefs.storeName = res.store?.name ?: ""
                        AgentState.setNeedsRepair(false)
                        AgentState.log("Pareado com ${res.store?.name ?: res.storeId}")
                        // FCM pode já ter token (app reinstalado): registra best-effort.
                        prefs.fcmToken?.let { api.registerFcmToken(it) }
                        onDone()
                    } catch (e: UnpairedException) {
                        error = "Código inválido ou revogado. Gere um novo no painel."
                    } catch (e: Exception) {
                        error = "Falha de conexão: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = token.startsWith("vnzpa_") && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Conectar")
        }
    }
}

// ---------------------------------------------------------------- Impressora

@SuppressLint("MissingPermission")
@Composable
private fun PrinterScreen(prefs: Prefs, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasBtPermission by remember { mutableStateOf(hasBluetoothPermission(context)) }
    var bonded by remember { mutableStateOf(listBonded(context, hasBtPermission)) }
    var tcpHost by remember { mutableStateOf("") }
    var showAllDevices by remember { mutableStateOf(false) }
    var showNetwork by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasBtPermission = hasBluetoothPermission(context)
        bonded = listBonded(context, hasBtPermission)
    }

    LaunchedEffect(Unit) {
        if (!hasBtPermission && Build.VERSION.SDK_INT >= 31) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        }
    }

    fun selectPrinter(config: PrinterConfig) {
        // Salva a impressora e vai pro teste guiado (que descobre o modo de
        // impressão e destrava o gate printerConnectedAt ao concluir). Sem
        // sondagem/heurística aqui — quem decide o modo é o lojista no wizard.
        prefs.printer = config
        AgentState.log("Impressora selecionada: ${config.name.ifBlank { config.address }}")
        onDone()
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Escolha a impressora", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Pareie a impressora térmica no Bluetooth do Android antes (Configurações → Bluetooth).",
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))

        if (!hasBtPermission && Build.VERSION.SDK_INT >= 31) {
            Button(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) }) {
                Text("Permitir acesso ao Bluetooth")
            }
        }

        val visible = if (showAllDevices) bonded else bonded.filter { it.likelyPrinter }
        val hiddenCount = bonded.size - bonded.count { it.likelyPrinter }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(visible) { (name, mac) ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    onClick = {
                        selectPrinter(PrinterConfig(PrinterType.BLUETOOTH, mac, name = name))
                    },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(name, fontWeight = FontWeight.Medium)
                        Text(mac, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            if (visible.isEmpty()) {
                item {
                    Text(
                        if (bonded.isEmpty())
                            "Nenhum dispositivo pareado. Pareie a impressora no Bluetooth do Android e volte."
                        else
                            "Nenhuma impressora reconhecida. Se a sua não apareceu, toque em \"Mostrar todos\".",
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }

        if (hiddenCount > 0 || showAllDevices) {
            TextButton(onClick = { showAllDevices = !showAllDevices }) {
                Text(
                    if (showAllDevices) "Mostrar só impressoras"
                    else "Mostrar todos os dispositivos ($hiddenCount ocultos)",
                    fontSize = 13.sp,
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // Rede TCP escondida como avançado: a maioria usa Bluetooth; expor o
        // campo de IP direto só confunde o lojista.
        TextButton(onClick = { showNetwork = !showNetwork }) {
            Text(
                if (showNetwork) "Ocultar impressora de rede" else "Impressora de rede (avançado)",
                fontSize = 13.sp,
            )
        }
        if (showNetwork) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = tcpHost,
                    onValueChange = { tcpHost = it.trim() },
                    label = { Text("IP (ex.: 192.168.0.50)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        selectPrinter(PrinterConfig(PrinterType.TCP, tcpHost, name = "Rede $tcpHost"))
                    },
                    enabled = tcpHost.isNotBlank(),
                ) { Text("Usar") }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private data class BondedDevice(val name: String, val mac: String, val likelyPrinter: Boolean)

@SuppressLint("MissingPermission")
private fun listBonded(context: Context, hasPermission: Boolean): List<BondedDevice> {
    if (Build.VERSION.SDK_INT >= 31 && !hasPermission) return emptyList()
    val adapter = Printers.bluetoothAdapter(context) ?: return emptyList()
    return try {
        adapter.bondedDevices.map {
            BondedDevice(it.name ?: "Dispositivo", it.address, isLikelyPrinter(it))
        }
    } catch (_: SecurityException) {
        emptyList()
    }
}

// Classes de dispositivo BT que NUNCA são impressora térmica (fone/caixa,
// teclado/mouse, telefone, relógio…). Filtra por EXCLUSÃO — não por "só
// IMAGING" — porque térmica chinesa barata costuma reportar classe genérica/
// Uncategorized; excluir só o que com certeza não é impressora evita esconder
// uma de verdade (e o botão "Mostrar todos" é a saída se ainda assim filtrar mal).
private val NON_PRINTER_MAJORS = setOf(
    BluetoothClass.Device.Major.AUDIO_VIDEO,
    BluetoothClass.Device.Major.PERIPHERAL,
    BluetoothClass.Device.Major.PHONE,
    BluetoothClass.Device.Major.WEARABLE,
    BluetoothClass.Device.Major.HEALTH,
    BluetoothClass.Device.Major.TOY,
)

@SuppressLint("MissingPermission")
private fun isLikelyPrinter(device: BluetoothDevice): Boolean {
    val major = device.bluetoothClass?.majorDeviceClass ?: return true
    return major !in NON_PRINTER_MAJORS
}

private fun hasBluetoothPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

// ------------------------------------------------------------------- Status

@Composable
private fun StatusScreen(
    prefs: Prefs,
    onRepair: () -> Unit,
    onChangePrinter: () -> Unit,
    onRetest: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val running by AgentState.serviceRunning.collectAsState()
    val lastSync by AgentState.lastSyncAt.collectAsState()
    val printed by AgentState.printedCount.collectAsState()
    val log by AgentState.log.collectAsState()
    val needsRepair by AgentState.needsRepair.collectAsState()
    val printerStatus by AgentState.printerStatus.collectAsState()
    var busy by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // Garante o serviço de fundo ligado quando o agente está configurado: o sync
    // do "app aberto" pode ter rodado antes do 1º pareamento/escolha de impressora
    // (setup feito na mesma sessão), deixando o serviço parado até reabrir o app.
    LaunchedEffect(Unit) { AgentForegroundService.sync(context, "status_opened") }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Spacer(Modifier.height(12.dp))
        Text(prefs.storeName ?: "Loja", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            prefs.printer?.let {
                val tipo = if (it.type == PrinterType.BLUETOOTH) "Bluetooth" else "Rede"
                "${it.name.ifBlank { it.address }} ($tipo · ${prefs.printerMode.label})"
            } ?: "Sem impressora",
            fontSize = 13.sp,
        )
        printerStatus?.let { ps ->
            Text(
                if (ps.ok) "● Impressora respondeu às ${ps.timeLabel}"
                else "● Sem conexão (${ps.timeLabel}) — ligue e aproxime a impressora",
                color = if (ps.ok) Color(0xFF2E7D32) else Color(0xFFC62828),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(10.dp))

        if (needsRepair) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE5E0))) {
                Column(Modifier.padding(12.dp)) {
                    Text("Pareamento inválido", fontWeight = FontWeight.Bold)
                    Text("Gere um novo código no painel e pareie de novo.", fontSize = 13.sp)
                    TextButton(onClick = { prefs.clearPairing(); onRepair() }) { Text("Re-parear") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (running) "● Serviço ativo" else "○ Serviço parado",
                        color = if (running) Color(0xFF2E7D32) else Color.Gray,
                        fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = running,
                        onCheckedChange = { on ->
                            if (on) AgentForegroundService.sync(context, "manual_start")
                            else AgentForegroundService.stop(context)
                        },
                    )
                }
                Text("Impressos nesta sessão: $printed", fontSize = 13.sp)
                Text(
                    "Último sync: " + (lastSync?.let {
                        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it))
                    } ?: "—"),
                    fontSize = 13.sp,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row {
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            prefs.printer?.let {
                                val bytes = ApiClient(context, prefs).testReceipt(prefs.printerMode.wire)
                                Printers.print(context, it, bytes)
                            }
                            AgentState.log("Teste de impressão enviado (${prefs.printerMode.label})")
                            AgentState.setPrinterOk()
                        } catch (e: Exception) {
                            AgentState.log("Teste falhou: ${e.message}", isError = true)
                            AgentState.setPrinterFailed(e.message ?: "Impressora fora de alcance ou desligada")
                        } finally { busy = false }
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Testando…")
                } else {
                    Text("Teste")
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row {
            TextButton(onClick = onChangePrinter) { Text("Trocar impressora") }
            Spacer(Modifier.weight(1f))
            BatteryButton()
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Tipo de impressão: ${prefs.printerMode.label}", fontSize = 13.sp)
                Text(
                    "Refaça o teste se o acento sair errado ou nada imprimir",
                    fontSize = 11.sp, color = Color.Gray,
                )
            }
            TextButton(onClick = onRetest) { Text("Refazer teste") }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Atividade", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { Support.openSupport(context, prefs, log) }) {
                Text("Enviar logs ao suporte", fontSize = 13.sp)
            }
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(log) { entry ->
                Text(
                    "${entry.timeLabel}  ${entry.message}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (entry.isError) MaterialTheme.colorScheme.error else Color.Unspecified,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------ Teste guiado (modo)

private enum class WizStep { PRINTING, ASK_PRINTED, ASK_QUALITY, PROBLEM, DEAD_END }

// Pergunta de QUALIDADE por modo. No raster o acento é garantido (é imagem),
// então a pergunta é sobre nitidez/integridade; o ASCII é o resgate legível.
private fun qualityQuestion(mode: PrinterMode): String = when (mode) {
    PrinterMode.ESCPOS -> "Os acentos saíram certos? (ç, ã, é, ô)"
    PrinterMode.RASTER -> "O cupom saiu nítido e completo?"
    PrinterMode.ASCII -> "O cupom saiu legível (mesmo sem acento)?"
}

// Escala: Texto (rápido) → Imagem (acento garantido) → Simples (sem acento).
private fun nextMode(mode: PrinterMode): PrinterMode? = when (mode) {
    PrinterMode.ESCPOS -> PrinterMode.RASTER
    PrinterMode.RASTER -> PrinterMode.ASCII
    PrinterMode.ASCII -> null
}

/**
 * Onboarding guiado do modo de impressão: imprime um cupom de teste, pergunta
 * "saiu?" e "acentos certos?", e escala de modo sozinho até o lojista confirmar.
 * O lojista nunca vê "ESC/POS/raster/ascii" — só responde como o cupom saiu.
 */
@Composable
private fun PrinterModeWizard(prefs: Prefs, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val log by AgentState.log.collectAsState()
    var mode by remember { mutableStateOf(PrinterMode.ESCPOS) }
    var step by remember { mutableStateOf(WizStep.PRINTING) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun printTest(m: PrinterMode) {
        mode = m; step = WizStep.PRINTING; problem = null
        scope.launch {
            val printer = prefs.printer
            if (printer == null) {
                problem = "Impressora não configurada."; step = WizStep.PROBLEM; return@launch
            }
            try {
                val bytes = ApiClient(context, prefs).testReceipt(m.wire)
                Printers.print(context, printer, bytes)
                step = WizStep.ASK_PRINTED
            } catch (e: PrinterException) {
                problem = e.message ?: "Impressora fora de alcance ou desligada"
                step = WizStep.PROBLEM
            } catch (e: Exception) {
                problem = "Não foi possível gerar o teste — confira a internet do celular. (${e.message})"
                step = WizStep.PROBLEM
            }
        }
    }

    fun finish(m: PrinterMode) {
        prefs.printerMode = m
        AgentState.log("Modo de impressão definido: ${m.label} (${m.wire})")
        scope.launch {
            // Concluir o teste destrava o gate printerConnectedAt e já drena a fila.
            prefs.printer?.let { p ->
                runCatching {
                    SyncEngine(context, prefs, ApiClient(context, prefs))
                        .ensurePrinterReadyReported(p.name, p.type.name.lowercase())
                }
            }
            AgentForegroundService.sync(context, "mode_selected")
            onDone()
        }
    }

    LaunchedEffect(Unit) { printTest(PrinterMode.ESCPOS) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("Teste da impressora", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Vamos imprimir um cupom de teste e achar o ajuste certo da sua impressora. " +
                "É só dizer como o cupom saiu.",
            fontSize = 13.sp, color = Color.Gray,
        )
        Spacer(Modifier.height(24.dp))

        when (step) {
            WizStep.PRINTING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Imprimindo o teste…", fontSize = 15.sp)
            }

            WizStep.ASK_PRINTED -> WizQuestion(
                "Saiu o cupom de teste na impressora?",
                onYes = { step = WizStep.ASK_QUALITY },
                onNo = { problem = "O cupom não saiu na impressora."; step = WizStep.PROBLEM },
            )

            WizStep.ASK_QUALITY -> WizQuestion(
                qualityQuestion(mode),
                yesLabel = "Sim, ficou bom",
                noLabel = "Não / saiu errado",
                onYes = { finish(mode) },
                onNo = {
                    val n = nextMode(mode)
                    if (n == null) step = WizStep.DEAD_END else printTest(n)
                },
            )

            WizStep.PROBLEM -> {
                Text(problem ?: "Algo deu errado.", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Confira: impressora ligada, com papel, pareada no Bluetooth e por perto. Depois tente de novo.",
                    fontSize = 13.sp, color = Color.Gray,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = { printTest(mode) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Imprimir de novo")
                }
                nextMode(mode)?.let { n ->
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { printTest(n) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Tentar outro tipo de impressão")
                    }
                }
            }

            WizStep.DEAD_END -> {
                Text("Não achamos um cupom legível", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Costuma ser conexão ou a própria impressora. Envie os logs pro suporte que a gente te ajuda.",
                    fontSize = 13.sp, color = Color.Gray,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { Support.openSupport(context, prefs, log) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Enviar logs ao suporte") }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { printTest(PrinterMode.ESCPOS) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Começar de novo") }
                Spacer(Modifier.height(10.dp))
                TextButton(
                    onClick = { finish(PrinterMode.ESCPOS) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Concluir mesmo assim") }
            }
        }
    }
}

@Composable
private fun WizQuestion(
    question: String,
    yesLabel: String = "Sim",
    noLabel: String = "Não",
    onYes: () -> Unit,
    onNo: () -> Unit,
) {
    Text(question, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(20.dp))
    Button(onClick = onYes, modifier = Modifier.fillMaxWidth()) { Text(yesLabel) }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = onNo, modifier = Modifier.fillMaxWidth()) { Text(noLabel) }
}

@Composable
private fun BatteryButton() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    var ignored by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }
    if (!ignored) {
        TextButton(onClick = {
            @SuppressLint("BatteryLife")
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            )
            context.startActivity(intent)
            ignored = pm.isIgnoringBatteryOptimizations(context.packageName)
        }) { Text("Liberar bateria") }
    }
}
