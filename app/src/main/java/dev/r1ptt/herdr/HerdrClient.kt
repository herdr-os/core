package dev.r1ptt.herdr

import dev.r1ptt.herdr.model.Approval
import dev.r1ptt.herdr.model.Changes
import dev.r1ptt.herdr.model.FileDiff
import dev.r1ptt.herdr.model.HerdEvent
import dev.r1ptt.herdr.model.Insight
import dev.r1ptt.herdr.model.Listing
import dev.r1ptt.herdr.model.Receipt
import dev.r1ptt.herdr.model.ReceiptStatus
import dev.r1ptt.herdr.model.Resolution
import dev.r1ptt.herdr.model.Snapshot
import dev.r1ptt.herdr.model.TerminalSnapshot
import dev.r1ptt.herdr.model.objects
import dev.r1ptt.net.Http
import dev.r1ptt.net.SseParser
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A refusal from the bridge, with its machine code (`approval_changed`, `unauthorized` …). */
class BridgeError(val status: Int, val code: String, message: String) : IOException(message)

/** Blocking calls; run them off the main thread. Every response is parsed into a typed model here. */
class HerdrClient(baseUrl: String, private val token: String, private val http: OkHttpClient = Http.client) {
    private val base = baseUrl.trimEnd('/').toHttpUrl()
    private val stream = http.newBuilder().readTimeout(STREAM_IDLE_S, TimeUnit.SECONDS).build()

    fun snapshot() = Snapshot.parse(get(listOf("v1", "snapshot")))
    fun session(id: String): Pair<JSONObject, Approval?> = get(listOf("v1", "sessions", id)).let { o ->
        o.getJSONObject("session") to o.optJSONObject("approval")?.let(Approval::parse)
    }
    fun insight(id: String) = Insight.parse(get(listOf("v1", "sessions", id, "insight")).optJSONObject("insight"))
    fun activity(id: String) = get(listOf("v1", "sessions", id, "activity")).optJSONArray("events").objects().map(HerdEvent::parse)
    fun feed(limit: Int = 30) = get(listOf("v1", "activity"), "limit" to "$limit").optJSONArray("events").objects().map(HerdEvent::parse)
    fun terminal(id: String, maxLines: Int = 200) = TerminalSnapshot.parse(get(listOf("v1", "sessions", id, "terminal", "snapshot"), "max_lines" to "$maxLines"))
    fun files(id: String, path: String = "") = Listing.parse(get(listOf("v1", "sessions", id, "files"), "path" to path))
    fun changes(id: String) = Changes.parse(get(listOf("v1", "sessions", id, "changes")))
    fun fileDiff(id: String, fileId: String, revision: String) =
        FileDiff.parse(get(listOf("v1", "sessions", id, "changes", fileId), "revision" to revision))
    fun resolve(text: String, focusSessionId: String?) =
        Resolution.parse(post(listOf("v1", "intents", "resolve"), JSONObject().put("text", text).put("focus_session_id", focusSessionId ?: JSONObject.NULL)))

    fun message(id: String, text: String, generation: Int) =
        act(listOf("v1", "sessions", id, "messages"), JSONObject().put("text", text).put("expected_generation", generation))
    /** Shows the agent a photo: the bridge saves it on the agent's machine and messages it the path. */
    fun attach(id: String, jpeg: ByteArray, note: String, generation: Int) =
        send(listOf("v1", "sessions", id, "attachments"), jpeg.toRequestBody("image/jpeg".toMediaType()), UUID.randomUUID().toString(), "note" to note, "expected_generation" to "$generation")
    fun delegate(id: String, instruction: String, generation: Int) =
        act(listOf("v1", "tasks", "delegate"), JSONObject().put("session_id", id).put("instruction", instruction).put("expected_generation", generation))
    /** Presses keys in the agent's pane: esc, tab, enter, up, down, left, right, backspace, ctrl+c. */
    fun keys(id: String, keys: List<String>, generation: Int) =
        act(listOf("v1", "sessions", id, "actions", "keys"), JSONObject().put("keys", org.json.JSONArray(keys)).put("expected_generation", generation))
    /**
     * Claude Code's `/model`, then Enter: mid-conversation it asks "Switch model?" in a dialog Herdr
     * doesn't report as blocked, so nothing on the R1 would ever answer it. Enter picks "Yes" there
     * and does nothing at an empty prompt.
     */
    fun claudeModel(id: String, model: String, generation: Int): Receipt {
        val r = message(id, "/model $model", generation)
        if (r.status != ReceiptStatus.ACCEPTED) return r
        // ponytail: a fixed wait for the dialog; poll the pane for it if 2.5 s proves short.
        Thread.sleep(2500)
        return keys(id, listOf("enter"), generation)
    }
    fun stop(id: String, generation: Int) = act(listOf("v1", "sessions", id, "actions", "stop"), JSONObject().put("expected_generation", generation))
    fun explain(id: String, text: String, generation: Int) =
        act(listOf("v1", "sessions", id, "actions", "explain"), JSONObject().put("text", text).put("consent", true).put("expected_generation", generation))
    fun decide(a: Approval, optionIndex: Int) = act(listOf("v1", "approvals", a.id, "decision"),
        JSONObject().put("option_index", optionIndex).put("evidence_fingerprint", a.evidenceFingerprint).put("expected_generation", a.generation))
    fun review(id: String, revision: String, approve: Boolean, note: String? = null, filePath: String? = null, hunk: String? = null) =
        act(listOf("v1", "sessions", id, "actions", "review"), JSONObject().put("revision", revision).put("decision", if (approve) "approve" else "request_changes")
            .put("note", note ?: "").putOpt("file_path", filePath).putOpt("hunk_header", hunk))

