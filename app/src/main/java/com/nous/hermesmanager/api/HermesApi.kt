package com.nous.hermesmanager.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Client for the Hermes Agent API server (OpenAI-compatible, port 8642)
 * and the Web Dashboard RPC gateway (port 9119).
 *
 * API server docs: hermes-agent.nousresearch.com/docs/user-guide/features/api-server
 */
class HermesApi() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // streams & long runs
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    // ---- state per connection config ----
    @Volatile var baseUrl: String = ""
    @Volatile var apiKey: String = ""
    @Volatile var dashboardUrl: String = ""
    @Volatile var model: String = ""
    @Volatile var provider: String = ""

    private fun normalize(url: String) = url.trim().trimEnd('/')

    private fun apiBase() = normalize(baseUrl)
    private fun dashBase() = normalize(dashboardUrl)

    private fun Request.Builder.auth(): Request.Builder =
        header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")

    private suspend fun exec(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw HermesException(resp.code, body)
            }
            body
        }
    }

    private suspend fun get(url: String): JsonObject =
        json.parseToJsonElement(exec(Request.Builder().url(url).auth().build()))
            .jsonObject

    private suspend fun post(url: String, payload: String): JsonObject =
        json.parseToJsonElement(
            exec(
                Request.Builder().url(url)
                    .auth()
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
            )
        ).jsonObject

    // =========================================================
    // API server (8642) — OpenAI-compatible endpoints
    // =========================================================

    suspend fun health(): JsonObject = get("$apiBase()/health")

    suspend fun capabilities(): JsonObject = get("$apiBase()/v1/capabilities")

    suspend fun detailedHealth(): JsonObject = get("$apiBase()/health/detailed")

    suspend fun listModels(): List<ModelRow> {
        val data = get("$apiBase()/v1/models")["data"]?.jsonArray ?: JsonArray(emptyList())
        return data.map {
            ModelRow(id = it.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "")
        }
    }

    /** Rich provider/model inventory (same data the dashboard Models page uses). */
    suspend fun modelOptions(refresh: Boolean = false): JsonObject =
        get("$apiBase()/api/model/options" + if (refresh) "?refresh=1" else "")

    private fun buildChatPayload(messages: List<ChatMessage>, stream: Boolean): JsonObject =
        JsonObject(buildMap {
            put("model", JsonPrimitive(model.ifEmpty { "hermes-agent" }))
            if (provider.isNotEmpty()) put("provider", JsonPrimitive(provider))
            put("stream", JsonPrimitive(stream))
            put(
                "messages",
                JsonArray(messages.map { m ->
                    JsonObject(mapOf(
                        "role" to JsonPrimitive(m.role),
                        "content" to JsonPrimitive(m.content),
                    ))
                })
            )
        })

    /** Non-streaming chat completion. Returns (text, reasoning, usage). */
    suspend fun chat(messages: List<ChatMessage>): ChatResult {
        val payload = buildChatPayload(messages, stream = false).toString()
        val obj = post("$apiBase()/v1/chat/completions", payload)
        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val msg = choice?.get("message")?.jsonObject
        val usage = obj["usage"]?.jsonObject
        return ChatResult(
            text = msg?.get("content")?.jsonPrimitive?.contentOrNull ?: "",
            reasoning = msg?.get("reasoning_content")?.jsonPrimitive?.contentOrNull,
            promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    // ---- Runs API: submit a run, poll status, stream events (SSE) ----

    suspend fun createRun(
        input: String,
        sessionId: String? = null,
        instructions: String? = null,
    ): RunHandle {
        val body = JsonObject(buildMap {
            put("input", JsonPrimitive(input))
            if (model.isNotEmpty()) put("model", JsonPrimitive(model))
            if (provider.isNotEmpty()) put("provider", JsonPrimitive(provider))
            sessionId?.let { put("session_id", JsonPrimitive(it)) }
            instructions?.let { put("instructions", JsonPrimitive(it)) }
        })
        val obj = post("$apiBase()/v1/runs", body.toString())
        return RunHandle(
            runId = obj["run_id"]?.jsonPrimitive?.contentOrNull ?: error("no run_id"),
            status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "started",
        )
    }

    suspend fun runStatus(runId: String): RunStatus {
        val obj = get("$apiBase()/v1/runs/$runId")
        val usage = obj["usage"]?.jsonObject
        return RunStatus(
            runId = runId,
            status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            sessionId = obj["session_id"]?.jsonPrimitive?.contentOrNull,
            output = obj["output"]?.jsonPrimitive?.contentOrNull,
            error = obj["error"]?.jsonPrimitive?.contentOrNull,
            inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    /**
     * Subscribe to run lifecycle events (SSE). Emits parsed [RunEvent]s.
     * Event types per docs: message.delta, message.interim, run.completed,
     * run.failed, run.cancelled, run.interrupted, tool.started, tool.completed,
     * subagent.start, subagent.complete, heartbeat.
     */
    fun streamRunEvents(
        runId: String,
        onEvent: (RunEvent) -> Unit,
        onError: (Throwable) -> Unit,
        onOpen: () -> Unit = {},
    ): EventSource {
        val req = Request.Builder().url("$apiBase()/v1/runs/$runId/events").auth().build()
        return EventSources.createFactory(http).newEventSource(req, object : EventSourceListener() {
            override fun onOpen(es: EventSource, resp: Response) = onOpen()

            override fun onEvent(es: EventSource, id: String?, type: String?, data: String?) {
                if (data == null) return
                val element = runCatching { json.parseToJsonElement(data) }.getOrNull()
                onEvent(
                    RunEvent(
                        type = type ?: "message",
                        data = element,
                        text = element?.jsonObject
                            ?.get("delta")?.jsonPrimitive?.contentOrNull
                            ?: element?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                            ?: element?.jsonObject?.get("preview")?.jsonPrimitive?.contentOrNull,
                    )
                )
            }

            override fun onFailure(es: EventSource, t: Throwable?, resp: Response?) {
                onError(t ?: HermesException(resp?.code ?: -1, resp?.message ?: "stream failed"))
            }
        })
    }

    suspend fun stopRun(runId: String): JsonObject = execJson(
        Request.Builder().url("$apiBase()/v1/runs/$runId/stop")
            .auth().post("{}".toRequestBody("application/json".toMediaType())).build()
    )

    // ---- session-scoped chat (keeps server-side transcript) ----

    suspend fun sessionChat(sessionId: String, message: String): ChatResult {
        val body = JsonObject(
            buildMap {
                put("input", JsonPrimitive(message))
                if (model.isNotEmpty()) put("model", JsonPrimitive(model))
                if (provider.isNotEmpty()) put("provider", JsonPrimitive(provider))
            }
        )
        val obj = post("$apiBase()/api/sessions/$sessionId/chat", body.toString())
        val msg = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
        return ChatResult(
            text = msg?.get("content")?.jsonPrimitive?.contentOrNull
                ?: obj["output"]?.jsonPrimitive?.contentOrNull ?: "",
            reasoning = msg?.get("reasoning_content")?.jsonPrimitive?.contentOrNull,
            promptTokens = 0, completionTokens = 0,
        )
    }

    // =========================================================
    // Web dashboard (9119) — /api/status and Gateway RPC
    // =========================================================
    // Dashboard endpoints are authenticated with a dashboard session; the API
    // server key is replayed as a bearer token (works when the dashboard shares
    // credentials / behind the same auth). If a 401 comes back the UI falls
    // back to the embedded dashboard WebView for those features.

    suspend fun dashboardStatus(): JsonObject = get("$dashBase()/api/status")

    /**
     * Generic Gateway RPC used by the dashboard frontend.
     * Known RPCs: sessions.list / sessions.search / sessions.messages /
     * sessions.delete / sessions.rename / cron.list / cron.create / cron.update /
     * cron.delete / cron.trigger / config.get / config.set / skills.list /
     * skills.toggle / mcp.list / model.options / logs.tail / profiles.list ...
     * (exact wire shape follows the dashboard's own JS client; if an RPC fails
     * here, the WebView tab still provides full access.)
     */
    suspend fun rpc(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement {
        val body = JsonObject(mapOf(
            "jsonrpc" to JsonPrimitive("2.0"),
            "id" to JsonPrimitive(System.currentTimeMillis()),
            "method" to JsonPrimitive(method),
            "params" to params,
        ))
        val obj = post("$dashBase()/api/rpc", body.toString())
        obj["error"]?.let { err ->
            throw HermesException(-1, err.toString())
        }
        return obj["result"] ?: JsonObject(emptyMap())
    }

    suspend fun sessions(limit: Int = 50): List<SessionRow> {
        val res = rpc("sessions.list", JsonObject(mapOf("limit" to JsonPrimitive(limit))))
        val arr = when {
            res is JsonArray -> res
            res is JsonObject -> res["sessions"]?.jsonArray ?: res["items"]?.jsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return arr.map { it.jsonObject.toSessionRow() }
    }

    suspend fun sessionMessages(sessionId: String): List<ChatMessage> {
        val res = rpc("sessions.messages", JsonObject(mapOf("session_id" to JsonPrimitive(sessionId))))
        val arr = when {
            res is JsonArray -> res
            res is JsonObject -> res["messages"]?.jsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return arr.mapNotNull { el ->
            val o = el.jsonObject
            val role = o["role"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val content = o["content"]?.jsonPrimitive?.contentOrNull
                ?: o["content"]?.jsonArray?.joinToString(" ") {
                    it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
                }
            if (content.isNullOrEmpty()) null
            else ChatMessage(role = role, content = content)
        }
    }

    private suspend fun execJson(req: Request): JsonObject =
        json.parseToJsonElement(exec(req)).jsonObject
}

private fun JsonObject.toSessionRow(): SessionRow = SessionRow(
    id = this["id"]?.jsonPrimitive?.contentOrNull
        ?: this["session_id"]?.jsonPrimitive?.contentOrNull ?: "",
    title = this["title"]?.jsonPrimitive?.contentOrNull,
    source = this["source"]?.jsonPrimitive?.contentOrNull
        ?: this["platform"]?.jsonPrimitive?.contentOrNull,
    model = this["model"]?.jsonPrimitive?.contentOrNull,
    messageCount = this["message_count"]?.jsonPrimitive?.intOrNull ?: 0,
    lastActive = this["last_active"]?.jsonPrimitive?.contentOrNull
        ?: this["updated_at"]?.jsonPrimitive?.contentOrNull,
    preview = this["preview"]?.jsonPrimitive?.contentOrNull,
    live = this["live"]?.jsonPrimitive?.booleanOrNull == true,
)

class HermesException(val code: Int, message: String) : Exception("HTTP $code: $message")

data class ModelRow(val id: String)
data class ChatMessage(val role: String, val content: String)
data class ChatResult(
    val text: String,
    val reasoning: String?,
    val promptTokens: Int,
    val completionTokens: Int,
)
data class RunHandle(val runId: String, val status: String)
data class RunStatus(
    val runId: String,
    val status: String,
    val sessionId: String?,
    val output: String?,
    val error: String?,
    val inputTokens: Int,
    val outputTokens: Int,
)
data class RunEvent(
    val type: String,
    val data: JsonElement?,
    val text: String?,
)
data class SessionRow(
    val id: String,
    val title: String?,
    val source: String?,
    val model: String?,
    val messageCount: Int,
    val lastActive: String?,
    val preview: String?,
    val live: Boolean,
)
