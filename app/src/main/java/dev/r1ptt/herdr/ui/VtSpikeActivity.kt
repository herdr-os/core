package dev.r1ptt.herdr.ui

import android.os.Bundle
import android.os.SystemClock
import android.widget.LinearLayout
import dev.r1ptt.R
import dev.r1ptt.herdr.vt.GhosttyVt
import dev.r1ptt.herdr.vt.VtView
import dev.r1ptt.ui.DeviceActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Spike: libghostty-vt on the R1. Renders a TUI-style frame through the native emulator and
 * measures how fast the R1 parses and snapshots terminal output. Not reachable from the menus;
 * start it with `adb shell am start -n dev.r1ptt/.herdr.ui.VtSpikeActivity` as root.
 */
class VtSpikeActivity : DeviceActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = ui.page("libghostty-vt spike") { finish() }
        val stats = ui.mono("Measuring…", R.color.accent, 11f)
        val view = VtView(this)
        page.body.addView(stats)
        page.body.addView(view, LinearLayout.LayoutParams(-1, ui.dp(260)).apply { topMargin = ui.dp(8) })
        setContentView(page.root)
        if (!GhosttyVt.available) { stats.text = "libherdrvt is not packaged for this ABI"; return }

        scope.launch {
            val (screen, report) = withContext(Dispatchers.Default) {
                val cols = 48; val rows = 18
                GhosttyVt(cols, rows).use { vt ->
                    // Throughput: ~1 MB of colored log lines through the parser.
                    val line = "\u001b[32m✓\u001b[0m \u001b[1mtests/auth.test.ts\u001b[0m \u001b[38;5;244m(12 tests)\u001b[0m \u001b[38;2;255;79;18m84ms\u001b[0m\r\n".toByteArray()
                    val chunk = ByteArray(line.size * 256).also { b -> repeat(256) { line.copyInto(b, it * line.size) } }
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    var fed = 0
                    while (fed < 1_000_000) { vt.write(chunk); fed += chunk.size }
                    val parseMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                    vt.write(FRAME.toByteArray())
                    val t1 = SystemClock.elapsedRealtimeNanos()
                    val snap = vt.snapshot()
                    val snapMs = (SystemClock.elapsedRealtimeNanos() - t1) / 1e6
                    snap to "parse ${fed / 1024} KB: %.0f ms (%.1f MB/s) · snapshot %dx%d: %.2f ms".format(parseMs, fed / 1e3 / parseMs, cols, rows, snapMs)
                }
            }
            view.screen = screen
            val t2 = SystemClock.elapsedRealtimeNanos()
            view.post {
                stats.text = "$report · draw %.1f ms".format((SystemClock.elapsedRealtimeNanos() - t2) / 1e6)
                android.util.Log.i("herdrvt", stats.text.toString())
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** A TUI-style frame: clear, absolute positioning, box drawing, 256 and true color, reverse video. */
        private val FRAME = "\u001b[2J\u001b[H" +
            "\u001b[38;5;214m╭──────────────────────────────────────────────╮\u001b[0m\r\n" +
            "\u001b[38;5;214m│\u001b[0m \u001b[1mWhich migration strategy should I use?\u001b[0m       \u001b[38;5;214m│\u001b[0m\r\n" +
            "\u001b[38;5;214m│\u001b[0m                                              \u001b[38;5;214m│\u001b[0m\r\n" +
            "\u001b[38;5;214m│\u001b[0m \u001b[7m❯ 1. Incremental migration \u001b[0m                  \u001b[38;5;214m│\u001b[0m\r\n" +
            "\u001b[38;5;214m│\u001b[0m   2. Full replacement                        \u001b[38;5;214m│\u001b[0m\r\n" +
            "\u001b[38;5;214m╰──────────────────────────────────────────────╯\u001b[0m\r\n\r\n" +
            "\u001b[31m FAIL \u001b[0m src/user.test.ts \u001b[2m(1)\u001b[0m\r\n" +
            "   \u001b[31m×\u001b[0m should create user \u001b[3m(32ms)\u001b[0m\r\n" +
            "     \u001b[4mAssertionError\u001b[0m: expected \u001b[38;2;75;227;138m200\u001b[0m to be \u001b[38;2;255;92;122m201\u001b[0m\r\n\r\n" +
            "\u001b[16;1H\u001b[48;5;236m\u001b[38;5;250m esc to interrupt · ctrl+t todos            \u001b[0m" +
            "\u001b[18;3H> "
    }
}
