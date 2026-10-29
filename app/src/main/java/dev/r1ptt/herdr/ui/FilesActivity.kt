package dev.r1ptt.herdr.ui

import android.content.Intent
import dev.r1ptt.R
import dev.r1ptt.herdr.model.FileEntry
import dev.r1ptt.herdr.model.Listing

/**
 * The agent's repository as folders and files, with Git status. Folders open; a changed file opens
 * its diff. File contents are never fetched here: the bridge only lists names, and paths that may
 * hold secrets are marked withheld.
 */
class FilesActivity : HerdrScreen() {
    private var path = ""
    private var listing: Listing? = null
    private var failure: String? = null
    private var shownKey = ""

    override fun onResume() {
        super.onResume()
        if (listing == null) load(path)
    }

    private fun load(next: String) {
        val id = sessionId ?: return
        io({ it.files(id, next) }) { l -> path = l.path; listing = l; failure = null; render() }
    }

    override fun render() {
        val l = listing
        val key = "${l?.path}${l?.entries?.size}$failure"
        if (key == shownKey) return
        shownKey = key
        val title = if (path.isEmpty()) "${agent?.displayName ?: "Agent"} · Files" else path.substringAfterLast('/')
        val page = ui.page(title) { back() }
        wheelScrollTarget = page.scroll
        when {
            failure != null -> page.body.addView(ui.text(failure!!, 16f))
            l == null -> page.body.addView(ui.note("Reading the repository on the host…"))
            else -> {
                page.body.addView(ui.mono(listOf(l.root, l.path).filter(String::isNotEmpty).joinToString("/"), R.color.dim, 10f).apply { setPadding(0, 0, 0, ui.dp(10)) })
                if (l.entries.isEmpty()) page.body.addView(ui.note("Empty folder."))
                l.entries.forEach { e -> page.body.addView(ui.actionRow(if (e.dir) "${e.name}/" else e.name, summary(e)) { open(e) }) }
            }
        }
        setContentView(page.root)
    }

    private fun summary(e: FileEntry) = when {
        e.withheld -> "withheld: may hold secrets"
        e.git == "?" -> "untracked"
        e.git == "A" -> "added"
        e.git == "D" -> "deleted"
        e.git == "M" -> if (e.dir) "has changes" else "modified"
        else -> ""
    }

    private fun open(e: FileEntry) {
        when {
            e.dir -> { listing = null; render(); load(e.path) }
            e.withheld -> showToast("Withheld: this path may contain secrets, so the bridge never sends it.")
            e.git != null -> startActivity(Intent(this, ChangesActivity::class.java).putExtra(EXTRA_SESSION, sessionId).putExtra(ChangesActivity.EXTRA_FILE, e.path))
            else -> showToast("No changes in ${e.name}. Contents stay on the host.")
        }
    }

    private fun back() {
        if (path.isEmpty()) return finish()
        listing = null; render(); load(path.substringBeforeLast('/', ""))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = back()
}
