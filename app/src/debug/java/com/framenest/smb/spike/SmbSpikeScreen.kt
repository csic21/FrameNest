package com.framenest.smb.spike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.framenest.smb.SmbBenchmark
import com.framenest.smb.SmbBuildConfigDefaults
import com.framenest.smb.SmbCredentials
import com.framenest.smb.SmbErrorMapper
import com.framenest.smb.SmbException
import com.framenest.smb.SmbLog
import com.framenest.smb.SmbjClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SmbSpikeScreen(modifier: Modifier = Modifier) {
    val client = remember { SmbjClient() }
    DisposableEffect(client) {
        onDispose { client.close() }
    }

    var host by remember { mutableStateOf(SmbBuildConfigDefaults.host()) }
    var port by remember { mutableStateOf(SmbBuildConfigDefaults.port().toString()) }
    var username by remember { mutableStateOf(SmbBuildConfigDefaults.username()) }
    var password by remember { mutableStateOf(String(SmbBuildConfigDefaults.passwordChars())) }
    var domain by remember { mutableStateOf(SmbBuildConfigDefaults.domain()) }
    var share by remember { mutableStateOf(SmbBuildConfigDefaults.share()) }
    var path by remember { mutableStateOf(SmbBuildConfigDefaults.path().ifBlank { "/" }) }
    var testFile by remember { mutableStateOf(SmbBuildConfigDefaults.testFile()) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf(initialHelp()) }

    val scope = rememberCoroutineScope()

    fun append(line: String) {
        val safe = line // credentials never appended by callers below
        log = (log + "\n" + safe).takeLast(12_000)
        SmbLog.i(safe)
    }

    fun runAction(label: String, block: () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    block()
                }
                append("OK $label\n$result")
            } catch (t: Throwable) {
                val error = when (t) {
                    is SmbException -> t.error
                    else -> SmbErrorMapper.map(t)
                }
                append("FAIL $label type=${error::class.simpleName} msg=${error.message}")
            } finally {
                busy = false
            }
        }
    }

    fun credentials(): SmbCredentials =
        SmbCredentials(
            host = host.trim(),
            port = port.toIntOrNull() ?: SmbCredentials.DEFAULT_PORT,
            username = username,
            password = password.toCharArray(),
            domain = domain,
        )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .semantics { contentDescription = "smb_spike_screen" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("FN-02 SMB Spike", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Credentials stay in memory / BuildConfig only. Never embed password in smb:// URLs.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Port") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = domain,
            onValueChange = { domain = it },
            label = { Text("Domain (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = share,
            onValueChange = { share = it },
            label = { Text("Share") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text("Directory path") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = testFile,
            onValueChange = { testFile = it },
            label = { Text("Test file (for read/bench)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        if (busy) {
            CircularProgressIndicator()
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    runAction("connect") {
                        client.connect(credentials())
                        "connected=${client.isConnected} ${credentials().safeSummary()}"
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Connect") }
            Button(
                onClick = {
                    runAction("disconnect") {
                        client.disconnect()
                        "disconnected connected=${client.isConnected}"
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Disconnect") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    runAction("listShares") {
                        if (!client.isConnected) client.connect(credentials())
                        val known = share.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        val shares = client.listShares(known)
                        if (shares.isEmpty()) {
                            "shares=[] (provide share name(s); MS-SRVS enum not used in spike)"
                        } else {
                            "shares=${shares.joinToString()}"
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Shares") }
            Button(
                onClick = {
                    runAction("listDir") {
                        if (!client.isConnected) client.connect(credentials())
                        val entries = client.listDirectory(share.trim(), path)
                        buildString {
                            append("count=${entries.size}\n")
                            entries.take(80).forEach { e ->
                                val kind = if (e.isDirectory) "D" else "F"
                                append("$kind ${e.name} size=${e.sizeBytes}\n")
                            }
                            if (entries.size > 80) append("… truncated\n")
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("List dir") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    runAction("metadata") {
                        if (!client.isConnected) client.connect(credentials())
                        val target = testFile.ifBlank { path }
                        val meta = client.metadata(share.trim(), target)
                        "path=${meta.path} dir=${meta.isDirectory} size=${meta.sizeBytes}"
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Metadata") }
            Button(
                onClick = {
                    runAction("randomRead") {
                        if (!client.isConnected) client.connect(credentials())
                        val target = testFile.trim()
                        require(target.isNotEmpty()) { "Set test file path" }
                        client.openRandomAccess(share.trim(), target).use { raf ->
                            val head = raf.readFullyAt(0, 16)
                            val midPos = (raf.size / 2).coerceAtLeast(0L)
                            val mid = raf.readFullyAt(midPos, 16)
                            val tailPos = (raf.size - 16).coerceAtLeast(0L)
                            val tail = raf.readFullyAt(tailPos, 16)
                            "size=${raf.size} head=${head.toHex()} mid@${midPos}=${mid.toHex()} tail@${tailPos}=${tail.toHex()}"
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Random read") }
        }

        Button(
            onClick = {
                runAction("benchmark") {
                    if (!client.isConnected) client.connect(credentials())
                    val target = testFile.trim()
                    require(target.isNotEmpty()) { "Set test file path" }
                    val result = SmbBenchmark.run(
                        client = client,
                        host = host.trim(),
                        share = share.trim(),
                        path = target,
                    )
                    result.summaryLines().joinToString("\n")
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Run benchmark") }

        Button(
            onClick = {
                runAction("errorProbe") {
                    // Deliberate auth failure — password never logged.
                    val bad = credentials().copy(password = "definitely-wrong-password".toCharArray())
                    try {
                        SmbjClient().use { probe ->
                            probe.connect(bad)
                        }
                        "unexpected success"
                    } catch (e: SmbException) {
                        "mapped=${e.error::class.simpleName} msg=${e.error.message}"
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Probe auth error mapping") }

        Spacer(Modifier.height(8.dp))
        Text("Log (redacted)", style = MaterialTheme.typography.titleMedium)
        Text(
            text = log,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "smb_spike_log" },
        )
    }
}

private fun initialHelp(): String = """
    Steps:
    1. Type the NAS fields in this debug-only screen.
    2. Connect → List dir → Metadata / Random read → Benchmark.
    3. Disconnect and reconnect to verify session lifecycle.
    Data-path note: prefer SEEKABLE_SMB_DATASOURCE for player+thumbnails; see docs/decisions/0002-smb-data-path.md
""".trimIndent()

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { b -> "%02x".format(b) }
