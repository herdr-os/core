package dev.r1ptt.herdr

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.r1ptt.App
import dev.r1ptt.data.ConfigBlobStorage
import dev.r1ptt.data.ConfigCipher
import dev.r1ptt.data.ConfigCodec
import dev.r1ptt.data.EncryptedConfigPersistence
import dev.r1ptt.herdr.model.AgentState
import dev.r1ptt.herdr.model.ReceiptStatus
import dev.r1ptt.herdr.ui.AgentActivity
import dev.r1ptt.herdr.ui.ChangesActivity
import dev.r1ptt.herdr.ui.DecisionActivity
import dev.r1ptt.herdr.ui.DelegateActivity
import dev.r1ptt.herdr.ui.HerdActivity
import dev.r1ptt.herdr.ui.HerdrScreen
import dev.r1ptt.herdr.ui.HerdrSettingsActivity
import dev.r1ptt.herdr.ui.TerminalActivity
import dev.r1ptt.herdr.ui.ThreadActivity
import dev.r1ptt.herdr.ui.WalkieActivity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The R1 client against the real bridge (`node bridge/src/main.ts serve --fixtures`): enrollment,
 * the live event stream, a decision, a delegation, a revision-bound review, revocation, and every
 * Herdr screen rendered at the R1's 480×640 into build/ui-snapshots/herdr/.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class, qualifiers = "w404dp-h539dp-190dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HerdrBridgeTest {
    private lateinit var app: App
    private lateinit var bridge: Process
    private lateinit var data: File
    private lateinit var url: String
    private val bridgeDir = File("../bridge").absoluteFile
    private val out = File("build/ui-snapshots/herdr").apply { mkdirs() }

    private fun node(vararg args: String): Process =
        ProcessBuilder(listOf("node", "src/main.ts") + args + listOf("--fixtures", "--data", data.path)).directory(bridgeDir).redirectErrorStream(true).start()

    @Before fun start() {
        val nodeMajor = runCatching { ProcessBuilder("node", "--version").start().inputStream.bufferedReader().readText().trim().removePrefix("v").substringBefore('.').toInt() }.getOrNull()
        assumeTrue("Node 24+ runs the bridge's TypeScript directly (found ${nodeMajor ?: "none"})", (nodeMajor ?: 0) >= 24)
        app = RuntimeEnvironment.getApplication() as App
        data = Files.createTempDirectory("herdr-bridge").toFile()
        bridge = node("serve", "--port", "0")
        val ready = bridge.inputStream.bufferedReader().lineSequence().first { it.contains("listening on") }
        url = Regex("https?://\\S+").find(ready)!!.value
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
        Herdr::class.java.getDeclaredField("pairing").apply { isAccessible = true }
            .set(app.herdr, EncryptedConfigPersistence(JSONObject(), memory, plain, codec))
    }

    private val opened = mutableListOf<org.robolectric.android.controller.ActivityController<*>>()

    @After fun stop() {
        opened.asReversed().forEach { runCatching { it.pause().stop().destroy() } }
        dev.r1ptt.ui.DeviceActivity.resetForTest()
        if (::bridge.isInitialized) bridge.destroy()
        if (::data.isInitialized) data.deleteRecursively()
    }

    private fun enrollCode(scopes: String = "read,act,review"): String {
        val p = node("enroll", "--scopes", scopes)
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor(20, TimeUnit.SECONDS)
        return Regex("Enrollment code: (\\S+)").find(text)!!.groupValues[1]
    }

    private fun settle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Runs the main looper while background calls to the bridge complete. */
    private fun until(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!check()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
            Thread.sleep(25)
            settle()
        }
    }

    private fun <T : Activity> open(type: Class<T>, session: String? = null): T {
        val intent = Intent(app, type).apply { session?.let { putExtra(HerdrScreen.EXTRA_SESSION, it) } }
        return Robolectric.buildActivity(type, intent).setup().also { opened += it }.get().also { settle() }
    }

    private fun snap(activity: Activity, name: String) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, 480, 640)
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).also { it.drawColor(Color.BLACK); content.draw(it) }
        File(out, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun texts(view: View): List<String> = if (view.visibility != View.VISIBLE) emptyList() else
        (if (view is TextView) listOf(view.text.toString()) else emptyList()) + if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
    private fun screenText(a: Activity) = texts(a.findViewById(android.R.id.content)).joinToString("\n")

    private fun click(a: Activity, label: String) {
        fun find(v: View): View? = if (v is TextView && v.text.toString() == label) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        var target: View? = find(a.findViewById(android.R.id.content)) ?: error("no '$label' in:\n${screenText(a)}")
        while (target != null && !target.isClickable) target = target.parent as? View
        checkNotNull(target) { "'$label' is not tappable" }
        target.performClick()
        settle()
    }

    private fun wheel(a: Activity, down: Boolean) {
        val code = if (down) KeyEvent.KEYCODE_VOLUME_DOWN else KeyEvent.KEYCODE_VOLUME_UP
        val scan = if (down) 108 else 103
        a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, 0, -1, scan))
        a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0, 0, -1, scan))
        settle()
    }

    @Test fun `an R1 pairs, follows the herd live, answers, delegates and reviews against the real bridge`() {
        val unpaired = open(HerdActivity::class.java)
        assertTrue(screenText(unpaired).contains("Pair a host"))
        snap(unpaired, "00-unpaired")

        app.herdr.pair(url, enrollCode(), "Test R1")
        assertTrue(app.herdr.paired)
        val deck = open(HerdActivity::class.java)
        until("first live snapshot") { app.herdr.state.link == Link.LIVE && app.herdr.state.deck.size == 3 }
        settle()
        val cards = app.herdr.state.deck
        assertEquals("the blocked Claude is on top", "claude", cards.first().kind)
        assertEquals(AgentState.APPROVAL_REQUIRED, cards.first().state)
        assertTrue("the asking sheep is in front with its question", screenText(deck).contains("Claude Code is asking for you.") && screenText(deck).contains("Which migration strategy should I use?"))
        snap(deck, "01-herd")
        wheel(deck, down = true)
        assertTrue("wheel brought the next sheep forward and sent Claude to graze", screenText(deck).contains("CLAUDE · NEEDS DECISION"))
        snap(deck, "02-herd-wheel")
        wheel(deck, down = false)

        val claude = cards.first()
        val agent = open(AgentActivity::class.java, claude.sessionId)
        until("agent activity") { !screenText(agent).contains("Loading…") }
        snap(agent, "03-agent")
        opened.last().pause()
        assertTrue("leaving a Herdr screen hands push-to-talk back to the deck, not Hermes", app.turns.target === deck && deck.isActive())
        opened.last().resume()

        val decision = open(DecisionActivity::class.java, claude.sessionId)
        assertTrue(screenText(decision).contains("Which migration strategy should I use?"))
        snap(decision, "04-decision")
        wheel(decision, down = true)
        click(decision, "Full replacement")
        assertTrue("confirmation names the destination", screenText(decision).contains("To Claude on MacBook Pro"))
        assertTrue("side button is fenced during review", app.turns.reviewingApproval)
        snap(decision, "05-decision-confirm")
        click(decision, "Send answer")
        until("decision receipt") { screenText(decision).contains("Got it.") || screenText(decision).contains("Not sent") }
        assertTrue(screenText(decision), screenText(decision).contains("Got it."))
        snap(decision, "06-decision-done")

        val term = open(TerminalActivity::class.java, claude.sessionId)
        until("terminal output") { screenText(term).contains("AssertionError") }
        assertFalse("TUI chrome is stripped", screenText(term).contains("╭"))
        snap(term, "07-terminal")
        click(term, "Type")
        assertTrue(screenText(term).contains("TYPING INTO PANE"))
        snap(term, "07b-terminal-typing")
        click(term, "⏎ enter")
        wheel(term, down = false)
        until("keys reach the pane") { app.herdr.client()!!.activity(claude.sessionId).count { it.type == "action.receipt" && it.payload.optString("kind") == "keys" && it.payload.optString("status") == "accepted" } == 2 }
        click(term, "TYPING INTO PANE")
        assertTrue("tapping the chip goes back to read-only", screenText(term).contains("WHEEL · SCROLLBACK"))

        val subagents = open(dev.r1ptt.herdr.ui.WorkActivity::class.java, claude.sessionId)
        until("subagents from the session file") { screenText(subagents).contains("test-runner") }
        assertTrue("a working subagent shows its latest step", screenText(subagents).contains("› Bash: Run GhosttyVtTest on the R1"))
        snap(subagents, "15-subagents")
        val running = Robolectric.buildActivity(dev.r1ptt.herdr.ui.WorkActivity::class.java, Intent(app, dev.r1ptt.herdr.ui.WorkActivity::class.java)
            .putExtra(HerdrScreen.EXTRA_SESSION, claude.sessionId).putExtra(dev.r1ptt.herdr.ui.WorkActivity.EXTRA_TASKS, true)).setup().also { opened += it }.get()
        until("background commands") { screenText(running).contains("291 / 417 passed") }
        assertTrue("a failed command shows its exit code", screenText(running).contains("exit 1"))
        snap(running, "16-tasks")

        val model = open(dev.r1ptt.herdr.ui.ModelActivity::class.java, claude.sessionId)
        until("the model in use") { screenText(model).contains("IN USE") }
        wheel(model, down = true)
        assertTrue(screenText(model), screenText(model).contains("Switch to Sonnet 5.5"))
        snap(model, "18-model")

        val changes = open(ChangesActivity::class.java, claude.sessionId)
        until("changed files") { screenText(changes).contains("users.ts") }
        assertFalse("no secret content reaches the R1", screenText(changes).contains("DATABASE_URL"))
        snap(changes, "08-changes")
        click(changes, "src/api/users.ts")
        until("hunk") { screenText(changes).contains("Hunk 1") }
        assertTrue(screenText(changes).contains("res.status(201)"))
        snap(changes, "09-diff")

        click(changes, "Explain this hunk")
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        until("explain receipt") { app.herdr.client()!!.let { c -> runCatching { c.activity(claude.sessionId) }.getOrDefault(emptyList()) }.any { it.type == "action.receipt" && it.payload.optString("kind") == "explain" } }

        val files = open(dev.r1ptt.herdr.ui.FilesActivity::class.java, claude.sessionId)
        until("repository listing") { screenText(files).contains("src/") }
        assertTrue("secret-looking files are marked, never opened", screenText(files).contains("withheld"))
        snap(files, "14-files")
        click(files, "src/")
        until("src folder") { screenText(files).contains("api/") }
        click(files, "api/")
        until("api folder") { screenText(files).contains("users.ts") }
        click(files, "users.ts")
        val fromFiles = shadowOf(files).nextStartedActivity
        assertEquals("a changed file opens its diff", "src/api/users.ts", fromFiles.getStringExtra(dev.r1ptt.herdr.ui.ChangesActivity.EXTRA_FILE))

        val client = app.herdr.client()!!
        val rev = client.changes(claude.sessionId)
        File(data, "demo-repos/backend-api/src/api/users.ts").appendText("// agent kept editing\n")
        val stale = client.review(claude.sessionId, rev.fingerprint, approve = true)
        assertEquals("approval bound to the old revision is refused", "revision_changed", stale.code)
        val fresh = client.changes(claude.sessionId)
        assertEquals(ReceiptStatus.ACCEPTED, client.review(claude.sessionId, fresh.fingerprint, approve = true).status)

        val codex = cards.first { it.kind == "codex" }
        val delegate = Robolectric.buildActivity(DelegateActivity::class.java, Intent(app, DelegateActivity::class.java)
            .putExtra(DelegateActivity.EXTRA_TEXT, "Ask Codex on my MacBook to write regression tests for authentication")).setup().also { opened += it }.get()
        settle()
        snap(delegate, "10-delegate-draft")
        click(delegate, "Review")
        until("resolution") { screenText(delegate).contains("YOU SAID") }
        assertTrue(screenText(delegate), screenText(delegate).contains("Codex on MacBook Pro takes it. Send it there?"))
        until("the candidate's model from its session file") { screenText(delegate).contains("GPT-6-LUNA") }
        snap(delegate, "11-delegate-confirm")
        click(delegate, "Send to the herd")
        until("delegate receipt") { screenText(delegate).contains("Task started") || screenText(delegate).contains("Not started") }
        assertTrue(screenText(delegate), screenText(delegate).contains("Task started"))
        snap(delegate, "12-delegate-done")
        assertNotNull(codex)

        val codexModel = open(dev.r1ptt.herdr.ui.ModelActivity::class.java, codex.sessionId)
        until("Codex's model") { screenText(codexModel).contains("IN USE") }
        snap(codexModel, "19-model-codex")
        click(codexModel, "Open Codex's model menu")
        until("/model sent") { shadowOf(codexModel).nextStartedActivity?.getBooleanExtra(TerminalActivity.EXTRA_INTERACTIVE, false) == true }

        val settings = open(HerdrSettingsActivity::class.java)
        assertTrue(screenText(settings).contains("MacBook Pro"))
        snap(settings, "13-machines")

        val revoke = node("revoke", app.herdr.deviceId)
        revoke.waitFor(20, TimeUnit.SECONDS)
        app.herdr.refresh()
        until("revocation to clear the deck") { !app.herdr.paired && app.herdr.state.deck.isEmpty() }
        assertEquals(Link.UNPAIRED, app.herdr.state.link)
    }

    @Test fun `talking with an agent - its thread, a spoken message, a photo, a note about a subagent, and the walkie`() {
        app.herdr.pair(url, enrollCode(), "Test R1")
        open(HerdActivity::class.java)
        until("live herd") { app.herdr.state.link == Link.LIVE && app.herdr.state.deck.size == 3 }
        val codex = app.herdr.state.deck.first { it.kind == "codex" }
        val thread = open(ThreadActivity::class.java, codex.sessionId)
        until("the conversation from Codex's session file") { screenText(thread).contains("Writing tests/auth.test.ts") }
        assertTrue(screenText(thread).contains("Write regression tests for the auth module."))
        snap(thread, "20-thread")
        // A poll that only moves the context count must not rebuild the thread: no jump, no flash.
        val threadFrame = thread.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val insightField = ThreadActivity::class.java.getDeclaredField("insight").apply { isAccessible = true }
        val polled = insightField.get(thread) as dev.r1ptt.herdr.model.Insight
        insightField.set(thread, polled.copy(contextUsed = (polled.contextUsed ?: 0) + 1200))
        ThreadActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(thread)
        assertTrue("same frame after a context-only poll", thread.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) === threadFrame)
        insightField.set(thread, polled)

        thread.showPartial("Run them against the CI image too")
        assertTrue("a hold in the thread says where it goes", screenText(thread).contains("RELEASE TO SEND"))
        snap(thread, "21-thread-listening")
        thread.insert("Run them against the CI image too")
        until("the message to land in the agent's own session") { screenText(thread).lines().count { it == "Run them against the CI image too" } == 1 && !screenText(thread).contains("SENDING") }
        snap(thread, "22-thread-sent")

        val jpeg = java.io.ByteArrayOutputStream().also { Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        val sent = app.herdr.client()!!.attach(codex.sessionId, jpeg, "This shows right after the install.", codex.generation)
        assertEquals(sent.detail, ReceiptStatus.ACCEPTED, sent.status)
        val path = sent.result!!.getString("path")
        assertTrue("saved beside the agent's project: $path", path.contains("/tests-qa/.herdr-attachments/") && File(path).readBytes().contentEquals(jpeg))
        dev.r1ptt.herdr.ui.CameraActivity.localCopy(app, path).apply { parentFile!!.mkdirs() }.writeBytes(jpeg)
        val photo = open(ThreadActivity::class.java, codex.sessionId)
        until("the photo in the thread") { screenText(photo).contains("This shows right after the install.") }
        assertTrue(screenText(photo).contains("PHOTO"))
        snap(photo, "23-photo-sent")

        shadowOf(app).grantPermissions(android.Manifest.permission.CAMERA)
        val camera = open(dev.r1ptt.herdr.ui.CameraActivity::class.java, codex.sessionId)
        assertTrue(screenText(camera).contains("Show Codex"))
        snap(camera, "24-camera")

        val claude = app.herdr.state.deck.first { it.kind == "claude" }
        val about = Robolectric.buildActivity(ThreadActivity::class.java, Intent(app, ThreadActivity::class.java)
            .putExtra(HerdrScreen.EXTRA_SESSION, claude.sessionId).putExtra(ThreadActivity.EXTRA_ABOUT, "test-runner")).setup().also { opened += it }.get()
        settle()
        about.showPartial("Skip the bench test")
        assertTrue(screenText(about).contains("TO SUBAGENT") && screenText(about).contains("via Claude Code, its lead"))
        snap(about, "25-subagent-listening")
        about.clearPartial()

        val walkie = open(WalkieActivity::class.java, codex.sessionId)
        until("tuned to Codex") { screenText(walkie).contains("CH 2 · RECEIVING") }
        snap(walkie, "26-walkie")
        walkie.showPartial("Did the tests pass")
        assertTrue(screenText(walkie).contains("TRANSMITTING"))
        snap(walkie, "27-walkie-talk")
        walkie.clearPartial()
        wheel(walkie, down = false)
        until("channel 1 is Claude, which is asking") { screenText(walkie).contains("CH 1 · ASKING") }
        assertTrue("an asking agent gets a tap target", screenText(walkie).contains("Tap to answer"))
        val before = app.herdr.client()!!.activity(claude.sessionId).count { it.type == "action.receipt" }
        walkie.insert("yes")
        settle(200)
        assertEquals("a spoken yes sends nothing to an agent that is asking", before, app.herdr.client()!!.activity(claude.sessionId).count { it.type == "action.receipt" })
        snap(walkie, "28-walkie-asking")
    }

    @Test fun `on power the herd stays live with every screen closed, and notices become private notifications`() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        app.herdr.pair(url, enrollCode(), "Desk R1")
        val deck = Robolectric.buildActivity(HerdActivity::class.java).setup()
        until("live") { app.herdr.state.link == Link.LIVE }
        deck.pause().stop()
        settle()
        assertEquals("Pocket: nothing runs with the screen off", Link.OFFLINE, app.herdr.state.link)
        app.herdr.power(true)
        until("desk mode keeps the stream") { app.herdr.state.link == Link.LIVE }
        assertEquals("Desk", app.herdr.mode)
        val claude = app.herdr.state.deck.first()
        app.herdr.notify(Notice(claude.sessionId, AgentState.FAILED, "Claude on MacBook Pro: Failed"))
        val posted = shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).allNotifications.single()
        assertEquals(android.app.Notification.VISIBILITY_SECRET, posted.visibility)
        app.herdr.power(false)
        settle()
        assertEquals("unplugged without Connected mode: back to Pocket", Link.OFFLINE, app.herdr.state.link)
        deck.destroy()
    }

    @Test fun `a read-only R1 sees the herd but every action is refused`() {
        app.herdr.pair(url, enrollCode("read"), "Read-only R1")
        val client = app.herdr.client()!!
        val claude = client.snapshot().agents.first { it.kind == "claude" }
        val r = client.message(claude.sessionId, "hi", claude.generation)
        assertEquals(ReceiptStatus.REJECTED, r.status)
        assertEquals("forbidden_scope", r.code)
    }
}
