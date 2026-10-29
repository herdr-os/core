package dev.r1ptt.herdr.ui

import android.app.AlertDialog
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.widget.LinearLayout
import android.widget.TextView
import dev.r1ptt.R
import dev.r1ptt.herdr.BridgeError
import dev.r1ptt.herdr.model.ChangedFile
import dev.r1ptt.herdr.model.Changes
import dev.r1ptt.herdr.model.FileDiff
import dev.r1ptt.herdr.model.Receipt
import dev.r1ptt.herdr.model.ReceiptStatus
import dev.r1ptt.ui.Fonts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The agent's uncommitted Git changes, from Git on its host. Files first, then one file one hunk
 * at a time (the wheel steps hunks). Every review is bound to the revision fingerprint it was made
 * on; if the code moves, the review is void and the new revision is offered instead.
 */
class ChangesActivity : HerdrScreen() {
    private var changes: Changes? = null
    private var diff: FileDiff? = null
    private var hunk = 0
    private var failure: String? = null
    private var invalidated = false
    private var shownKey = ""
    override val wheelSteps get() = diff != null

    override fun onResume() {
        super.onResume()
        herdr.state.held = sessionId
        if (changes == null) load()
    }

    override fun onPause() {
        if (herdr.state.held == sessionId) herdr.state.held = null
        super.onPause()
    }

    private fun load() {
        val id = sessionId ?: return
        val client = herdr.client() ?: return
        scope.launchCatching({ client.changes(id) }) { c, e ->
            changes = c; diff = null; failure = e?.let(::describe); render()
            // Opened from Files on one changed file: go straight to its diff, once.
            intent.getStringExtra(EXTRA_FILE)?.let { wanted ->
                intent.removeExtra(EXTRA_FILE)
                c?.files?.firstOrNull { it.path == wanted }?.let(::openFile)
            }
        }
    }

    private fun openFile(f: ChangedFile) {
        val id = sessionId ?: return
        val rev = changes?.fingerprint ?: return
        val client = herdr.client() ?: return
        scope.launchCatching({ client.fileDiff(id, f.id, rev) }) { d, e ->
            if (e is BridgeError && e.code == "revision_changed") { invalidated = true; load(); return@launchCatching }
            diff = d; hunk = 0; failure = e?.let(::describe); render()
        }
    }

    override fun render() {
        val d = diff
        val c = changes
        // Herd updates for other agents must not reset the hunk being read.
        val key = "$failure${c?.fingerprint}${d?.fingerprint}${d?.file?.id}$hunk$invalidated"
        if (key == shownKey) return
        shownKey = key
        val page = ui.page(if (d != null) d.file.path.substringAfterLast('/') else "${agent?.displayName ?: "Agent"} · Changes") {
            back()
        }
        wheelScrollTarget = page.scroll
        val body = page.body
        if (invalidated) body.addView(ui.note("The code changed while you were reviewing. Your earlier review no longer applies; this is the new revision."))
        when {
            failure != null -> body.addView(ui.text(failure!!, 16f))
            c == null -> body.addView(ui.note("Reading Git on the host…"))
            d != null -> fileView(body, c, d)
            else -> listView(body, c)
        }
        setContentView(page.root)
    }

    private fun listView(body: LinearLayout, c: Changes) {
        body.addView(ui.mono("${c.repoName} · base ${c.baseCommit?.take(7) ?: "none"} · rev ${c.fingerprint.take(8)}", R.color.dim, 10f).apply { setPadding(0, 0, 0, ui.dp(10)) })
        if (c.files.isEmpty()) { body.addView(ui.text("No uncommitted changes.", 16f)); return }
        c.files.forEach { f ->
            val stats = when (f.content) {
                "text" -> "+${f.additions} −${f.deletions}"
                "binary" -> "binary"
                "too_large" -> "too large to show"
                "withheld" -> "withheld: may hold secrets"
                "deleted" -> "deleted −${f.deletions}"
                else -> f.content
            }
            val name = (f.oldPath?.let { "$it → " } ?: "") + f.path
            body.addView(ui.actionRow(name, "${f.status} · $stats") { openFile(f) })
        }
        body.addView(ui.section("Review"))
        body.addView(ui.button("Request changes") { requestChanges(c, null, null) })
        body.addView(ui.button("Approve this revision", primary = true) { approve(c) })
    }

    private fun fileView(body: LinearLayout, c: Changes, d: FileDiff) {
        body.addView(ui.mono("${d.file.status} · +${d.file.additions} −${d.file.deletions}${if (d.redactions > 0) " · ${d.redactions} redacted" else ""}", R.color.dim, 10f))
        if (d.hunks.isEmpty()) {
            body.addView(ui.text(when (d.file.content) {
                "binary" -> "Binary file. Its contents aren't shown."
                "too_large" -> "This change is too large to show on the R1."
                "withheld" -> "Withheld: this path may contain secrets, so the bridge never sends it."
                else -> "No line changes to show."
            }, 16f).apply { setPadding(0, ui.dp(14), 0, ui.dp(14)) })
        } else {
            val h = d.hunks[hunk.coerceIn(0, d.hunks.size - 1)]
            body.addView(ui.mono("Hunk ${hunk + 1} of ${d.hunks.size} · ${h.header}", R.color.accent, 10f).apply { setPadding(0, ui.dp(8), 0, ui.dp(6)) })
            body.addView(hunkView(h))
            body.addView(ui.note("Turn the wheel for the next hunk."))
            body.addView(ui.button("Explain this hunk") { explain(d.file.path, h) })
            body.addView(ui.button("Request changes to this hunk") { requestChanges(c, d.file.path, h.header) })
        }
        body.addView(ui.button("Back to files") { diff = null; render() })
    }