    /** The receipt for an earlier attempt; how an uncertain write is settled without sending it again. */
    fun receipt(key: String): Receipt? = try {
        Receipt.parse(get(listOf("v1", "actions"), "idempotency_key" to key).getJSONObject("receipt"))
    } catch (e: BridgeError) {
        if (e.code == "action_not_found") null else throw e
    }

    /**
     * Sends one mutation under a fresh Idempotency-Key. A rejection comes back as a receipt, not an
     * exception. If the answer is lost, the receipt is looked up by key; if that fails too the
     * result is [ReceiptStatus.UNKNOWN] with the key kept, and nothing is re-sent.
     */
    fun act(path: List<String>, body: JSONObject, key: String = UUID.randomUUID().toString()): Receipt = send(path, body.toString().toRequestBody(Http.JSON), key)

    private fun send(path: List<String>, body: RequestBody, key: String, vararg query: Pair<String, String>): Receipt = try {
        Receipt.parse(post(path, body, key, *query).getJSONObject("receipt"))
    } catch (e: BridgeError) {
        // Only the bridge's own 4xx is a definite refusal; a 5xx or a proxy's 502 may follow an accepted action.
        if (e.status in 400..499) Receipt("", key, path.joinToString("/"), ReceiptStatus.REJECTED, "${e.code}: ${e.message}", null)
        else settle(path, key, e)
    } catch (e: Exception) {
        settle(path, key, e)
    }

    private fun settle(path: List<String>, key: String, e: Exception) = runCatching { receipt(key) }.getOrNull()
        ?: Receipt("", key, path.joinToString("/"), ReceiptStatus.UNKNOWN, "delivery uncertain: ${e.message ?: "connection lost"}", null)

    /**
     * Follows `/v1/events` until the stream ends or [call] is cancelled. Blocks. `reset_required`
     * and `revoked` arrive as [onControl]; everything else as parsed events.
     */
    fun events(after: String?, onOpen: (Call) -> Unit, onEvent: (HerdEvent) -> Unit, onControl: (String) -> Unit) {
        val url = base.newBuilder().addPathSegments("v1/events").apply { after?.takeIf(String::isNotEmpty)?.let { addQueryParameter("after", it) } }.build()
        val call = stream.newCall(Request.Builder().url(url).bearer().header("Accept", "text/event-stream").build())
        onOpen(call)
        call.execute().use { res ->
            if (!res.isSuccessful) throw error(res.code, res.body?.string().orEmpty())
            val parser = SseParser { event, data ->
                when (event) {
                    "reset_required", "revoked" -> onControl(event)
                    else -> runCatching { HerdEvent.parse(JSONObject(data)) }.getOrNull()?.let(onEvent)
                }
            }
            val source = res.body!!.source()
            while (true) parser.line(source.readUtf8Line() ?: break)
            parser.finish()
        }
    }

    private fun get(path: List<String>, vararg query: Pair<String, String>): JSONObject {
        val url = base.newBuilder().apply { path.forEach(::addPathSegment); query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return call(Request.Builder().url(url).bearer().get().build())
    }

    private fun post(path: List<String>, body: JSONObject): JSONObject = post(path, body.toString().toRequestBody(Http.JSON), null)

    private fun post(path: List<String>, body: RequestBody, key: String?, vararg query: Pair<String, String>): JSONObject {
        val request = Request.Builder().url(base.newBuilder().apply { path.forEach(::addPathSegment); query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()).bearer()
            .apply { key?.let { header("Idempotency-Key", it) } }
            .post(body).build()
        return call(request)
    }

    private fun call(request: Request): JSONObject = http.newCall(request).execute().use { res ->
        val text = res.body?.string().orEmpty()
        if (!res.isSuccessful) throw error(res.code, text)
        JSONObject(text)
    }

    private fun Request.Builder.bearer() = header("Authorization", "Bearer $token")

    companion object {
        /** The bridge sends a keepalive every 15 s; three missed ones mean the stream is dead. */
        private const val STREAM_IDLE_S = 45L

        private fun error(status: Int, body: String): BridgeError {
            val e = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
            return BridgeError(status, e?.optString("code") ?: "http_$status", e?.optString("message")?.ifBlank { null } ?: "Bridge answered $status")
        }

        /** Trades a one-time enrollment code for this device's own token. */
        fun claim(baseUrl: String, code: String, name: String, http: OkHttpClient = Http.client): JSONObject {
            val url = baseUrl.trim().trimEnd('/').toHttpUrl().newBuilder().addPathSegments("v1/enrollments/claim").build()
            val body = JSONObject().put("code", code.trim()).put("name", name).toString().toRequestBody(Http.JSON)
            return http.newCall(Request.Builder().url(url).post(body).build()).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw error(res.code, text)
                JSONObject(text)
            }
        }
    }
}
