package com.quicksend.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

fun fmt(b: Long): String {
    if (b < 1024) return "$b B"
    val u = arrayOf("KB", "MB", "GB", "TB")
    var v = b.toDouble(); var i = -1
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return String.format("%.1f %s", v, u[i])
}

fun startSvc(ctx: Context, action: String, wifi: Boolean = true, bt: Boolean = true) {
    ContextCompat.startForegroundService(
        ctx,
        Intent(ctx, TransferService::class.java).setAction(action)
            .putExtra("wifi", wifi).putExtra("bt", bt)
    )
}

fun parseManual(s: String): Peer? {
    val t = s.trim()
    if (t.isEmpty()) return null
    val parts = t.split(":")
    val port = parts.getOrNull(1)?.toIntOrNull() ?: Proto.PORT
    return Peer(parts[0], parts[0], port)
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
fun App() {
    var tab by remember { mutableIntStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) { permLauncher.launch(Perms.needed()) }
    val progress by Engine.progress.collectAsState()

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        Text("QuickSend", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Send") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Receive") })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (tab == 0) SendTab() else ReceiveTab()
            ProgressCard(progress)
            LogCard()
        }
    }
}

@Composable
fun SendTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sel by Engine.selection.collectAsState()
    val progress by Engine.progress.collectAsState()
    var indexing by remember { mutableStateOf(false) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            indexing = true
            val e = withContext(Dispatchers.IO) { FileSource.fromFiles(ctx, uris) }
            Engine.selection.update { it + e }
            indexing = false
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            indexing = true
            val e = withContext(Dispatchers.IO) { FileSource.fromTree(ctx, uri) }
            Engine.selection.update { it + e }
            indexing = false
        }
    }

    // --- Wi-Fi targets
    val nsd = remember { NsdScanner(ctx.applicationContext) }
    DisposableEffect(Unit) { nsd.start(); onDispose { nsd.stop() } }
    val peers by nsd.peers.collectAsState()
    var wifiOn by rememberSaveable { mutableStateOf(true) }
    var selPeerName by rememberSaveable { mutableStateOf<String?>(null) }
    var manualIp by rememberSaveable { mutableStateOf("") }

    // --- Bluetooth targets
    val bt = remember { BtScanner(ctx.applicationContext) }
    DisposableEffect(Unit) { bt.refreshBonded(); onDispose { bt.stop() } }
    val devs by bt.devices.collectAsState()
    val btScanning by bt.scanning.collectAsState()
    var btOn by rememberSaveable { mutableStateOf(false) }
    var selBt by rememberSaveable { mutableStateOf<String?>(null) }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        bt.refreshBonded()
    }

    Section("1. Choose what to send") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pickFiles.launch(arrayOf("*/*")) }) { Text("Add files") }
            Button(onClick = { pickFolder.launch(null) }) { Text("Add folder") }
            OutlinedButton(onClick = { Engine.selection.value = emptyList() }) { Text("Clear") }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (indexing) "Reading folder…"
            else "${sel.size} file(s) • ${fmt(sel.sumOf { it.size })}"
        )
    }

    Section("2. Wi‑Fi") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = wifiOn, onCheckedChange = { wifiOn = it })
            Spacer(Modifier.width(8.dp)); Text("Send over Wi‑Fi")
        }
        if (wifiOn) {
            Text(
                "Both phones on the same Wi‑Fi, or one on the other's hotspot.",
                style = MaterialTheme.typography.bodySmall
            )
            if (peers.isEmpty()) Text("Searching for receivers…", style = MaterialTheme.typography.bodySmall)
            peers.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { selPeerName = p.name },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selPeerName == p.name, onClick = { selPeerName = p.name })
                    Column { Text(p.name); Text(p.host, style = MaterialTheme.typography.bodySmall) }
                }
            }
            OutlinedTextField(
                value = manualIp,
                onValueChange = { manualIp = it; selPeerName = null },
                label = { Text("…or type receiver IP (e.g. 192.168.1.20)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    Section("3. Bluetooth") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = btOn, onCheckedChange = { on ->
                btOn = on
                if (on) {
                    if (!bt.enabled()) enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    bt.refreshBonded()
                }
            })
            Spacer(Modifier.width(8.dp)); Text("Send over Bluetooth")
        }
        if (btOn) {
            Text(
                "Pick the receiver (paired, or press Scan while it is discoverable). Turn on Wi‑Fi too to use both at once.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = { bt.scan() }) { Text(if (btScanning) "Scanning…" else "Scan for devices") }
            devs.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().clickable { selBt = d.address },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selBt == d.address, onClick = { selBt = d.address })
                    Column { Text(d.name); Text(d.address, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }

    val wifiTarget: Peer? =
        if (wifiOn) (selPeerName?.let { n -> peers.find { it.name == n } } ?: parseManual(manualIp)) else null
    val btTarget: String? = if (btOn) selBt else null
    val label = when {
        wifiTarget != null && btTarget != null -> "Send via Wi‑Fi + Bluetooth"
        wifiTarget != null -> "Send via Wi‑Fi"
        btTarget != null -> "Send via Bluetooth"
        else -> "Choose a receiver"
    }
    Button(
        onClick = {
            Engine.pendingSend = SendRequest(sel, wifiTarget, btTarget)
            startSvc(ctx, TransferService.ACTION_SEND)
        },
        enabled = sel.isNotEmpty() && (wifiTarget != null || btTarget != null) &&
                !(progress.active && progress.direction == "send"),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) { Text(label) }
}

@Composable
fun ReceiveTab() {
    val ctx = LocalContext.current
    val receiving by Engine.receiving.collectAsState()
    var wifi by rememberSaveable { mutableStateOf(true) }
    var bt by rememberSaveable { mutableStateOf(true) }
    val ips = remember { Net.localIps() }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        startSvc(ctx, TransferService.ACTION_START_RECEIVE, wifi, bt)
    }

    Section("Receive files") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = wifi, onCheckedChange = { wifi = it }, enabled = !receiving)
            Spacer(Modifier.width(8.dp)); Text("Wi‑Fi")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = bt, onCheckedChange = { bt = it }, enabled = !receiving)
            Spacer(Modifier.width(8.dp)); Text("Bluetooth")
        }
        Spacer(Modifier.height(8.dp))
        if (!receiving) {
            Button(onClick = {
                val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
                if (bt && adapter != null && !adapter.isEnabled)
                    enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                else startSvc(ctx, TransferService.ACTION_START_RECEIVE, wifi, bt)
            }, modifier = Modifier.fillMaxWidth()) { Text("Start receiving") }
        } else {
            Button(onClick = { startSvc(ctx, TransferService.ACTION_STOP_RECEIVE) },
                modifier = Modifier.fillMaxWidth()) { Text("Stop receiving") }
        }
        if (bt) {
            OutlinedButton(onClick = {
                ctx.startActivity(
                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                )
            }, modifier = Modifier.fillMaxWidth()) { Text("Make Bluetooth discoverable (5 min)") }
        }
        Spacer(Modifier.height(8.dp))
        Text("Your IP: " + if (ips.isEmpty()) "not connected to Wi‑Fi" else ips.joinToString())
        Text("Saved to: Downloads/QuickSend", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ProgressCard(p: Progress) {
    if (p.direction.isEmpty()) return
    Section(if (p.direction == "send") "Sending" else "Receiving") {
        val frac = if (p.totalBytes > 0) (p.doneBytes.toFloat() / p.totalBytes).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        Text("${p.doneFiles}/${p.totalFiles} files • ${fmt(p.doneBytes)} / ${fmt(p.totalBytes)}")
        Text(
            "${fmt(p.speedBps)}/s • Wi‑Fi ${fmt(p.wifiBytes)} • Bluetooth ${fmt(p.btBytes)}",
            style = MaterialTheme.typography.bodySmall
        )
        if (p.current.isNotEmpty() && !p.finished)
            Text(p.current, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        if (p.finished && p.error == null) Text("Done ✓", fontWeight = FontWeight.Bold)
        p.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun LogCard() {
    val lines by Engine.logLines.collectAsState()
    if (lines.isEmpty()) return
    Section("Log") {
        lines.takeLast(8).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
