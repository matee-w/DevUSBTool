package com.devtool.usbmode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppScreen()
                }
            }
        }
    }
}

@Composable
fun AppScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var canWrite by remember { mutableStateOf(DevSettingsRepo.canWrite(context)) }
    var values by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var usb by remember { mutableStateOf<UsbStatus?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            busy = true
            canWrite = DevSettingsRepo.canWrite(context)
            val (v, u) = withContext(Dispatchers.IO) {
                DEV_SETTINGS.associate { it.key to DevSettingsRepo.read(context.contentResolver, it) } to
                    UsbModeRepo.load(context)
            }
            values = v
            usb = u
            busy = false
        }
    }

    // Reload whenever the app comes back to the front (e.g. after using a system toggle).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun writeSetting(s: DevSetting, value: String) {
        scope.launch {
            val err = withContext(Dispatchers.IO) { DevSettingsRepo.write(context.contentResolver, s, value) }
            if (err != null) toast(context, err)
            refresh()
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DevUSB Tool", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(24.dp).padding(end = 8.dp), strokeWidth = 2.dp)
            Button(onClick = { refresh() }) { Text("Refresh") }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                DevSettingsCard(canWrite, values, ::writeSetting)
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                UsbCard(usb, onSwitch = { node, role ->
                    scope.launch {
                        val err = withContext(Dispatchers.IO) { UsbModeRepo.switchWithRoot(node, role) }
                        toast(context, err ?: "Switched to ${role.label}")
                        refresh()
                    }
                })
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        SectionTitle("Diagnostics")
                        Text("Copies device info, readable USB paths and matched vendor apps to the clipboard.",
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            scope.launch {
                                val text = withContext(Dispatchers.IO) { UsbModeRepo.diagnostics(context, usb, values) }
                                copy(context, "DevUSB diagnostics", text)
                            }
                        }) { Text("Copy diagnostics") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DevSettingsCard(canWrite: Boolean, values: Map<String, String?>, onWrite: (DevSetting, String) -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Developer settings")
            if (!canWrite) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.padding(12.dp).fillMaxWidth()) {
                        Text("Read-only: WRITE_SECURE_SETTINGS not granted",
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text("Run this once from a computer (ADB over network works), then tap Refresh:",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(6.dp))
                        val cmd = DevSettingsRepo.grantCommand(context)
                        SelectionContainer {
                            Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { copy(context, "grant command", cmd) }) { Text("Copy command") }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            DEV_SETTINGS.forEach { s ->
                SettingRow(s, values[s.key], enabled = canWrite, onWrite = { onWrite(s, it) })
                HorizontalDivider()
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { openIntent(context, UsbModeRepo.developerOptionsIntent()) },
                modifier = Modifier.fillMaxWidth()) { Text("Open system Developer options") }
        }
    }
}

@Composable
private fun SettingRow(s: DevSetting, raw: String?, enabled: Boolean, onWrite: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.label, fontSize = 16.sp)
            Text(s.key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            s.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
        }
        when (val k = s.kind) {
            is Kind.Toggle -> Switch(
                checked = DevSettingsRepo.isOn(raw),
                enabled = enabled,
                onCheckedChange = { onWrite(if (it) k.onValue else "0") },
            )
            is Kind.Choice -> {
                var open by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { open = true }, enabled = enabled) {
                        Text(DevSettingsRepo.choiceLabel(k, raw))
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        k.options.forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onWrite(value) })
                        }
                    }
                }
            }
            Kind.ReadOnly -> Text(raw ?: "Off", fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun UsbCard(usb: UsbStatus?, onSwitch: (UsbNode, UsbRole) -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("USB mode")
            if (usb == null) {
                Text("Loading…"); return@Column
            }
            val primary = usb.primary
            Text("Current role", style = MaterialTheme.typography.labelMedium)
            Text(
                primary?.role?.label ?: "Unknown (blocked)",
                fontSize = 28.sp, fontWeight = FontWeight.Bold,
                color = when (primary?.role) {
                    UsbRole.HOST -> MaterialTheme.colorScheme.tertiary
                    UsbRole.DEVICE -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            primary?.let { Text(it.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
            Text("sys.usb.state: ${usb.usbState.ifEmpty { "-" }}   config: ${usb.usbConfig.ifEmpty { "-" }}",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))

            val target = primary ?: usb.nodes.firstOrNull { it.exists }
            if (usb.hasRoot && target != null) {
                Text("Root detected. Switch directly:", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(UsbRole.HOST, UsbRole.DEVICE, UsbRole.OTG).forEach { role ->
                        if (writeValue(target.type, role) != null) {
                            Button(onClick = { onSwitch(target, role) }, modifier = Modifier.weight(1f)) { Text(role.label) }
                        }
                    }
                }
            } else {
                Text("Switching needs root, which this device doesn't have. " +
                    "Use the system's own toggle instead: look for \"Connect to PC\", \"USB mode\" or \"OTG\".",
                    style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { openIntent(context, UsbModeRepo.developerOptionsIntent()) }, Modifier.fillMaxWidth()) {
                Text("Open Developer options")
            }

            Spacer(Modifier.height(12.dp))
            Text("Vendor / factory settings apps", fontWeight = FontWeight.SemiBold)
            if (usb.vendorApps.isEmpty()) {
                Text("None found. Factory menus are often opened from Car Settings with a code.",
                    style = MaterialTheme.typography.bodySmall)
            }
            usb.vendorApps.forEach { app ->
                OutlinedButton(
                    onClick = {
                        context.packageManager.getLaunchIntentForPackage(app.packageName)?.let { openIntent(context, it) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(app.label)
                        Text(app.packageName, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            var showPaths by remember { mutableStateOf(false) }
            TextButton(onClick = { showPaths = !showPaths }) {
                Text(if (showPaths) "Hide checked paths" else "Show checked paths (${usb.nodes.size})")
            }
            if (showPaths) {
                usb.nodes.forEach { n ->
                    Text(n.path, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text(
                        when {
                            n.readable -> "   = ${n.raw} → ${n.role.label}"
                            n.exists -> "   exists, not readable (${n.error})"
                            else -> "   not found"
                        },
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
}

private fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

private fun copy(context: Context, label: String, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    toast(context, "Copied")
}

private fun openIntent(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        toast(context, "Can't open: ${e.message}")
    }
}
