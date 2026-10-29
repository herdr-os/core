package dev.r1ptt.herdr.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.text.TextUtils
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.r1ptt.R
import dev.r1ptt.herdr.model.ReceiptStatus
import java.io.File

/**
 * "Show it": a photo from the R1's camera for one agent. A tap on the shutter snaps and sends; a
 * hold on the side button snaps when you let go and sends your words with it. The bridge saves the
 * JPEG on the agent's machine and tells the agent where, so a model that reads images can look.
 */
class CameraActivity : HerdrScreen() {
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var stills: ImageReader? = null
    private var orientation = 0
    @Volatile private var note = ""
    private var busy = false
    private val d by lazy { Design(this) }

    override val listenPrompt get() = "Say what to look at. It snaps when you let go."
    override val sendsTo get() = agent?.let { Addressee("Photo for", who(it), it.machineLabel, release = "Release to snap and send") }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        preview = TextureView(this)
        status = d.text("", 18, R.color.fg, 600).apply { gravity = Gravity.CENTER; setPadding(d.px(32), 0, d.px(32), 0) }
        render()
    }

    override fun render() {
        if (::status.isInitialized && preview.parent != null) return
        val a = agent
        val h = Harness.of(a?.kind ?: "")
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = d.fill(R.color.field, 30)
            setPadding(d.px(10), d.px(8), d.px(22), d.px(8))
            addView(ImageView(context).apply { setImageResource(h.sheep); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(d.px(44), d.px(44)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(d.text("Show ${a?.let(::who) ?: "the agent"}", 18, R.color.fg, 700).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(d.text(listOfNotNull(a?.machineLabel, a?.project).joinToString(" · ").uppercase(), 13, R.color.dim, 500, mono = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d.px(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.px(14) })
        }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(d.px(18), d.px(18), d.px(18), 0) })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(d.px(24), 0, d.px(24), d.px(22))
            addView(View(context).apply {
                background = android.graphics.drawable.LayerDrawable(arrayOf(d.fill(R.color.raised, 42), d.fill(R.color.cream, 42))).apply { setLayerInset(1, d.px(9), d.px(9), d.px(9), d.px(9)) }
                contentDescription = "Snap and send"
                isClickable = true
                setOnClickListener { snap() }
            }, LinearLayout.LayoutParams(d.px(84), d.px(84)).apply { bottomMargin = d.px(22) })
            addView(LinearLayout(context).apply {
                addView(hint("Tap", "Snap"), LinearLayout.LayoutParams(0, -2, 1f))
                addView(hint("Hold", "Snap and say why"))
            }, LinearLayout.LayoutParams(-1, -2))
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun hint(key: String, what: String) = d.twoTone(key.uppercase(), "· ${what.uppercase()}", 13, R.color.accent, R.color.fg).apply {
        typeface = dev.r1ptt.ui.Fonts.mono(context, 500); letterSpacing = .08f
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        if (preview.isAvailable) open() else preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) = open()
            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(t: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
        }
        if (getSystemService(CameraManager::class.java).cameraIdList.isEmpty()) status.text = "This R1 reports no camera."
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == CAMERA_PERMISSION && results.firstOrNull() != PackageManager.PERMISSION_GRANTED) { showToast("Camera access is needed to show the agent a photo."); finish() }
    }

    override fun onPause() {
        session = null
        camera?.close(); camera = null
        stills?.close(); stills = null
        super.onPause()
    }

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onDraft(text: String) { note = text; snap() }

    @SuppressLint("MissingPermission")
    private fun open() {
        if (camera != null) return
        val manager = getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            ?: manager.cameraIdList.firstOrNull() ?: return
        val traits = manager.getCameraCharacteristics(id)
        orientation = traits.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val sizes = traits.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG).orEmpty()
        // Big enough to read a screen or a label, small enough to stay well under the bridge's 5 MB.
        val size = sizes.filter { it.width <= 1920 && it.height <= 1440 }.maxByOrNull { it.width * it.height } ?: sizes.minByOrNull { it.width * it.height } ?: return
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1).also { stills = it }
        reader.setOnImageAvailableListener({ r ->
            val jpeg = r.acquireLatestImage()?.use { img -> img.planes[0].buffer.let { b -> ByteArray(b.remaining()).also(b::get) } } ?: return@setOnImageAvailableListener
            runOnUiThread { send(jpeg) }
        }, handler)
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                val texture = preview.surfaceTexture ?: return
                texture.setDefaultBufferSize(1280, 960)
                val view = Surface(texture)
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(view, reader.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(view); set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }.build()
                        runCatching { s.setRepeatingRequest(request, null, handler) }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) = fail()
                }, handler)
            }
            override fun onDisconnected(device: CameraDevice) { device.close(); camera = null }
            override fun onError(device: CameraDevice, error: Int) { device.close(); camera = null; fail() }
        }, handler)
    }

    private fun snap() {
        val s = session ?: return showToast("The camera isn't ready yet")
        val reader = stills ?: return
        if (busy) return
        busy = true
        status.text = "Snapping…"
        runCatching {
            s.capture(s.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_ORIENTATION, orientation)
                set(CaptureRequest.JPEG_QUALITY, 85.toByte())
            }.build(), null, handler)
        }.onFailure { busy = false; status.text = ""; showToast("The camera couldn't take the photo") }
    }

    private fun send(jpeg: ByteArray) {
        val a = agent ?: return finish()
        status.text = "Sending to ${who(a)}…"
        io({ it.attach(a.sessionId, jpeg, note, a.generation) }) { r ->
            busy = false
            if (r.status != ReceiptStatus.ACCEPTED) { status.text = ""; return@io showToast(receiptText(r)) }
            r.result?.optString("path")?.takeIf(String::isNotBlank)?.let { runCatching { localCopy(this, it).apply { parentFile?.mkdirs() }.writeBytes(jpeg) } }
            finish()
            open(ThreadActivity::class.java, a.sessionId)
        }
    }

    private fun fail() = runOnUiThread { showToast("The camera couldn't start"); finish() }

    companion object {
        private const val CAMERA_PERMISSION = 7302

        /** Where this R1 keeps its own copy of a photo it sent, so the thread can show it. */
        fun localCopy(context: Context, hostPath: String) = File(context.cacheDir, "herdr-photos/${hostPath.substringAfterLast('/')}")
    }
}
