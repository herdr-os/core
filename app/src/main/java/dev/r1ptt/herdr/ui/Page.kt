package dev.r1ptt.herdr.ui

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import dev.r1ptt.App
import dev.r1ptt.R
import dev.r1ptt.herdr.Herdr
import dev.r1ptt.herdr.HerdrClient
import dev.r1ptt.herdr.model.Agent
import dev.r1ptt.herdr.model.Receipt
import dev.r1ptt.net.friendly
import dev.r1ptt.ui.DeviceUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A Herdr screen shown inside the pasture's own window instead of as its own Android activity:
 * opening one swaps a view, with no window to create and no trip through the system's activity
 * lifecycle (about 100 ms on the R1). The pasture ([HerdActivity]) keeps them in a stack.
 */
abstract class Page(val host: HerdActivity, val sessionId: String?) {
    protected val context: Context get() = host
    protected val app get() = host.application as App
    protected val herdr: Herdr get() = app.herdr
    protected val ui = DeviceUi(host)
    protected val d = Design(host)
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    protected val agent: Agent? get() = sessionId?.let(herdr.state::agent)

    /** Everything the page shows; the host animates this in and out. */
    val frame: FrameLayout = FrameLayout(host).apply { setBackgroundColor(host.getColor(R.color.bg)); isClickable = true }

    /** What the wheel scrolls when [onWheel] doesn't use a detent. */
    var scrollTarget: View? = null
        protected set(v) { field = v; if (host.topPage() === this) host.wheelScrollTarget = v }

    /** Extras the screen was opened with, beyond its session (see [PageTrampoline]). */
    var args: android.os.Bundle = android.os.Bundle.EMPTY
        internal set

    abstract fun render()
    /** Back pressed on this page; true when the page handled it rather than closing. */
    open fun onBack(): Boolean = false
    open fun onShow() {}
    open fun onHide() {}
    open fun onWheel(down: Boolean): Boolean = false
    open val wheelSteps = false
    open val listenPrompt: String? = null
    open val sendsTo: HerdrScreen.Addressee? = null
    /** Words from a hold of the side button; by default they become a new task, as on any screen. */
    open fun onDraft(text: String) = host.draft(text, sessionId)

    fun destroy() = scope.cancel()

    protected fun setContentView(view: View) {
        frame.removeAllViews()
        frame.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    protected fun finish() = host.closePage(this)
    protected fun open(type: Class<*>, sessionId: String? = null) = host.open(type, sessionId)
    protected fun showToast(message: CharSequence) = host.showToast(message)
    protected fun showDeviceDialog(builder: AlertDialog.Builder): AlertDialog = host.showDeviceDialog(builder)
    protected fun receiptText(r: Receipt) = host.receiptText(r)
    protected fun linkView(): TextView = host.linkView()

    /** Runs [work] against the bridge off the main thread, then [done] on it, while the page lives. */
    protected fun <T> io(work: (HerdrClient) -> T, done: (T) -> Unit) {
        val client = herdr.client() ?: return showToast("Pair a Herdr bridge first")
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { work(client) } }.onSuccess(done).onFailure { showToast(friendly(it)) }
        }
    }
}

/** Opens its page on the pasture; kept so links, notifications and other screens that name it still land. */
open class PageTrampoline : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(android.content.Intent(this, HerdActivity::class.java).putExtras(intent).putExtra(HerdActivity.EXTRA_PAGE, javaClass.name))
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
