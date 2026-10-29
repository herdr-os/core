package dev.r1ptt.herdr.model

import org.json.JSONArray
import org.json.JSONObject

// Device-side mirror of protocol/openapi.yaml. Parsing is lenient on unknown fields and strict on
// identity: an agent without a session id or machine is dropped, never guessed.

enum class AgentState(val wire: String, val priority: Int, val label: String) {
    APPROVAL_REQUIRED("approval_required", 60, "Needs decision"),
    BLOCKED("blocked", 50, "Needs your input"),
    FAILED("failed", 40, "Failed"),
    COMPLETED_UNSEEN("completed_unseen", 30, "Done"),
    WORKING("working", 20, "Working"),
    IDLE("idle", 10, "Idle"),
    DISCONNECTED("disconnected", 0, "Offline");

    val needsYou get() = this == APPROVAL_REQUIRED || this == BLOCKED

    companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: IDLE
    }
}

data class Agent(
    val sessionId: String,
    val machineId: String,
    val machineLabel: String,
    val kind: String,
    val name: String?,
    val title: String,
    val project: String,
    val cwd: String?,
    val state: AgentState,
    val priority: Int,
    val generation: Int,
    val stateSince: String,
    val stale: Boolean,
    val pendingApprovalId: String?,
    val summary: String,
) {
    /** What the card calls this agent: its Herdr name if it has one, else its kind. */
    val displayName get() = (name?.takeIf { it.isNotBlank() && it != kind } ?: kind).replaceFirstChar { it.uppercase() }

    companion object {
        fun parse(o: JSONObject): Agent? {
            val id = o.optString("session_id").ifBlank { return null }
            val machine = o.optString("machine_id").ifBlank { return null }
            return Agent(
                sessionId = id,
                machineId = machine,
                machineLabel = o.optString("machine_label", machine),
                kind = o.optString("agent", "agent"),
                name = o.optNullableString("name"),
                title = o.optString("title"),
                project = o.optString("project"),
                cwd = o.optNullableString("cwd"),
                state = AgentState.of(o.optString("state")),
                priority = o.optInt("priority"),
                generation = o.optInt("generation", 1),
                stateSince = o.optString("state_since"),
                stale = o.optBoolean("stale"),
                pendingApprovalId = o.optNullableString("pending_approval_id"),
                summary = o.optString("summary"),
            )
        }
    }
}

data class Machine(val id: String, val label: String, val kind: String, val connected: Boolean, val lastSeenAt: String?, val herdrVersion: String? = null) {
    companion object {
        fun parse(o: JSONObject) = Machine(
            o.getString("machine_id"), o.optString("label"), o.optString("kind", "local"),
            o.optString("status") == "connected", o.optNullableString("last_seen_at"), o.optNullableString("herdr_version"),
        )
    }
}

data class Option(val index: Int, val label: String)

data class Approval(
    val id: String,
    val sessionId: String,
    val generation: Int,
    val question: String,
    val options: List<Option>,
    val evidenceFingerprint: String,
    val evidence: String,
    val parse: String,
    val open: Boolean,
) {
    companion object {
        fun parse(o: JSONObject) = Approval(
            o.getString("approval_id"), o.getString("session_id"), o.optInt("generation", 1), o.optString("question"),
            o.optJSONArray("options").objects().map { Option(it.getInt("index"), it.getString("label")) },
            o.getString("evidence_fingerprint"), o.optString("evidence"), o.optString("parse"), o.optString("status") == "open",
        )
    }
}

data class HerdEvent(
    val id: String,
    val cursor: String,
    val occurredAt: String,
    val machineId: String?,
    val sessionId: String?,
    val generation: Int?,
    val type: String,
    val payload: JSONObject,
) {
    companion object {
        fun parse(o: JSONObject) = HerdEvent(
            o.getString("event_id"), o.getString("cursor"), o.optString("occurred_at"), o.optNullableString("machine_id"),
            o.optNullableString("session_id"), if (o.isNull("generation")) null else o.optInt("generation"), o.getString("type"),
            o.optJSONObject("payload") ?: JSONObject(),
        )
    }
}

