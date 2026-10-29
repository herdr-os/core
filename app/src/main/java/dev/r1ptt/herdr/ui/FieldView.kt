package dev.r1ptt.herdr.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View

/**
 * The pasture: sky for the hour, then the isometric field. Drawn in 480×640 design space. A [bare]
 * field, with no herd paired or found yet, is only a dashed outline.
 */
class FieldView(context: Context, private val daylight: Daylight, private val bare: Boolean = false) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val sky = Paint()
    private val diamond = Path().apply { moveTo(240f, 170f); lineTo(470f, 285f); lineTo(240f, 400f); lineTo(10f, 285f); close() }
    private val outline = Path().apply { moveTo(240f, 202f); lineTo(442f, 268f); lineTo(240f, 332f); lineTo(38f, 268f); close() }
    private val grid = listOf(
        floatArrayOf(182.5f, 198.75f, 412.5f, 313.75f), floatArrayOf(125f, 227.5f, 355f, 342.5f), floatArrayOf(67.5f, 256.25f, 297.5f, 371.25f),
        floatArrayOf(297.5f, 198.75f, 67.5f, 313.75f), floatArrayOf(355f, 227.5f, 125f, 342.5f), floatArrayOf(412.5f, 256.25f, 182.5f, 371.25f),
    )

    // Sky top color, field fill, field line. Sheep and orange never change with the hour.
    private val palette = when (daylight) {
        Daylight.DAWN -> intArrayOf(0xFF14243A.toInt(), 0xFF0D1820.toInt(), 0xFF22343A.toInt())
        Daylight.DAY -> intArrayOf(0xFF000000.toInt(), 0xFF0C100C.toInt(), 0xFF1E2A1E.toInt())
        Daylight.DUSK -> intArrayOf(0xFF3B170A.toInt(), 0xFF1C120A.toInt(), 0xFF3A2618.toInt())
        Daylight.NIGHT -> intArrayOf(0xFF000000.toInt(), 0xFF05070A.toInt(), 0xFF141A1E.toInt())
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val k = width / 480f
        canvas.drawColor(0xFF000000.toInt())
        if (palette[0] != 0xFF000000.toInt()) {
            sky.shader = LinearGradient(0f, 0f, 0f, 380f * k, palette[0], 0x00000000, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), 380f * k, sky)
        }
        canvas.save()
        canvas.scale(k, k)
        if (bare) {
            line.color = 0xFF3A3833.toInt()
            line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 6f), 0f)
            canvas.drawPath(outline, line)
            line.pathEffect = null
            canvas.restore()
            return
        }
        if (daylight == Daylight.DUSK) {
            fill.color = 0x59E8955A.toInt(); canvas.drawCircle(240f, 190f, 70f, fill)
            fill.color = 0x80F2B07A.toInt(); canvas.drawCircle(240f, 190f, 42f, fill)
        }
        if (daylight == Daylight.NIGHT) {
            fill.color = 0xD9E9E4D6.toInt(); canvas.drawCircle(389f, 149f, 17f, fill)
            fill.color = 0xFF000000.toInt(); canvas.drawCircle(401f, 143f, 17f, fill)
            fill.color = 0x99F4EFE6.toInt()
            for ((x, y) in listOf(60 to 150, 140 to 178, 250 to 140, 310 to 190, 440 to 200, 28 to 220, 200 to 196)) canvas.drawCircle(x.toFloat(), y.toFloat(), 1.4f, fill)
        }
        fill.color = palette[1]; canvas.drawPath(diamond, fill)
        line.color = palette[2]
        line.alpha = 140
        grid.forEach { canvas.drawLine(it[0], it[1], it[2], it[3], line) }
        line.alpha = 255
        canvas.drawPath(diamond, line)
        canvas.restore()
    }
}