    private fun hunkView(h: dev.r1ptt.herdr.model.Hunk) = TextView(this).apply {
        typeface = Fonts.mono(context, 400)
        textSize = 12f
        setLineSpacing(ui.dp(3).toFloat(), 1f)
        setTextColor(getColor(R.color.fg))
        setTextIsSelectable(true)
        val out = SpannableStringBuilder()
        h.lines.forEach { l ->
            val start = out.length
            val glyph = when (l.kind) { "add" -> "+"; "del" -> "−"; else -> " " }
            val num = (if (l.kind == "del") l.old else l.new)?.toString()?.padStart(4) ?: "    "
            out.append("$num $glyph ${l.text}\n")
            val (fg, bg) = when (l.kind) {
                "add" -> R.color.ok to 0x2234C46A
                "del" -> R.color.error to 0x22FF5C7A
                else -> R.color.dim to 0
            }
            out.setSpan(ForegroundColorSpan(getColor(fg)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bg != 0) out.setSpan(BackgroundColorSpan(bg), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text = out
        background = ui.background(R.color.field, 14)
        setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) }
    }

    /** The hunk as diff text, sent to the agent only after the user agrees (the bridge redacts it again). */
    private fun explain(path: String, h: dev.r1ptt.herdr.model.Hunk) {
        val a = agent ?: return
        val text = "$path ${h.header}\n" + h.lines.joinToString("\n") { l -> (when (l.kind) { "add" -> "+"; "del" -> "-"; else -> " " }) + l.text }
        showDeviceDialog(AlertDialog.Builder(this)
            .setTitle("Ask ${a.displayName} to explain this hunk?")
            .setMessage("Sends this hunk of $path (${h.lines.size} lines, secrets redacted) to ${a.displayName} on ${a.machineLabel}, asking it not to change anything yet.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send") { _, _ -> io({ it.explain(a.sessionId, text, a.generation) }) { r -> showToast(receiptText(r)) } })
    }

    private fun requestChanges(c: Changes, path: String?, header: String?) {
        val a = agent ?: return
        val note = ui.field("What should change?", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        showDeviceDialog(AlertDialog.Builder(this)
            .setTitle("Feedback to ${a.displayName}")
            .setMessage(path?.let { "On $it" + (header?.let { h -> " ($h)" } ?: "") } ?: "On revision ${c.fingerprint.take(8)}")
            .setView(note)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send") { _, _ ->
                val text = note.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton showToast("Say what should change")
                io({ it.review(a.sessionId, c.fingerprint, approve = false, note = text, filePath = path, hunk = header) }, ::reviewed)
            })
    }

    private fun approve(c: Changes) {
        val a = agent ?: return
        showDeviceDialog(AlertDialog.Builder(this)
            .setTitle("Approve this revision?")
            .setMessage("${a.displayName} on ${a.machineLabel}\nRepo ${c.repoName}\nBase ${c.baseCommit?.take(10) ?: "none"}\nRevision ${c.fingerprint.take(16)}\n${c.files.size} files\n\n" +
                "This is a Herdr review acknowledgment of exactly this revision. It does not commit, push, merge or approve a pull request.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Approve") { _, _ -> io({ it.review(a.sessionId, c.fingerprint, approve = true) }, ::reviewed) })
    }

    private fun reviewed(r: Receipt) {
        showToast(if (r.status == ReceiptStatus.ACCEPTED) "Review recorded for this revision." else receiptText(r))
        if (r.code == "revision_changed") { invalidated = true; load() }
    }

    private fun back() { if (diff != null) { diff = null; render() } else finish() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = back()

    override fun onWheel(down: Boolean): Boolean {
        val n = diff?.hunks?.size ?: return false
        if (n == 0) return true
        hunk = (hunk + if (down) 1 else -1).coerceIn(0, n - 1)
        render()
        return true
    }

    private fun describe(e: Throwable) = when {
        e is BridgeError && e.code == "diff_unavailable_remote" -> "Diffs for this machine need a bridge adapter running on it."
        e is BridgeError && e.code == "not_a_repository" -> "This agent's folder isn't a Git repository."
        else -> dev.r1ptt.net.friendly(e)
    }

    companion object {
        const val EXTRA_FILE = "herdr.changes.file"
    }
}

private fun <T> CoroutineScope.launchCatching(work: () -> T, done: (T?, Throwable?) -> Unit) = launch {
    val r = runCatching { withContext(Dispatchers.IO) { work() } }
    done(r.getOrNull(), r.exceptionOrNull())
}
