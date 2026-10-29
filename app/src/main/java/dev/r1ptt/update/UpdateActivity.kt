package dev.r1ptt.update

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.r1ptt.App
import dev.r1ptt.R
import dev.r1ptt.ui.DeviceActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class UpdateActivity : DeviceActivity() {
    private val updates get() = (application as App).updates
    private var uiScope: kotlinx.coroutines.CoroutineScope? = null
    private lateinit var status: TextView
    private lateinit var label: TextView
    private lateinit var heading: TextView
    private lateinit var check: TextView
    private lateinit var download: TextView
    private lateinit var install: TextView
    private lateinit var confirm: TextView
    private lateinit var cancel: TextView
    private lateinit var release: TextView
    private lateinit var notes: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = dev.r1ptt.herdr.ui.Design(this)
        val page = dev.r1ptt.herdr.ui.designPage(this, d, "Updates", null) { updates.cancel(); finish() }
        setContentView(page.root)
        wheelScrollTarget = page.scroll
        val root = page.body
        fun button(text: String, primary: Boolean = false, action: () -> Unit) =
            (if (primary) d.pill(text) { action() } else dev.r1ptt.herdr.ui.quietPill(d, text, action))
                .also { root.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(10) }) }
        label = d.text("", 14, R.color.dim, 700, mono = true).apply { letterSpacing = .1f; root.addView(this) }
        heading = d.text("", 44, R.color.fg, 700).apply { letterSpacing = -.02f; setPadding(0, d.px(8), 0, 0); root.addView(this) }
        status = d.text("", 18, R.color.dim, 500).apply { setLineSpacing(0f, 1.15f); setPadding(0, d.px(14), 0, d.px(10)); root.addView(this) }
        release = d.text("", 13, R.color.dim, 500, mono = true).apply { setPadding(0, 0, 0, d.px(12)); root.addView(this) }
        root.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        check = button("Check for updates", primary = true) { updates.check() }
        download = button("Download update", primary = true) { updates.download() }
        install = button("Install update", primary = true) {
            if (!packageManager.canRequestPackageInstalls()) {
                showDeviceDialog(AlertDialog.Builder(this).setTitle("Allow robotOS update requests?")
                    .setMessage("Android requires you to allow this source before robotOS can request an update. You will still confirm each installation. You can turn this off again in Android settings.")
                    .setPositiveButton("Open Android settings") { _, _ ->
                        try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                        catch (_: Exception) { status.setText(R.string.update_permission_hint) }
                    }.setNegativeButton("Cancel", null))
            } else {
                showDeviceDialog(AlertDialog.Builder(this).setTitle("Install the verified update?")
                    .setMessage("Finish your conversation first. Voice will pause while Android confirms and installs the update. Your conversations and settings stay in place.")
                    .setPositiveButton("Continue") { _, _ -> updates.install() }
                    .setNegativeButton("Cancel", null))
            }
        }
        confirm = button("Continue to Android confirmation", primary = true) {
            updates.confirmation()?.let {
                try { @Suppress("DEPRECATION") startActivityForResult(it, CONFIRM_INSTALL) }
                catch (_: Exception) { updates.confirmationReturned(cancelled = true) }
            }
        }
        cancel = button("Cancel update") { updates.cancel() }
        notes = button("Release notes") {
            updates.state.value.release?.let { version ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${ReleaseManifest.REPOSITORY}/releases/tag/${version.tag}"))) }
                    .onFailure { status.text = "No browser is available to open release notes." }
            }
        }
        root.addView(d.text("CHECKS ONLY WHEN YOU ASK", 13, R.color.dim, 500, mono = true).apply { setPadding(0, d.px(12), 0, 0) })
        render(updates.state.value)
    }

    override fun onResume() {
        super.onResume()
        updates.foreground(true)
        uiScope = MainScope().also { scope -> scope.launch { updates.state.collect(::render) } }
    }

    private fun render(s: UpdateState) {
        status.text = s.message
        val r = s.release
        val ready = r != null && s.phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.READY)
        label.text = when {
            ready -> "UPDATE READY · ${android.text.format.Formatter.formatShortFileSize(this, r!!.apkSize).uppercase()}"
            r != null -> s.phase.name
            else -> "INSTALLED"
        }
        label.setTextColor(getColor(if (ready) R.color.accent else R.color.dim))
        heading.text = "robotOS ${r?.versionName ?: updates.installedVersion()}"
        val working = s.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING) || updates.installing
        check.visibility = if (s.phase == UpdatePhase.IDLE && !working) View.VISIBLE else View.GONE
        download.visibility = if (s.phase == UpdatePhase.AVAILABLE && !working) View.VISIBLE else View.GONE
        install.visibility = if (s.phase == UpdatePhase.READY && !working) View.VISIBLE else View.GONE
        confirm.visibility = if (s.phase == UpdatePhase.CONFIRM && updates.installing) View.VISIBLE else View.GONE
        cancel.visibility = if (working) View.VISIBLE else View.GONE
        notes.visibility = if (s.release != null && !working) View.VISIBLE else View.GONE
        release.visibility = if (s.release != null) View.VISIBLE else View.GONE
        release.text = s.release?.let { "INSTALLED ${updates.installedVersion()} · COMMIT ${it.commitSha.take(7)}".uppercase() }.orEmpty()
    }

    override fun onPause() {
        uiScope?.cancel(); uiScope = null
        updates.foreground(false)
        super.onPause()
    }

    @Deprecated("Uses platform activity result for PackageInstaller's confirmation UI")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CONFIRM_INSTALL) updates.confirmationReturned(resultCode != RESULT_OK)
    }

    companion object { private const val CONFIRM_INSTALL = 1 }
}
