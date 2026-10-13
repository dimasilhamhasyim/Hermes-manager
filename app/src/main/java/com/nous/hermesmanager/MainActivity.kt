package com.nous.hermesmanager

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nous.hermesmanager.api.HermesApi
import com.nous.hermesmanager.ui.ChatViewModel
import com.nous.hermesmanager.ui.SettingsViewModel
import com.nous.hermesmanager.ui.SessionsViewModel
import com.nous.hermesmanager.ui.StatusViewModel
import com.nous.hermesmanager.ui.applySettings
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HermesApp
        val api = HermesApi()
        setContent {
            // Load persisted settings once before rendering screens
            var ready by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                api.applySettings(app.settingsStore.flow.first())
                ready = true
            }
            MaterialTheme {
                if (!ready) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    MainScreen(api, app.settingsStore)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(api: HermesApi, store: SettingsStore) {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Chat", "Status", "Sessions", "Dashboard", "Setelan")

    val chatVm = remember { ChatViewModel(api) }
    val statusVm = remember { StatusViewModel(api) }
    val sessionsVm = remember { SessionsViewModel(api) }
    val settingsVm = remember { SettingsViewModel(api, store) }

    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        topBar = {
            TopAppBar(
                title = { Text("Hermes Manager") },
                actions = {
                    if (tab == 1) IconButton({ statusVm.refresh() }) { Icon(Icons.Default.Refresh, "refresh") }
                    if (tab == 2) IconButton({ sessionsVm.refresh() }) { Icon(Icons.Default.Refresh, "refresh") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {},
                        label = { Text(t) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> ChatScreen(chatVm)
                1 -> StatusScreen(statusVm, api)
                2 -> SessionsScreen(sessionsVm)
                3 -> DashboardScreen(api)
                4 -> SettingsScreen(settingsVm)
            }
        }
    }
}

@Composable
fun ChatScreen(vm: ChatViewModel) {
    val messages by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
        LazyColumn(
            Modifier.weight(1f).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages) { m ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            m.role.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            if (m.content.isEmpty() && m.streaming) "…" else m.content,
                            fontFamily = if (m.role == "system") FontFamily.Monospace else FontFamily.Default,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Pesan ke Hermes…") },
                maxLines = 5,
            )
            Column(Modifier.padding(start = 8.dp)) {
                Button(
                    onClick = { vm.send(input); input = "" },
                    enabled = !busy && input.isNotBlank(),
                ) { Text(if (busy) "Bekerja…" else "Kirim") }
                TextButton(onClick = { vm.clear() }) { Text("Sesi baru") }
            }
        }
    }
}

@Composable
fun StatusScreen(vm: StatusViewModel, api: HermesApi) {
    val health by vm.health.collectAsState()
    val detailed by vm.detailed.collectAsState()
    val caps by vm.capabilities.collectAsState()
    val error by vm.error.collectAsState()

    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize().padding(12.dp), Arrangement.spacedBy(10.dp)) {
        error?.takeIf { it.isNotBlank() }?.let {
            Text("Gagal: $it", color = MaterialTheme.colorScheme.error)
        }
        Text("API Server: ${api.baseUrl.ifEmpty { "(belum diatur)" }}")
        Text("Dashboard : ${api.dashboardUrl.ifEmpty { "(belum diatur)" }}")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text("Health", style = MaterialTheme.typography.titleSmall)
                Text(health ?: "—", fontFamily = FontFamily.Monospace)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text("Capabilities", style = MaterialTheme.typography.titleSmall)
                Text(prettyJson(caps) ?: "—", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text("Detailed Health", style = MaterialTheme.typography.titleSmall)
                Text(prettyJson(detailed) ?: "—", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(onClick = { vm.refresh() }) { Text("Refresh") }
    }
}

@Composable
fun SessionsScreen(vm: SessionsViewModel) {
    val sessions by vm.sessions.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()

    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize()) {
        error?.let {
            Text(
                "Tidak bisa memuat daftar sesi via RPC (gunakan tab Dashboard / WebView): $it",
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(sessions) { s ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text(s.title ?: s.id, style = MaterialTheme.typography.titleSmall)
                            Text(
                                buildString {
                                    append("sumber: ${s.source ?: "?"} · model: ${s.model ?: "?"}")
                                    append(" · pesan: ${s.messageCount}")
                                    s.lastActive?.let { append(" · aktif: ${it.take(16)}") }
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            s.preview?.let {
                                Text(it.take(140), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DashboardScreen(api: HermesApi) {
    val url = api.dashboardUrl.ifEmpty { api.baseUrl }
    if (url.isBlank()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Isi URL dashboard (mis. http://IP-server:9119) di tab Setelan dulu.")
        }
        return
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                loadUrl(url)
            }
        },
        update = { it.loadUrl(url) },
    )
}

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val s by vm.settings.collectAsState()
    val saved by vm.saved.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp), Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = s.baseUrl,
            onValueChange = { v -> vm.update { it.copy(baseUrl = v) } },
            label = { Text("API Server URL (mis. http://192.168.1.10:8642)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = s.apiKey,
            onValueChange = { v -> vm.update { it.copy(apiKey = v) } },
            label = { Text("API Key (API_SERVER_KEY)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = s.dashboardUrl,
            onValueChange = { v -> vm.update { it.copy(dashboardUrl = v) } },
            label = { Text("Dashboard URL (mis. http://192.168.1.10:9119) — opsional") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = s.model,
            onValueChange = { v -> vm.update { it.copy(model = v) } },
            label = { Text("Model override (opsional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = s.provider,
            onValueChange = { v -> vm.update { it.copy(provider = v) } },
            label = { Text("Provider slug (opsional, mis. openrouter)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { vm.save() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (saved) "✓ Tersimpan" else "Simpan")
        }
        Text(
            "Di server Hermes: aktifkan API server (API_SERVER_ENABLED=true + API_SERVER_KEY " +
                "di ~/.hermes/.env), jalankan `hermes gateway`, dan `hermes dashboard --host 0.0.0.0` " +
                "agar dashboard bisa diakses dari HP.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun prettyJson(raw: String?): String? = raw?.let {
    runCatching {
        val el = kotlinx.serialization.json.Json.parseToJsonElement(it)
        kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(
            kotlinx.serialization.json.JsonElement.serializer(), el
        )
    }.getOrDefault(it)
}
