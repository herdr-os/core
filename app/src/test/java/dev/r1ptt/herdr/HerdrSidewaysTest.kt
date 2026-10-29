package dev.r1ptt.herdr

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.BatteryManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.r1ptt.App
import dev.r1ptt.data.ConfigBlobStorage
import dev.r1ptt.data.ConfigCipher
import dev.r1ptt.data.ConfigCodec
import dev.r1ptt.data.EncryptedConfigPersistence
import dev.r1ptt.herdr.model.Insight
import dev.r1ptt.herdr.model.Snapshot
import dev.r1ptt.herdr.ui.AgentActivity
import dev.r1ptt.herdr.ui.DecisionActivity
import dev.r1ptt.herdr.ui.Design
import dev.r1ptt.herdr.ui.HerdActivity
import dev.r1ptt.herdr.ui.HerdrScreen
import dev.r1ptt.herdr.ui.HerdrSettingsActivity
import dev.r1ptt.herdr.ui.inboxZero
import dev.r1ptt.update.UpdateActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.thread

/**
 * The keep-it-running and sideways screens, each from the state that brings it on: no pairing, a
 * bridge that never answers, a host gone quiet, low battery, an empty herd, an answer whose receipt
 * was lost, a question settled elsewhere, a usage limit, a full context, a crash, a hold with no
 * words. Rendered at 480×640 into build/ui-snapshots/sideways/.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class, qualifiers = "w404dp-h539dp-190dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HerdrSidewaysTest {
    private lateinit var app: App
    private val out = File("build/ui-snapshots/sideways").apply { mkdirs() }
    private val opened = mutableListOf<org.robolectric.android.controller.ActivityController<*>>()

    // A bridge that accepts connections and never answers: the stream stays "connecting" and the state the test sets stays put.
    private val silent = ServerSocket(0)
    private val held = mutableListOf<Socket>()

    @Before fun start() {
        app = RuntimeEnvironment.getApplication() as App
        thread(isDaemon = true) { runCatching { while (true) held += silent.accept() } }
    }

    @After fun stop() {
        opened.asReversed().forEach { runCatching { it.pause().stop().destroy() } }
        dev.r1ptt.ui.DeviceActivity.resetForTest()
        held.forEach { runCatching { it.close() } }
        silent.close()
    }

    private fun pairTo(url: String?) {
        val memory = object : ConfigBlobStorage {
            var blob: String? = null
            override fun read() = blob
            override fun write(blob: String) { this.blob = blob }
        }
        val plain = object : ConfigCipher {
            override fun seal(plain: String, allowKeyCreation: Boolean) = plain
            override fun open(blob: String) = blob
        }
        val codec = object : ConfigCodec<JSONObject> {
            override fun encode(value: JSONObject) = value.toString()
            override fun decode(plain: String) = JSONObject(plain)
        }
        val identity = if (url == null) JSONObject() else JSONObject().put("url", url).put("token", "t").put("device_id", "dev_7f3a91c2").put("scopes", JSONArray(listOf("read", "act")))
        Herdr::class.java.getDeclaredField("pairing").apply { isAccessible = true }.set(app.herdr, EncryptedConfigPersistence(identity, memory, plain, codec))
    }

    private val silentUrl get() = "http://127.0.0.1:${silent.localPort}"

    private fun problem(text: String?) = Herdr::class.java.getDeclaredField("problem").apply { isAccessible = true }.set(app.herdr, text)

    private fun machine(id: String, up: Boolean, minutesAgo: Long = 0) = JSONObject().put("machine_id", id).put("label", id).put("kind", if (id == "cerebro") "local" else "ssh")
        .put("status", if (up) "connected" else "disconnected").put("last_seen_at", Instant.now().minusSeconds(minutesAgo * 60).toString()).put("herdr_version", "0.9.5")

    private fun agent(id: String, host: String, kind: String, state: String, stale: Boolean = false, approval: String? = null, summary: String = "") = JSONObject()
        .put("session_id", id).put("machine_id", host).put("machine_label", host).put("agent", kind).put("title", "Release build").put("project", "herdr-os")
        .put("cwd", "/Users/d/src/herdr-os").put("state", state).put("priority", 10).put("generation", 1).put("state_since", "2026-10-29T09:12:00Z")
        .put("stale", stale).put("pending_approval_id", approval ?: JSONObject.NULL).put("summary", summary)

    private fun approval(id: String, session: String) = JSONObject().put("approval_id", id).put("session_id", session).put("generation", 1)
        .put("question", "Can I install the new build on the R1?").put("options", JSONArray().put(JSONObject().put("index", 1).put("label", "Yes, once")).put(JSONObject().put("index", 2).put("label", "No")))
        .put("evidence_fingerprint", "fp1").put("evidence", "").put("parse", "structured").put("status", "open")

    /** Makes [machines], [agents] and [approvals] the herd the R1 knows, over a link in [link] state, and redraws. */
    private fun herd(link: Link, machines: List<JSONObject>, agents: List<JSONObject>, approvals: List<JSONObject> = emptyList()) {
        app.herdr.state.apply(Snapshot.parse(JSONObject().put("cursor", "c1").put("machines", JSONArray(machines)).put("agents", JSONArray(agents))
            .put("approvals", JSONArray(approvals)).put("synced_at", Instant.now().minusSeconds(600).toString())))
        app.herdr.state.setLink(link)
        bump()
    }

    private fun bump() {
        val v = Herdr::class.java.getDeclaredField("_version").apply { isAccessible = true }.get(app.herdr) as kotlinx.coroutines.flow.MutableStateFlow<Int>
        v.value++
        settle()
    }

    private fun settle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun <T : Activity> open(type: Class<T>, session: String? = null): T {
        val intent = Intent(app, type).apply { session?.let { putExtra(HerdrScreen.EXTRA_SESSION, it) } }
        return Robolectric.buildActivity(type, intent).setup().also { opened += it }.get().also { settle() }
    }

    private fun snap(view: View, name: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 480, 640)
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).also { it.drawColor(Color.BLACK); view.draw(it) }
        File(out, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun snap(a: Activity, name: String) = snap(a.findViewById<ViewGroup>(android.R.id.content), name)

    private fun texts(view: View): List<String> =
        (if (view is TextView) listOf(view.text.toString()) else emptyList()) + if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
    private fun screenText(a: Activity) = texts(a.findViewById(android.R.id.content)).joinToString("\n")

    private fun click(a: Activity, label: String) {
        fun find(v: View): View? = if ((v is TextView && v.text.toString() == label) || (v !is TextView && v.contentDescription?.toString() == label)) v
            else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        var target: View? = find(a.findViewById(android.R.id.content)) ?: error("no '$label' in:\n${screenText(a)}")
        while (target != null && !target.isClickable) target = target.parent as? View
        checkNotNull(target) { "'$label' is not tappable" }.performClick()
        settle()
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!check()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
            Thread.sleep(25); settle()
        }
    }

    private val three = listOf(machine("cerebro", true), machine("namek", true), machine("ds-macbook-pro", false, 180))

    @Test fun `the pasture keeps saying what is true when the herd is missing, quiet, unreachable or low on power`() {
        pairTo(null)
        val home = open(HerdActivity::class.java)
        assertTrue(screenText(home).contains("NOT PAIRED") && screenText(home).contains("Pair a host"))
        snap(home, "x9-unpaired")
        problem("This R1's access was revoked on the bridge."); bump()
        assertTrue(screenText(home).contains("Your bridge let this R1 go."))
        snap(home, "x9-unpaired-revoked")
        problem(null)

        pairTo(silentUrl); bump()
        assertTrue("connecting with nothing cached is the boot screen", screenText(home).contains("WAKING THE HERD"))
        snap(home, "q2-boot")

        herd(Link.LIVE, three, emptyList())
        assertTrue(screenText(home).contains("3 HOSTS · 0 SHEEP") || screenText(home).contains("2 OF 3 HOSTS · 0 SHEEP"))
        assertTrue(screenText(home).contains("Give the herd a job?"))
        snap(home, "242-no-sheep")

        herd(Link.LIVE, listOf(machine("cerebro", true), machine("namek", false, 6)),
            listOf(agent("c1", "cerebro", "claude", "working"), agent("c2", "cerebro", "claude", "idle"), agent("p1", "namek", "pi", "idle", stale = true)))
        assertTrue(screenText(home), screenText(home).contains("namek went quiet.") && screenText(home).contains("NO ANSWER · 6 MIN"))
        assertTrue(screenText(home).contains("Its Pi was idle at"))
        snap(home, "244-host-down")
        click(home, "Hide namek")
        assertFalse("a hidden host's last-known sheep leave the pasture", screenText(home).contains("NAMEK"))

        herd(Link.RECONNECTING, three, listOf(agent("c1", "cerebro", "claude", "working"), agent("x1", "cerebro", "codex", "idle"), agent("p1", "namek", "pi", "idle")))
        problem("Failed to connect to /100.64.0.1:8787"); bump()
        assertTrue(screenText(home).contains("I can’t reach your herd."))
        snap(home, "1sw-cant-reach")
        click(home, "Show last known")
        assertFalse(screenText(home).contains("I can’t reach your herd."))
        problem(null)

        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, 12).putExtra(BatteryManager.EXTRA_SCALE, 100).putExtra(BatteryManager.EXTRA_PLUGGED, 0))
        herd(Link.LIVE, three, listOf(agent("c1", "cerebro", "claude", "working"), agent("x1", "cerebro", "codex", "idle")))
        assertTrue(screenText(home).contains("12%") && screenText(home).contains("I’m getting sleepy."))
        snap(home, "1tj-low-battery")
        click(home, "OK")
        assertFalse("waved off, the pasture comes back", screenText(home).contains("I’m getting sleepy."))

        HerdrScreen::class.java.getDeclaredMethod("missed").apply { isAccessible = true }.invoke(home)
        settle()
        assertTrue(screenText(home).contains("I didn’t catch that.") && screenText(home).contains("Type instead"))
        snap(home, "246-didnt-catch")
    }

    @Test fun `settings show hosts from the bridge, the companion's voice and power, and updates`() {
        pairTo(silentUrl)
        val settings = open(HerdrSettingsActivity::class.java)
        herd(Link.LIVE, three, listOf(agent("c1", "cerebro", "claude", "working"), agent("c2", "cerebro", "claude", "idle"), agent("p1", "namek", "pi", "idle"),
            agent("m1", "ds-macbook-pro", "codex", "idle", stale = true)))
        assertTrue(screenText(settings).contains("ds-macbook-pro offline"))
        snap(settings, "1qa-settings")
        click(settings, "Hosts")
        assertTrue(screenText(settings).contains("Herdr 0.9.5") && screenText(settings).contains("1 SHEEP"))
        snap(settings, "1rm-hosts")
        click(settings, "Back")
        click(settings, "Voice & companion")
        assertTrue(screenText(settings).contains("Say who needs me"))
        snap(settings, "1vw-voice")
        click(settings, "Say who needs me")
        assertTrue("the row flips the speak-alerts pref", app.herdr.speakAlerts)
        click(settings, "Back")
        click(settings, "Power")
        snap(settings, "settings-power")

        val update = open(UpdateActivity::class.java)
        assertTrue(screenText(update).contains("Check for updates"))
        snap(update, "1tk-update")
    }

    @Test fun `an answer whose receipt is lost is never re-sent, and one settled elsewhere says so`() {
        // A refused port: the answer goes out, nothing comes back, and the receipt can't be looked up.
        pairTo("http://127.0.0.1:" + ServerSocket(0).use { it.localPort })
        val agents = listOf(agent("c1", "cerebro", "claude", "approval_required", approval = "apr1"))
        herd(Link.LIVE, three, agents, listOf(approval("apr1", "c1")))
        val decision = open(DecisionActivity::class.java, "c1")
        herd(Link.LIVE, three, agents, listOf(approval("apr1", "c1")))
        click(decision, "Yes, once")
        click(decision, "Send answer")
        waitFor("an uncertain receipt") { screenText(decision).contains("NOT CONFIRMED YET") }
        assertTrue(screenText(decision).contains("I won’t send it twice.") && screenText(decision).contains("Check if it arrived"))
        snap(decision, "1xa-not-confirmed")

        pairTo(silentUrl)
        herd(Link.LIVE, three, agents, listOf(approval("apr1", "c1")))
        val again = open(DecisionActivity::class.java, "c1")
        herd(Link.LIVE, three, listOf(agent("c1", "cerebro", "claude", "working")))
        assertTrue(screenText(again), screenText(again).contains("Already answered") && screenText(again).contains("Nothing was sent from here."))
        snap(again, "247-already-answered")
    }

    @Test fun `an agent that crashed, ran out of usage or filled its context leads with it`() {
        pairTo(silentUrl)
        herd(Link.LIVE, three, listOf(agent("c1", "cerebro", "claude", "idle"), agent("x1", "cerebro", "codex", "failed", summary = "running npm test")))
        fun insight(a: Activity, i: Insight) {
            AgentActivity::class.java.getDeclaredField("insight").apply { isAccessible = true }.set(a, i)
            AgentActivity::class.java.getDeclaredField("shownKey").apply { isAccessible = true }.set(a, "")
            bump()
        }
        val crashed = open(AgentActivity::class.java, "x1")
        assertTrue(screenText(crashed).contains("STOPPED UNEXPECTEDLY") && screenText(crashed).contains("last: running npm test"))
        snap(crashed, "245-crashed")

        val limited = open(AgentActivity::class.java, "c1")
        insight(limited, Insight("claude-opus-5-5", null, 90_000, null, emptyList(), emptyList(), "You've hit your limit · resets 2:20pm (Europe/Madrid)"))
        assertTrue(screenText(limited), screenText(limited).contains("Resets 2:20pm.") && screenText(limited).contains("Finish on Sonnet"))
        assertFalse("no honest hand-off to Codex, so none is offered", screenText(limited).contains("Codex"))
        snap(limited, "248-rate-limited")
        click(limited, "Finish on Sonnet")
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertTrue("touch confirms before anything is sent", dialog.isShowing)
        dialog.dismiss()

        insight(limited, Insight("claude-opus-5-5", null, 619_000, null, emptyList(), emptyList(), null))
        assertFalse("no recorded window, no guessed alarm: 619k is healthy on a 1M model", screenText(limited).contains("Compact and keep going"))
        insight(limited, Insight("claude-opus-5-5", null, 192_000, 200_000, emptyList(), emptyList(), null))
        assertTrue(screenText(limited), screenText(limited).contains("96%") && screenText(limited).contains("Compact and keep going"))
        snap(limited, "294-context-full")
    }

    @Test fun `inbox zero is a view the inbox can drop in`() {
        val root = FrameLayout(app).apply { setBackgroundColor(Color.BLACK); addView(inboxZero(app, Design(app), 3), FrameLayout.LayoutParams(-1, -1)) }
        assertTrue(texts(root).contains("3 sheep are working. I’ll call you."))
        snap(root, "243-inbox-zero")
    }
}
