package dev.r1ptt.herdr.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.r1ptt.R
import dev.r1ptt.herdr.PairingLink
import dev.r1ptt.net.friendly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * First run: welcome, get on a network, pair a bridge (scan, confirm, or type the code), then try
 * the side button once. Each step is one screen; the bar on top counts four.
 */
class SetupActivity : HerdrScreen() {
    override val shadeAllowed = false
    enum class Step(val bar: Int) { WELCOME(1), NETWORK(2), CONFIRM(3), TYPE(3), PRACTICE(4) }

    private val d by lazy { Design(this) }
    private var step = Step.WELCOME
    private var link: PairingLink? = null
    private var pairing = false
    private var heard: String? = null
    private var shownKey = ""
    // Kept across redraws so a failed pairing doesn't wipe what was typed.
    private val urlField by lazy { ui.field("Bridge URL, e.g. http://100.80.68.69:8787", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) }
    private val codeField by lazy { ui.field("One-time code", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) }

    override val listenPrompt = "Say hi."

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        step = intent.getStringExtra(EXTRA_STEP)?.let { runCatching { Step.valueOf(it) }.getOrNull() } ?: Step.WELCOME
        getSharedPreferences("herdr", MODE_PRIVATE).edit().putBoolean(SEEN, true).apply()
    }

    override fun onResume() {
        super.onResume()
        shownKey = ""
        render()
    }

    private fun go(next: Step) { step = next; render() }

    override fun render() {
        val key = "$step$pairing$heard${herdr.paired}${link}"
        if (key == shownKey) return
        shownKey = key
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d.px(28), d.px(28), d.px(28), d.px(22))
        }
        root.addView(LinearLayout(this).apply {
            (1..4).forEach { i -> addView(View(context).apply { background = d.fill(if (i <= step.bar) R.color.accent else R.color.raised, 2) },
                LinearLayout.LayoutParams(0, d.px(4), 1f).apply { if (i > 1) marginStart = d.px(8) }) }
        })
        when (step) {
            Step.WELCOME -> welcome(root)
            Step.NETWORK -> network(root)
            Step.CONFIRM -> link?.let { confirm(root, it) } ?: return go(Step.NETWORK)
            Step.TYPE -> type(root)
            Step.PRACTICE -> practice(root)
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(root) }.also { wheelScrollTarget = it })
    }

    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }

    private fun welcome(root: LinearLayout) {
        root.addView(ImageView(this).apply { setImageResource(R.drawable.sheep_plain); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(d.px(116), d.px(116)).apply { topMargin = d.px(104); marginStart = d.px(4) })
        root.addView(d.text(SpannableStringBuilder("Your herd,\nin your pocket.").apply {
            setSpan(ForegroundColorSpan(getColor(R.color.accent)), 11, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }, 44, R.color.cream, 700).apply { setLineSpacing(0f, 1.0f); letterSpacing = -.02f }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(30) })
        root.addView(d.text("Watch, answer and steer the coding agents running on your computers.", 18, R.color.dim).apply { setLineSpacing(0f, 1.1f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(14) })
        root.addView(spacer())
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = d.fill(R.color.cream, 33)
            setPadding(d.px(28), d.px(8), d.px(8), d.px(8))
            addView(d.text("Get started", 18, R.color.on_accent, 700), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(this@SetupActivity).apply {
                setImageDrawable(getDrawable(R.drawable.ic_chevron_right)!!.mutate().apply { setTint(getColor(R.color.accent)) })
                scaleType = ImageView.ScaleType.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.on_accent)) }
            }, LinearLayout.LayoutParams(d.px(48), d.px(48)))
            isClickable = true
            contentDescription = "Get started"
            setOnClickListener { go(if (herdr.paired) Step.PRACTICE else Step.NETWORK) }
        }, LinearLayout.LayoutParams(-1, d.px(64)))
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        root.addView(d.text("HERDR OS · $version".uppercase(), 13, R.color.dim, 600, mono = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(14) })
    }

    private fun network(root: LinearLayout) {
        root.addView(d.text("Get on your network", 28, R.color.cream, 700), LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(22) })
        val online = app.radio.isOnline()
        val ssid = runCatching { @Suppress("DEPRECATION") getSystemService(WifiManager::class.java).connectionInfo?.ssid?.trim('"') }.getOrNull()?.takeUnless { it.isBlank() || it.startsWith("<") }
        root.addView(stepRow(if (online) null else 1, "Wi-Fi", if (online) ssid ?: "Connected" else "Not connected", "CHANGE".takeIf { online }) {
            runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }.onFailure { showToast("Wi-Fi settings unavailable") }
        })
        val ts = tailscale(this)
        root.addView(stepRow(if (ts == Tailscale.CONNECTED) null else 2, "Tailscale", when (ts) {
            Tailscale.CONNECTED -> "Connected · reaches your computers anywhere"
            Tailscale.INSTALLED -> "Open it and sign in to reach your computers anywhere"
            Tailscale.MISSING -> "Not installed · the same Wi-Fi works too"
        }, "OPEN".takeIf { ts == Tailscale.INSTALLED }, active = ts != Tailscale.MISSING) {
            packageManager.getLaunchIntentForPackage(TAILSCALE)?.let(::startActivity)
        })
        root.addView(stepRow(3, "Pair a computer", "Scan the code from herdr-bridge", "SCAN") { scan() })
        root.addView(spacer())
        root.addView(d.text("SKIP FOR NOW", 14, R.color.dim, 600, mono = true).apply {
            gravity = Gravity.CENTER; minHeight = d.px(56); isClickable = true; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    /** One setup row: a check when it is done, else its number; orange only for the one to do now. */
    private fun stepRow(number: Int?, title: String, sub: String, action: String?, active: Boolean = true, go: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = d.fill(R.color.field, 24)
        setPadding(d.px(18), d.px(16), d.px(18), d.px(16))
        if (!active) alpha = .45f
        addView(d.text(if (number == null) "✓" else "$number", 18, if (number == null) R.color.ok else if (active) R.color.accent else R.color.dim, 700).apply {
            gravity = Gravity.CENTER
            background = d.fill(if (number == null) R.color.field_line else R.color.raised, 10)
        }, LinearLayout.LayoutParams(d.px(40), d.px(40)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(d.text(title, 18, R.color.cream, 700))
            addView(d.text(sub, 14, R.color.dim, mono = true).apply { setPadding(0, d.px(3), 0, 0); letterSpacing = 0f })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(14) })
        action?.let { addView(d.text(it, 14, R.color.dim, 700, mono = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = d.px(8) }) }
        isClickable = active
        setOnClickListener { go() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(18) }
    }

    @Suppress("DEPRECATION")
    private fun scan() = startActivityForResult(Intent(this, QrScanActivity::class.java), SCAN)

    @Deprecated("Platform activity result, as DeviceActivity uses")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SCAN) return
        if (resultCode == QrScanActivity.RESULT_TYPE) return go(Step.TYPE)
        link = data?.getStringExtra(QrScanActivity.EXTRA_LINK)?.let(PairingLink::parse) ?: return
        go(Step.CONFIRM)
    }

    /** A scanned bridge, named before anything is claimed: where it is and what this R1 may do there. */
    private fun confirm(root: LinearLayout, l: PairingLink) {
        val uri = runCatching { URI(l.url) }.getOrNull()
        val host = uri?.host.orEmpty()
        val tailnet = host.startsWith("100.") || host.endsWith(".ts.net")
        val name = host.takeUnless { it.isEmpty() || it.first().isDigit() }?.substringBefore('.')
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(ImageView(context).apply { setImageResource(R.drawable.sheep_plain) }, LinearLayout.LayoutParams(d.px(72), d.px(72)))
            listOf(R.color.accent, R.color.accent, R.color.faint, R.color.raised).forEach { c ->
                addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(c)) } },
                    LinearLayout.LayoutParams(d.px(6), d.px(6)).apply { marginStart = d.px(6) })
            }
            addView(ImageView(context).apply {
                setImageDrawable(getDrawable(R.drawable.ic_server)!!.mutate().apply { setTint(getColor(R.color.cream)) })
                scaleType = ImageView.ScaleType.CENTER
                background = d.fill(R.color.raised, 22)
            }, LinearLayout.LayoutParams(d.px(72), d.px(72)).apply { marginStart = d.px(16) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(8) })
        root.addView(d.text("FOUND A BRIDGE", 14, R.color.accent, 700, mono = true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = d.px(22) })
        root.addView(d.text(if (name != null) "Pair with $name?" else "Pair with this bridge?", 28, R.color.cream, 700), LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(6) })
        fun fact(label: String, value: String) = root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = d.px(40)
            addView(d.text(label, 14, R.color.dim, 500, mono = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(d.text(value, 14, R.color.cream, mono = true).apply { letterSpacing = 0f; gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 2f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(4) })
        fact("ADDRESS", "${uri?.authority ?: l.url} · ${if (tailnet) "tailnet" else "local"}")
        if (l.scopes.isNotEmpty()) fact("THIS R1 MAY", l.scopes.joinToString(" · "))
        root.addView(spacer())
        val left = l.expiresAt?.let { runCatching { Duration.between(Instant.now(), Instant.parse(it)).toMinutes() }.getOrNull() }
        val expiry = when {
            left == null -> "The code works once."
            left < 1 -> "The code works once and has about a minute left."
            else -> "The code works once and expires in $left min."
        }
        root.addView(d.text("Only pair a bridge you run. $expiry The token stays sealed on this R1.", 16, R.color.dim).apply {
            background = d.fill(R.color.field, 20)
            setPadding(d.px(20), d.px(16), d.px(20), d.px(16))
            setLineSpacing(0f, 1.1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(16) })
        root.addView(LinearLayout(this).apply {
            addView(d.pill("Cancel", fill = R.color.raised, ink = R.color.cream, height = 64) { link = null; go(Step.NETWORK) }, LinearLayout.LayoutParams(d.px(130), -2))
            addView(d.pill(if (pairing) "Pairing…" else "Pair", height = 64) { if (!pairing) pair(l.url, l.code) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(10) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(18) })
    }

    private fun type(root: LinearLayout) {
        root.addView(d.text("Type the code", 28, R.color.cream, 700), LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(22) })
        root.addView(d.text("On the computer, run herdr-bridge enroll. It prints the address and a one-time code.", 16, R.color.dim).apply { setLineSpacing(0f, 1.1f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(8) })
        val url = urlField.also { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        val code = codeField.also { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        root.addView(url, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(18) })
        root.addView(code, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(10) })
        root.addView(spacer())
        root.addView(LinearLayout(this).apply {
            addView(d.pill("Back", fill = R.color.raised, ink = R.color.cream, height = 64) { go(Step.NETWORK) }, LinearLayout.LayoutParams(d.px(130), -2))
            addView(d.pill(if (pairing) "Pairing…" else "Pair", height = 64) {
                if (pairing) return@pill
                if (url.text.isBlank() || code.text.isBlank()) showToast("Enter the bridge URL and code") else pair(url.text.toString(), code.text.toString())
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(10) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(18) })
    }

    private fun pair(url: String, code: String) {
        pairing = true; render()
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { herdr.pair(url, code, Build.MODEL ?: "Rabbit R1") } }
            pairing = false
            r.onSuccess { link = null; go(Step.PRACTICE) }.onFailure { showToast(friendly(it)); shownKey = ""; render() }
        }
    }

    private fun practice(root: LinearLayout) {
        root.clipToPadding = false
        root.addView(d.text("LAST STEP · TRY IT", 14, R.color.accent, 700, mono = true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = d.px(22) })
        val said = heard
        root.addView(d.text(if (said == null) "Hold the side button and say hi." else "I heard “$said”.", 44, R.color.cream, 700).apply { letterSpacing = -.02f; setLineSpacing(0f, 1.0f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(8) })
        // The side button is on the R1's right edge, level with this mark.
        root.addView(FrameLayout(this).apply {
            fun disc(size: Int, color: Int) = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) } }
                .also { addView(it, FrameLayout.LayoutParams(d.px(size), d.px(size), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = -d.px(size / 2) + d.px(8) }) }
            disc(160, getColor(R.color.accent_soft))
            disc(100, 0x55FF4F12)
            addView(View(context).apply { background = d.fill(R.color.accent, 8) }, FrameLayout.LayoutParams(d.px(16), d.px(60), Gravity.END or Gravity.CENTER_VERTICAL))
            addView(d.text("HOLD HERE  →", 14, R.color.cream, 700, mono = true), FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = d.px(80) })
        }, LinearLayout.LayoutParams(-1, d.px(160)).apply { topMargin = d.px(40); marginEnd = -d.px(28) })
        root.addView(spacer())
        listOf("HOLD" to "TALK TO AN AGENT", "WHEEL" to "MOVE THROUGH THE HERD", "TOUCH" to "APPROVE · ONLY TOUCH").forEach { (key, what) ->
            root.addView(LinearLayout(this).apply {
                addView(d.text(key, 14, R.color.accent, 700, mono = true), LinearLayout.LayoutParams(d.px(102), -2))
                addView(d.text(what, 14, R.color.dim, 500, mono = true))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(12) })
        }
        if (said == null) root.addView(d.text("SKIP FOR NOW", 14, R.color.dim, 600, mono = true).apply {
            minHeight = d.px(48); gravity = Gravity.CENTER_VERTICAL; isClickable = true; setOnClickListener { done() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = d.px(10) })
        else root.addView(d.pill("Go to the pasture") { done() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(18) })
    }

    /** Practice only: the words are shown back, never sent anywhere. */
    override fun onDraft(text: String) {
        if (step != Step.PRACTICE) return
        heard = text.trimEnd('.', '!', '?')
        render()
    }

    private fun done() {
        startActivity(Intent(this, HerdActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    enum class Tailscale { MISSING, INSTALLED, CONNECTED }

    companion object {
        const val EXTRA_STEP = "herdr.setup.step"
        private const val SEEN = "setup_seen"
        private const val SCAN = 7303
        private const val TAILSCALE = "com.tailscale.ipn"

        /** First launch of an unpaired R1 opens the welcome once; after that the pasture's "Pair a host" does. */
        fun firstRun(context: Context) {
            val app = context.applicationContext as? dev.r1ptt.App ?: return
            if (app.herdr.paired || context.getSharedPreferences("herdr", Context.MODE_PRIVATE).getBoolean(SEEN, false)) return
            context.startActivity(Intent(context, SetupActivity::class.java))
        }

        /** Tailscale as far as this R1 can tell: installed, and whether a VPN is carrying traffic. */
        fun tailscale(context: Context): Tailscale {
            if (context.packageManager.getLaunchIntentForPackage(TAILSCALE) == null) return Tailscale.MISSING
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val vpn = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
            return if (vpn) Tailscale.CONNECTED else Tailscale.INSTALLED
        }
    }
}
