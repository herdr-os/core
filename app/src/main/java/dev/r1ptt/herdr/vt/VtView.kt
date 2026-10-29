package dev.r1ptt.herdr.vt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import dev.r1ptt.ui.Fonts
import kotlin.math.floor

/**
 * Draws a [VtScreen] as a monospace grid: backgrounds first, then text in runs of one color and
 * style, then the cursor. The text size is chosen so every column fits the view's width.
 */
class VtView(context: Context) : View(context) {
    var screen: VtScreen? = null
        set(value) { field = value; invalidate() }

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.mono(context, 400) }
    private val bold = Paint(text).apply { typeface = Fonts.mono(context, 700) }
    private val fill = Paint()
    private val run = StringBuilder()

    /** Height in pixels for [rows] rows at [cols] columns across [width] pixels. */
    fun heightFor(cols: Int, rows: Int, width: Int): Int { size(cols, width); return (rows * lineHeight).toInt() }

    private var cellWidth = 0f
    private var lineHeight = 0f
    private var sizedFor = -1

    private fun size(cols: Int, width: Int) {
        if (sizedFor == cols * 100_000 + width || cols == 0) return
        sizedFor = cols * 100_000 + width
        text.textSize = 20f
        val perCell = text.measureText("M")
        text.textSize = 20f * width / (perCell * cols)
        bold.textSize = text.textSize
        cellWidth = width.toFloat() / cols
        lineHeight = text.fontSpacing
    }

    override fun onDraw(canvas: Canvas) {
        val s = screen ?: return
        size(s.cols, width)
        canvas.drawColor(s.defaultBg)
        for (r in 0 until s.rows) {
            val top = r * lineHeight
            for (c in 0 until s.cols) {
                val inverse = s.flags(r, c) and VtScreen.INVERSE != 0
                val bg = if (inverse) s.fg(r, c) else s.bg(r, c)
                // Whole-pixel edges shared with the neighbor cell, so a colored run has no seams.
                if (bg != VtScreen.NONE) { fill.color = bg; canvas.drawRect(floor(c * cellWidth), floor(top), floor((c + 1) * cellWidth), floor(top + lineHeight), fill) }
            }
            val baseline = top - text.ascent()
            var c = 0
            while (c < s.cols) {
                val start = c
                val flags = s.flags(r, c)
                val fg = if (flags and VtScreen.INVERSE != 0) (s.bg(r, c).takeIf { it != VtScreen.NONE } ?: s.defaultBg) else s.fg(r, c)
                run.setLength(0)
                while (c < s.cols && s.flags(r, c) == flags && s.fg(r, c) == s.fg(r, start)) {
                    val cp = s.codepoint(r, c)
                    run.appendCodePoint(if (cp == 0) ' '.code else cp)
                    c++
                }
                if (run.isBlank()) continue
                val paint = if (flags and VtScreen.BOLD != 0) bold else text
                paint.color = fg
                paint.alpha = if (flags and VtScreen.FAINT != 0) 150 else 255
                paint.isUnderlineText = flags and VtScreen.UNDERLINE != 0
                paint.textSkewX = if (flags and VtScreen.ITALIC != 0) -0.2f else 0f
                // Cells are a fixed grid; draw each run from its first cell so wide glyphs can't drift the row.
                canvas.drawText(run.toString(), start * cellWidth, baseline, paint)
            }
        }
        if (s.cursorRow >= 0) {
            fill.color = s.defaultFg; fill.alpha = 140
            canvas.drawRect(s.cursorCol * cellWidth, s.cursorRow * lineHeight, (s.cursorCol + 1) * cellWidth, (s.cursorRow + 1) * lineHeight, fill)
            fill.alpha = 255
        }
    }
}