data class Snapshot(val cursor: String, val machines: List<Machine>, val agents: List<Agent>, val approvals: List<Approval>, val syncedAt: String) {
    companion object {
        fun parse(o: JSONObject) = Snapshot(
            o.getString("cursor"),
            o.optJSONArray("machines").objects().map(Machine::parse),
            o.optJSONArray("agents").objects().mapNotNull(Agent::parse),
            o.optJSONArray("approvals").objects().map(Approval::parse),
            o.optString("synced_at"),
        )
    }
}

enum class ReceiptStatus { PENDING, ACCEPTED, REJECTED, UNKNOWN }

data class Receipt(val actionId: String, val key: String, val kind: String, val status: ReceiptStatus, val detail: String?, val result: JSONObject?) {
    /** The bridge's machine code (`approval_changed`, `revision_changed` …) from a rejection. */
    val code get() = detail?.substringBefore(':')?.trim()

    companion object {
        fun parse(o: JSONObject) = Receipt(
            o.getString("action_id"), o.optString("idempotency_key"), o.optString("kind"),
            ReceiptStatus.entries.firstOrNull { it.name == o.optString("status").uppercase() } ?: ReceiptStatus.UNKNOWN, o.optNullableString("detail"), o.optJSONObject("result"),
        )
    }
}

data class Span(val start: Int, val end: Int, val fg: Int?, val bold: Boolean)
data class TermLine(val text: String, val spans: List<Span>, val redacted: Boolean)

data class TerminalSnapshot(
    val lines: List<TermLine>,
    val cwd: String?,
    val stream: String,
    val redactions: Int,
    val neutralized: Int,
    val truncated: Boolean,
    val capturedAt: String,
) {
    companion object {
        fun parse(o: JSONObject) = TerminalSnapshot(
            o.optJSONArray("lines").objects().map { l ->
                TermLine(l.optString("text"), l.optJSONArray("spans").objects().map { s ->
                    Span(s.getInt("start"), s.getInt("end"), if (s.isNull("fg")) null else s.optInt("fg"), s.optBoolean("bold"))
                }, l.optBoolean("redacted"))
            },
            o.optNullableString("cwd"), o.optString("stream", "offline"), o.optInt("redactions"), o.optInt("neutralized"),
            o.optBoolean("truncated"), o.optString("captured_at"),
        )
    }
}

data class ChangedFile(val id: String, val path: String, val oldPath: String?, val status: String, val additions: Int, val deletions: Int, val content: String) {
    companion object {
        fun parse(o: JSONObject) = ChangedFile(
            o.getString("file_id"), o.getString("path"), o.optNullableString("old_path"), o.optString("status"),
            o.optInt("additions"), o.optInt("deletions"), o.optString("content", "text"),
        )
    }
}

data class Changes(val repoName: String, val repoId: String, val baseCommit: String?, val fingerprint: String, val files: List<ChangedFile>) {
    companion object {
        fun parse(o: JSONObject) = Changes(
            o.optString("repo_name"), o.optString("repo_id"), o.optNullableString("base_commit"), o.getString("fingerprint"),
            o.optJSONArray("files").objects().map(ChangedFile::parse),
        )
    }
}

data class DiffLine(val kind: String, val old: Int?, val new: Int?, val text: String)
data class Hunk(val id: String, val header: String, val lines: List<DiffLine>)
data class FileDiff(val file: ChangedFile, val fingerprint: String, val hunks: List<Hunk>, val redactions: Int) {
    companion object {
        fun parse(o: JSONObject) = FileDiff(
            ChangedFile.parse(o.getJSONObject("file")), o.getString("fingerprint"),
            o.optJSONArray("hunks").objects().map { h ->
                Hunk(h.getString("hunk_id"), h.optString("header"), h.optJSONArray("lines").objects().map { l ->
                    DiffLine(l.getString("kind"), if (l.isNull("old")) null else l.optInt("old"), if (l.isNull("new")) null else l.optInt("new"), l.optString("text"))
                })
            },
            o.optInt("redactions"),
        )
    }
}

