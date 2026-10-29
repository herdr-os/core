package dev.r1ptt.herdr.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.widget.ImageView
import dev.r1ptt.ui.DeviceActivity
import dev.r1ptt.ui.WheelPolicy

/**
 * Shows the design mocks pushed by `tools/design-preview.sh` at the panel's native 480×640, one per
 * screen, so legibility and touch sizes can be judged on the real R1. The wheel or a tap moves
 * between them. Not reachable from the menus.
 */
class DesignPreviewActivity : DeviceActivity() {
    private val image by lazy { ImageView(this).apply { setBackgroundColor(Color.BLACK); scaleType = ImageView.ScaleType.FIT_CENTER } }
    private val files by lazy { getExternalFilesDir("preview")?.listFiles { f -> f.extension == "png" }?.sortedBy { it.name }.orEmpty() }
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        image.setOnClickListener { show(index + 1) }
        setContentView(image)
        if (files.isEmpty()) showToast("No mocks. Run tools/design-preview.sh first.") else show(0)
    }

    private fun show(i: Int) {
        if (files.isEmpty()) return
        index = Math.floorMod(i, files.size)
        image.setImageBitmap(BitmapFactory.decodeFile(files[index].path))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!WheelPolicy.isWheel(event.keyCode, event.scanCode, event.device?.name)) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            show(index + if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
        }
        return true
    }
}
