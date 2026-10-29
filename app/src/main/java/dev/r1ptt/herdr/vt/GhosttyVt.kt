package dev.r1ptt.herdr.vt

/**
 * A terminal emulator backed by Ghostty's libghostty-vt (native, via app/src/main/cpp/herdrvt.c).
 * Raw terminal bytes go in with [write]; [snapshot] returns the screen as cells. Not thread-safe:
 * use one instance from one thread.
 */
class GhosttyVt(cols: Int, rows: Int) : AutoCloseable {
    private var handle = nativeNew(cols, rows).also { check(it != 0L) { "libghostty-vt could not create a terminal" } }

    fun write(bytes: ByteArray, length: Int = bytes.size) {
        check(handle != 0L) { "closed" }
        nativeWrite(handle, bytes, length.coerceIn(0, bytes.size))
    }

    fun snapshot(): VtScreen {
        check(handle != 0L) { "closed" }
        return VtScreen(nativeSnapshot(handle) ?: error("snapshot failed"))
    }

    override fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    companion object {
        /** Loads the native library once; false where it isn't packaged (e.g. JVM tests without a host build). */
        val available: Boolean by lazy { runCatching { System.loadLibrary("herdrvt") }.isSuccess }

        @JvmStatic private external fun nativeNew(cols: Int, rows: Int): Long
        @JvmStatic private external fun nativeWrite(handle: Long, data: ByteArray, length: Int)
        @JvmStatic private external fun nativeSnapshot(handle: Long): IntArray?
        @JvmStatic private external fun nativeFree(handle: Long)
    }
}

/** One frame of the screen, read straight from the native snapshot array. */
class VtScreen(private val a: IntArray) {
    val rows get() = a[0]
    val cols get() = a[1]
    /** -1 when the cursor is not in the viewport. */
    val cursorRow get() = a[2]
    val cursorCol get() = a[3]
    val defaultFg get() = a[4]
    val defaultBg get() = a[5]

    private fun at(row: Int, col: Int) = HEADER + (row * cols + col) * PER_CELL
    fun codepoint(row: Int, col: Int) = a[at(row, col)]
    fun fg(row: Int, col: Int) = a[at(row, col) + 1]
    /** [NONE] when the cell has no background of its own. */
    fun bg(row: Int, col: Int) = a[at(row, col) + 2]
    fun flags(row: Int, col: Int) = a[at(row, col) + 3]

    fun line(row: Int): String = buildString {
        for (c in 0 until cols) codepoint(row, c).let { appendCodePoint(if (it == 0) ' '.code else it) }
    }.trimEnd()

    companion object {
        private const val HEADER = 6
        private const val PER_CELL = 4
        /** No color: transparent. Real colors are opaque, so this never collides (white is -1). */
        const val NONE = 0
        const val BOLD = 1
        const val ITALIC = 2
        const val FAINT = 4
        const val INVERSE = 8
        const val UNDERLINE = 16
    }
}