data class FileEntry(val name: String, val path: String, val dir: Boolean, val git: String?, val withheld: Boolean)
data class Listing(val root: String, val path: String, val entries: List<FileEntry>) {
    companion object {
        fun parse(o: JSONObject) = Listing(
            o.optString("root"), o.optString("path"),
            o.optJSONArray("entries").objects().map {
                FileEntry(it.getString("name"), it.getString("path"), it.optString("type") == "dir", it.optNullableString("git"), it.optBoolean("withheld"))
            },
        )
    }
}

data class Candidate(val sessionId: String, val agent: String, val machine: String, val project: String, val title: String, val state: String)

data class Resolution(
    val status: String,
    val sessionId: String?,
    val instruction: String,
    val agent: String?,
    val machine: String?,
    val project: String?,
    val candidates: List<Candidate>,
    val reason: String,
) {
    companion object {
        fun parse(o: JSONObject): Resolution {
            val intent = o.optJSONObject("intent")
            val parsed = o.optJSONObject("parsed") ?: JSONObject()
            return Resolution(
                o.getString("status"), intent?.optNullableString("session_id"),
                intent?.optNullableString("instruction") ?: intent?.optNullableString("text") ?: parsed.optString("instruction"),
                intent?.optNullableString("agent"), intent?.optNullableString("machine"), intent?.optNullableString("project"),
                o.optJSONArray("candidates").objects().map {
                    Candidate(it.getString("session_id"), it.optString("agent"), it.optString("machine"), it.optString("project"), it.optString("title"), it.optString("state"))
                },
                o.optString("reason"),
            )
        }
    }
}

fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

/** What an agent's own session file says: model, context use, subagents, background commands, a usage limit. */
data class Insight(
    val model: String?,
    val effort: String?,
    val contextUsed: Int?,
    val contextWindow: Int?,
    val subagents: List<Work>,
    val tasks: List<Work>,
    val limit: String?,
    val messages: List<Message> = emptyList(),
    /** What it is doing now, from its session file: its latest tool step. Null when the harness doesn't say. */
    val step: String? = null,
) {
    /**
     * A subagent or background command. [running] is true until the harness records its end.
     * [step] is a subagent's latest tool call or words; [tail] a command's last output lines.
     */
    data class Work(
        val label: String, val kind: String?, val state: String, val running: Boolean,
        val startedAt: String? = null, val endedAt: String? = null,
        val step: String? = null, val model: String? = null, val contextUsed: Int? = null,
        val exitCode: Int? = null, val tail: List<String> = emptyList(),
    )

    /** One turn of the conversation. [photo] is the host path of a photo you sent; [text] is then your note. */
    data class Message(val you: Boolean, val text: String, val at: String, val photo: String?)

    companion object {
        fun parse(o: JSONObject?): Insight? = o?.let {
            val ctx = it.optJSONObject("context")
            Insight(
                it.optNullableString("model"), it.optNullableString("effort"),
                ctx?.optInt("used"), ctx?.takeUnless { c -> c.isNull("window") }?.optInt("window"),
                it.optJSONArray("subagents").objects().map { s ->
                    Work(s.optString("description"), s.optNullableString("type"), s.optString("state"), s.optString("state") == "working",
                        s.optNullableString("started_at"), s.optNullableString("ended_at"), s.optNullableString("step"), s.optNullableString("model"),
                        s.takeUnless { x -> x.isNull("context_used") }?.optInt("context_used"))
                },
                it.optJSONArray("tasks").objects().map { t ->
                    Work(t.optString("command"), null, t.optString("state"), t.optString("state") == "running",
                        t.optNullableString("started_at"), t.optNullableString("ended_at"),
                        exitCode = t.takeUnless { x -> x.isNull("exit_code") }?.optInt("exit_code"),
                        tail = (0 until (t.optJSONArray("tail")?.length() ?: 0)).map { i -> t.getJSONArray("tail").optString(i) })
                },
                it.optJSONObject("limit")?.optString("message"),
                it.optJSONArray("messages").objects().map { m -> Message(m.optString("role") == "you", m.optString("text"), m.optString("at"), m.optNullableString("photo")) },
                it.optNullableString("step"),
            )
        }
    }
}
