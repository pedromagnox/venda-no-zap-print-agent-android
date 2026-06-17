package app.vendanozap.printagent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
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
import app.vendanozap.printagent.core.PrinterType
import app.vendanozap.printagent.net.ApiClient
import app.vendanozap.printagent.net.UnpairedException
import app.vendanozap.printagent.print.Printers
import app.vendanozap.printagent.print.TestReceipt
import app.vendanozap.printagent.service.AgentForegroundService
import app.vendanozap.printagent.update.AndroidLatest
import app.vendanozap.printagent.update.Updater
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Laranja = Color(0xFFF47527)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Laranja,
                    secondary = Laranja,
                ),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Root(prefs)
                }
            }
        }
        // Abrir o app é um evento: aproveita pra drenar pendências.
        AgentForegroundService.sync(this, trigger = "app_opened")
    }
}

private enum class Screen { PAIRING, PRINTER, STATUS }

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
        Screen.PRINTER -> PrinterScreen(prefs) { screen = Screen.STATUS }
        Screen.STATUS -> StatusScreen(
            prefs,
            onRepair = { screen = Screen.PAIRING },
            onChangePrinter = { screen = Screen.PRINTER },
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
        Text("Print Agent", fontSize = 20.sp)
        Spacer(Modifier.height(24.dp))
        Text(
            "No painel da sua loja, abra Impressão → Print Agent e copie o código de pareamento.",
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim() },
            label = { Text("Código de pareamento (vnzpa_…)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
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
            else Text("Parear")
        }
    }
}

// ---------------------------------------------------------------- Impressora

@SuppressLint("MissingPermission")
@Composable
private fun PrinterScreen(prefs: Prefs, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var hasBtPermission by remember { mutableStateOf(hasBluetoothPermission(context)) }
    var bonded by remember { mutableStateOf(listBonded(context, hasBtPermission)) }
    var tcpHost by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

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

    fun testAndSave(config: PrinterConfig) {
        busy = true; status = "Identificando impressora…"
        scope.launch {
            try {
                // Sonda GS I + heurística de nome decidem o modo sozinhas
                // (análogo Android da detecção por VID do agente Windows).
                val probe = Printers.probeAndTest(context, config, prefs.storeName)
                prefs.printer = config
                prefs.asciiMode = probe.asciiMode
                AgentState.log(
                    "Impressora: ${probe.identity ?: config.name.ifBlank { config.address }} → " +
                        if (probe.asciiMode) "modo sem acentos (genérica)" else "modo normal (CP858)",
                )
                // Destrava o gate printerConnectedAt já na configuração.
                runCatching {
                    app.vendanozap.printagent.service.SyncEngine(context, prefs, ApiClient(context, prefs))
                        .ensurePrinterReadyReported(
                            config.name,
                            config.type.name.lowercase(),
                            probe.identity,
                        )
                }
                status = null
                onDone()
            } catch (e: Exception) {
                status = "Falha no teste: ${e.message}"
            } finally {
                busy = false
            }
        }
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

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(bonded) { (name, mac) ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    onClick = {
                        if (!busy) testAndSave(PrinterConfig(PrinterType.BLUETOOTH, mac, name = name))
                    },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(name, fontWeight = FontWeight.Medium)
                        Text(mac, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Ou impressora de rede (TCP 9100):", fontSize = 13.sp)
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
                    if (!busy) testAndSave(PrinterConfig(PrinterType.TCP, tcpHost, name = "Rede $tcpHost"))
                },
                enabled = tcpHost.isNotBlank() && !busy,
            ) { Text("Testar") }
        }
        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@SuppressLint("MissingPermission")
private fun listBonded(context: Context, hasPermission: Boolean): List<Pair<String, String>> {
    if (Build.VERSION.SDK_INT >= 31 && !hasPermission) return emptyList()
    val adapter = Printers.bluetoothAdapter(context) ?: return emptyList()
    return try {
        adapter.bondedDevices.map { (it.name ?: "Dispositivo") to it.address }
    } catch (_: SecurityException) {
        emptyList()
    }
}

private fun hasBluetoothPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

// ------------------------------------------------------------------- Status

@Composable
private fun StatusScreen(prefs: Prefs, onRepair: () -> Unit, onChangePrinter: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val running by AgentState.serviceRunning.collectAsState()
    val lastSync by AgentState.lastSyncAt.collectAsState()
    val printed by AgentState.printedCount.collectAsState()
    val log by AgentState.log.collectAsState()
    val needsRepair by AgentState.needsRepair.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AndroidLatest?>(null) }

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
    // Checa atualização ao abrir a tela (evento, sem timer). Best-effort.
    LaunchedEffect(Unit) { update = Updater.check() }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Spacer(Modifier.height(12.dp))
        Text(prefs.storeName ?: "Loja", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            prefs.printer?.let {
                val tipo = if (it.type == PrinterType.BLUETOOTH) "Bluetooth" else "Rede"
                val modo = if (prefs.asciiMode) ", sem acentos" else ""
                "${it.name.ifBlank { it.address }} ($tipo$modo)"
            } ?: "Sem impressora",
            fontSize = 13.sp,
        )
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

        update?.let { upd ->
            var updating by remember { mutableStateOf(false) }
            var updError by remember { mutableStateOf<String?>(null) }
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                Column(Modifier.padding(12.dp)) {
                    Text("Atualização disponível", fontWeight = FontWeight.Bold)
                    Text("Versão ${upd.versionName} — recomendado atualizar.", fontSize = 13.sp)
                    updError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    TextButton(
                        enabled = !updating,
                        onClick = {
                            updating = true; updError = null
                            scope.launch {
                                try {
                                    val apk = Updater.download(context, upd)
                                    Updater.install(context, apk)
                                } catch (e: Exception) {
                                    updError = "Falha ao baixar: ${e.message}"
                                } finally { updating = false }
                            }
                        },
                    ) { Text(if (updating) "Baixando…" else "Atualizar agora") }
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
            Button(
                onClick = { AgentForegroundService.sync(context, "manual") },
                modifier = Modifier.weight(1f),
            ) { Text("Sincronizar agora") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            prefs.printer?.let {
                                val receipt = if (prefs.asciiMode) TestReceipt.buildAscii(prefs.storeName)
                                else TestReceipt.build(prefs.storeName)
                                Printers.print(context, it, receipt)
                            }
                            AgentState.log("Teste de impressão enviado")
                        } catch (e: Exception) {
                            AgentState.log("Teste falhou: ${e.message}", isError = true)
                        } finally { busy = false }
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Teste") }
        }

        Spacer(Modifier.height(6.dp))
        Row {
            TextButton(onClick = onChangePrinter) { Text("Trocar impressora") }
            Spacer(Modifier.weight(1f))
            BatteryButton()
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Atividade", fontWeight = FontWeight.Medium, fontSize = 14.sp)
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
