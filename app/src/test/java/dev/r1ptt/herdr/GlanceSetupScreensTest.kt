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
import dev.r1ptt.QuickControlsActivity
import dev.r1ptt.data.ConfigBlobStorage
import dev.r1ptt.data.ConfigCipher
import dev.r1ptt.data.ConfigCodec
import dev.r1ptt.data.EncryptedConfigPersistence
import dev.r1ptt.herdr.ui.AsleepActivity
import dev.r1ptt.herdr.ui.BriefingActivity
import dev.r1ptt.herdr.ui.HerdActivity
import dev.r1ptt.herdr.ui.HerdActivityFeed
import dev.r1ptt.herdr.ui.HerdListActivity
import dev.r1ptt.herdr.ui.QrScanActivity
import dev.r1ptt.herdr.ui.SetupActivity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.net.URLEncoder
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The glance and set-up screens against the fixture bridge: first-run pairing by a scanned code,
 * the asleep screen and its wake, the spoken briefing (silent here: no voice), the inbox, the herd
 * deck and quick controls, each rendered at 480×640 into build/ui-snapshots/glance/.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class, qualifiers = "w404dp-h539dp-190dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class GlanceSetupScreensTest {
    private lateinit var app: App
    private lateinit var bridge: Process
    private lateinit var data: File
    private lateinit var url: String
    private val bridgeDir = File("../bridge").absoluteFile
    private val out = File("build/ui-snapshots/glance").apply { mkdirs() }
    private val opened = mutableListOf<org.robolectric.android.controller.ActivityController<*>>()

    private fun node(vararg args: String): Process =
        ProcessBuilder(listOf("node", "src/main.ts") + args + listOf("--fixtures", "--data", data.path)).directory(bridgeDir).redirectErrorStream(true).start()

    @Before fun start() {
        val nodeMajor = runCatching { ProcessBuilder("node", "--version").start().inputStream.bufferedReader().readText().trim().removePrefix("v").substringBefore('.').toInt() }.getOrNull()
        assumeTrue("Node 24+ runs the bridge's TypeScript directly", (nodeMajor ?: 0) >= 24)
        app = RuntimeEnvironment.getApplication() as App
        data = Files.createTempDirectory("herdr-bridge").toFile()
        bridge = node("serve", "--port", "0")
        url = Regex("https?://\\S+").find(bridge.inputStream.bufferedReader().lineSequence().first { it.contains("listening on") })!!.value
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
        Herdr::class.java.getDeclaredField("pairing").apply { isAccessible = true }.set(app.herdr, EncryptedConfigPersistence(JSONObject(), memory, plain, codec))
    }

    @After fun stop() {
        opened.asReversed().forEach { runCatching { it.pause().stop().destroy() } }
        dev.r1ptt.ui.DeviceActivity.resetForTest()
        if (::bridge.isInitialized) bridge.destroy()
        if (::data.isInitialized) data.deleteRecursively()
    }

    private fun enrollCode(): String {
        val p = node("enroll")
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor(20, TimeUnit.SECONDS)
        return Regex("Enrollment code: (\\S+)").find(text)!!.groupValues[1]
    }

    private fun settle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun until(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!check()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
            Thread.sleep(25)
            settle()
        }
    }

    private fun <T : Activity> open(type: Class<T>, extras: Intent.() -> Unit = {}): T =
        Robolectric.buildActivity(type, Intent(app, type).apply(extras)).setup().also { opened += it }.get().also { settle() }

    private fun snap(activity: Activity, name: String) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, 480, 640)
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).also { it.drawColor(Color.BLACK); content.draw(it) }
        File(out, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun texts(view: View): List<String> =
        (if (view is TextView) listOf(view.text.toString()) else emptyList()) + if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
    private fun screenText(a: Activity) = texts(a.findViewById(android.R.id.content)).joinToString("\n")

    private fun find(a: Activity, label: String): View {
        fun look(v: View): View? = if (v is TextView && v.text.toString() == label) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { look(g.getChildAt(it)) } }
        var target: View? = look(a.findViewById(android.R.id.content)) ?: error("no '$label' in:\n${screenText(a)}")
        while (target != null && !target.isClickable && !target.isLongClickable) target = target.parent as? View
        return checkNotNull(target) { "'$label' is not tappable" }
    }

    private fun click(a: Activity, label: String) { find(a, label).performClick(); settle() }

    @Test fun `a new R1 is welcomed, scans a bridge's code, confirms what it grants, pairs and practises the button`() {
        val setup = open(SetupActivity::class.java)
        assertTrue(screenText(setup).contains("Get started"))
        snap(setup, "20-setup-welcome")
        click(setup, "Get started")
        assertTrue(screenText(setup).contains("Get on your network"))
        snap(setup, "21-setup-network")

        val scan = open(QrScanActivity::class.java)
        snap(scan, "22-pair-scan")

        click(setup, "SCAN")
        val started = shadowOf(setup).nextStartedActivityForResult
        assertEquals(QrScanActivity::class.java.name, started.intent.component?.className)
        val expires = Instant.now().plusSeconds(8 * 60 + 30).toString()
        val link = "herdr://pair?u=${URLEncoder.encode(url, "UTF-8")}&c=${enrollCode()}&s=read%2Cact%2Creview&x=${URLEncoder.encode(expires, "UTF-8")}"
        shadowOf(setup).receiveResult(started.intent, Activity.RESULT_OK, Intent().putExtra(QrScanActivity.EXTRA_LINK, link))
        settle()
        val confirm = screenText(setup)
        assertTrue(confirm, confirm.contains("FOUND A BRIDGE") && confirm.contains("read · act · review") && confirm.contains("expires in 8 min"))
        snap(setup, "23-pair-confirm")
        click(setup, "Pair")
        until("paired") { app.herdr.paired && screenText(setup).contains("Hold the side button") }
        snap(setup, "24-setup-practice")
        val heard = SetupActivity::class.java.getDeclaredMethod("onDraft", String::class.java).apply { isAccessible = true }
        heard.invoke(setup, "Hi.")
        settle()
        assertTrue("practice shows the words back, nothing is sent", screenText(setup).contains("I heard “Hi”."))
    }

    @Test fun `asleep, briefing, inbox, the herd deck and quick controls show the live herd`() {
        app.herdr.pair(url, enrollCode(), "Glance R1")
        val home = open(HerdActivity::class.java)
        until("live herd") { app.herdr.state.link == Link.LIVE && app.herdr.state.deck.size == 3 }
        settle()

        val asleep = open(AsleepActivity::class.java)
        until("asleep counts") { screenText(asleep).contains("HOLD TO WAKE") && screenText(asleep).contains("needs you") }
        assertTrue("no names or questions over the lock screen", !screenText(asleep).contains("migration"))
        snap(asleep, "30-asleep")

        val briefing = open(BriefingActivity::class.java)
        val said = screenText(briefing)
        assertTrue(said, said.contains("Claude is waiting on you.") && said.contains("Show me Claude"))
        assertTrue("silent without a voice: no SPEAKING header", textView(briefing, "SPEAKING").let { !it.isShown })
        snap(briefing, "31-briefing")
        click(briefing, "Show me Claude")
        assertEquals("Show me opens the waiting agent's question", dev.r1ptt.herdr.ui.DecisionActivity::class.java.name, shadowOf(briefing).nextStartedActivity.component?.className)

        val inbox = open(HerdActivityFeed::class.java)
        until("inbox events") { screenText(inbox).contains("EARLIER") && !screenText(inbox).contains("Loading…") }
        assertTrue(screenText(inbox), screenText(inbox).contains("1 OPEN") && screenText(inbox).contains("Which migration strategy should I use?"))
        snap(inbox, "32-inbox")

        val deck = open(HerdListActivity::class.java)
        until("herd deck") { screenText(deck).contains("1 / 3") }
        snap(deck, "33-herd-deck")
        deck.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 0, 0, -1, 108))
        settle()
        assertTrue("the wheel deals the next card", screenText(deck).contains("2 / 3"))

        // Herdr OS's shade: a drag down from the top of the pasture pulls it over the screen.
        fun drag(a: android.app.Activity, from: Float, to: Float) {
            val t = android.os.SystemClock.uptimeMillis()
            a.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, 240f, from, 0))
            listOf(.3f, .6f, 1f).forEach { f -> a.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 20, android.view.MotionEvent.ACTION_MOVE, 240f, from + (to - from) * f, 0)) }
            a.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 60, android.view.MotionEvent.ACTION_UP, 240f, to, 0))
            settle()
        }
        drag(home, 20f, 600f)
        val shade = screenText(home)
        assertTrue(shade, shade.contains("1 NEEDS YOU") && shade.contains("Which migration strategy should I use?") && shade.contains("Walkie") && shade.contains("Speak alerts"))
        snap(home, "36-shade")
        home.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 0, 0, -1, 108))
        assertTrue("the wheel moves focus inside the shade", find(home, "Answer").foreground != null)
        home.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        Thread.sleep(300); settle()
        assertTrue("Back puts the shade away", !screenText(home).contains("Speak alerts"))
        drag(home, 300f, 600f)
        assertTrue("a drag that starts below the top band is the pasture's, not the shade's", !screenText(home).contains("Speak alerts"))

        val menu = open(dev.r1ptt.herdr.ui.MenuActivity::class.java)
        assertTrue(screenText(menu), screenText(menu).contains("1 WAITING ON YOU") && screenText(menu).contains("robotOS home"))
        snap(menu, "35-menu")
        repeat(9) { menu.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 0, 0, -1, 108)) }
        settle()
        assertTrue("the wheel stops at the last row, which is highlighted", find(menu, "robotOS home").background != null && find(menu, "Inbox").background == null)
        click(menu, "Settings")
        assertEquals(dev.r1ptt.herdr.ui.HerdrSettingsActivity::class.java.name, shadowOf(menu).nextStartedActivity.component?.className)

        val controls = open(QuickControlsActivity::class.java)
        assertTrue(screenText(controls), screenText(controls).contains("POWER MODE") && screenText(controls).contains("Speak alerts"))
        snap(controls, "34-quick-controls")

        // Woken with nothing recorded before sleep: the waiting Claude is news, so the wake briefs.
        val wake = open(AsleepActivity::class.java)
        AsleepActivity::class.java.getDeclaredField("before").apply { isAccessible = true }.set(wake, emptyMap<String, Any>())
        find(wake, "HOLD TO WAKE").performLongClick()
        settle()
        assertEquals(BriefingActivity::class.java.name, shadowOf(wake).nextStartedActivity.component?.className)
        assertTrue(wake.isFinishing)
        assertTrue(!home.isFinishing)
    }

    private fun textView(a: Activity, label: String): TextView {
        fun look(v: View): TextView? = if (v is TextView && v.text.toString() == label) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { look(g.getChildAt(it)) } }
        return look(a.findViewById(android.R.id.content)) ?: error("no '$label'")
    }
}
