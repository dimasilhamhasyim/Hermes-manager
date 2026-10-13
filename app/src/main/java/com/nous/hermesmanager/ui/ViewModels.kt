package com.nous.hermesmanager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nous.hermesmanager.Settings
import com.nous.hermesmanager.SettingsStore
import com.nous.hermesmanager.api.ChatMessage
import com.nous.hermesmanager.api.HermesApi
import com.nous.hermesmanager.api.RunEvent
import com.nous.hermesmanager.api.SessionRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class UiMessage(
    val role: String,          // "user" | "assistant" | "system"
    val content: String,
    val streaming: Boolean = false,
)

class ChatViewModel(private val api: HermesApi) : ViewModel() {
    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Optional: bind this chat to an existing server-side Hermes session. */
    var sessionId: String? = null

    fun send(text: String) {
        if (text.isBlank() || _busy.value) return
        viewModelScope.launch {
            _error.value = null
            _busy.value = true
            _messages.value = _messages.value +
                UiMessage("user", text) +
                UiMessage("assistant", "", streaming = true)
            try {
                val run = api.createRun(text, sessionId = sessionId)
                api.streamRunEvents(
                    run.runId,
                    onEvent = { ev -> handleEvent(ev) },
                    onError = { t ->
                        _error.value = t.message
                        finishStreaming()
                        _busy.value = false
                    },
                )
            } catch (t: Throwable) {
                // Fallback: stateless OpenAI-compatible chat completions
                try {
                    val history = _messages.value
                        .filter { it.content.isNotEmpty() }
                        .map { ChatMessage(it.role, it.content) }
                    val res = api.chat(history)
                    _messages.value = _messages.value.dropLast(1) + UiMessage("assistant", res.text)
                } catch (t2: Throwable) {
                    _error.value = t2.message
                    _messages.value = _messages.value.dropLast(1)
                } finally {
                    _busy.value = false
                }
            }
        }
    }

    private fun handleEvent(ev: RunEvent) {
        viewModelScope.launch {
            val list = _messages.value.toMutableList()
            val idx = list.indexOfLast { it.streaming }
            when (ev.type) {
                "message.delta" -> {
                    val delta = ev.text ?: return@launch
                    if (idx >= 0) list[idx] = list[idx].copy(content = list[idx].content + delta)
                    _messages.value = list
                }
                "tool.started" -> {
                    val name = ev.data?.objString("tool") ?: "tool"
                    _messages.value = list + UiMessage("system", "🔧 $name ${ev.text?.take(100) ?: ""}")
                }
                "tool.completed" -> {
                    val err = (ev.data as? JsonObject)?.containsKey("error") == true
                    _messages.value = list + UiMessage(
                        "system",
                        (if (err) "⚠️ tool error" else "✅ tool selesai") +
                            (ev.text?.let { " — ${it.take(120)}" } ?: "")
                    )
                }
                "run.completed" -> {
                    val out = ev.data?.objString("output")
                    if (idx >= 0 && list[idx].content.isEmpty() && !out.isNullOrEmpty()) {
                        list[idx] = list[idx].copy(content = out)
                        _messages.value = list
                    } else {
                        _messages.value = list
                    }
                    finishStreaming()
                    _busy.value = false
                }
                "run.failed", "run.cancelled", "run.interrupted" -> {
                    _error.value = "Run berakhir: ${ev.type}"
                    finishStreaming()
                    _busy.value = false
                }
                // message.interim, subagent.*, : keepalive → diabaikan di UI minimal ini
            }
        }
    }

    private fun finishStreaming() {
        _messages.value = _messages.value.map {
            if (it.streaming) it.copy(streaming = false) else it
        }
    }

    fun clear() {
        _messages.value = emptyList()
        sessionId = null
    }
}

class SessionsViewModel(private val api: HermesApi) : ViewModel() {
    private val _sessions = MutableStateFlow<List<SessionRow>>(emptyList())
    val sessions: StateFlow<List<SessionRow>> = _sessions.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _sessions.value = api.sessions()
            } catch (t: Throwable) {
                _error.value = t.message
            } finally {
                _loading.value = false
            }
        }
    }
}

class StatusViewModel(private val api: HermesApi) : ViewModel() {
    private val _health = MutableStateFlow<String?>(null)
    val health: StateFlow<String?> = _health.asStateFlow()

    private val _detailed = MutableStateFlow<String?>(null)
    val detailed: StateFlow<String?> = _detailed.asStateFlow()

    private val _capabilities = MutableStateFlow<String?>(null)
    val capabilities: StateFlow<String?> = _capabilities.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _error.value = null
            try {
                _health.value = api.health()["status"]?.let { it.toString().trim('"') }
            } catch (t: Throwable) {
                _error.value = "health: ${t.message}"
            }
            try {
                _capabilities.value = api.capabilities().toString()
            } catch (t: Throwable) {
                _error.value = (_error.value ?: "") + " capabilities: ${t.message}"
            }
            try {
                _detailed.value = api.detailedHealth().toString()
            } catch (t: Throwable) {
                _error.value = (_error.value ?: "") + " detailed: ${t.message}"
            }
        }
    }
}

class SettingsViewModel(
    private val api: HermesApi,
    private val store: SettingsStore,
) : ViewModel() {
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    init {
        viewModelScope.launch {
            val s = store.current()
            _settings.value = s
            api.applySettings(s)
        }
    }

    fun update(f: (Settings) -> Settings) {
        _settings.value = f(_settings.value)
        _saved.value = false
    }

    fun save() {
        viewModelScope.launch {
            store.save(_settings.value)
            api.applySettings(_settings.value)
            _saved.value = true
        }
    }
}

fun HermesApi.applySettings(s: Settings) {
    baseUrl = s.baseUrl.trim().trimEnd('/')
    apiKey = s.apiKey.trim()
    dashboardUrl = s.dashboardUrl.trim().trimEnd('/').ifEmpty { baseUrl }
    model = s.model.trim()
    provider = s.provider.trim()
}

private fun kotlinx.serialization.json.JsonElement?.objString(key: String): String? =
    (this as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.contentOrNull }
