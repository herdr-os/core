package dev.r1ptt.herdr.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.LinearLayout
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import dev.r1ptt.R
import dev.r1ptt.herdr.PairingLink
import dev.r1ptt.ui.DeviceActivity

/**
 * Reads the QR code `herdr-bridge enroll --qr` prints and returns its [PairingLink]. It only
 * reads; the caller confirms the bridge address before anything is claimed.
 */
class QrScanActivity : DeviceActivity() {
    private val thread = HandlerThread("qr").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)
    private lateinit var preview: TextureView
    private var camera: CameraDevice? = null
    private var frames: ImageReader? = null
    @Volatile private var done = false
    private var warned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = TextureView(this)
        val d = Design(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            setPadding(d.px(22), d.px(22), d.px(22), d.px(18))
        }
        root.addView(d.header("Pair a host", "POINT THE CAMERA AT THE CODE") { finish() })
        root.addView(FrameLayout(this).apply {
            background = d.fill(R.color.field, 32)
            setPadding(d.px(18), d.px(18), d.px(18), d.px(18))
            clipToOutline = true
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(Brackets(context, d.px(32).toFloat(), d.px(4).toFloat(), getColor(R.color.accent)), FrameLayout.LayoutParams(-1, -1).apply { setMargins(d.px(36), d.px(18), d.px(36), d.px(18)) })
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = d.px(16) })
        root.addView(d.text("ON THE COMPUTER RUNNING HERDR", 13, R.color.dim, 600, mono = true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = d.px(20) })
        root.addView(d.text("herdr-bridge enroll --qr --tailscale", 14, R.color.cream, mono = true).apply { letterSpacing = 0f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = d.px(6) })
        root.addView(d.pill("Type the code instead", fill = R.color.raised, ink = R.color.cream, height = 56) { setResult(RESULT_TYPE); finish() }.apply {
            setCompoundDrawablesRelative(getDrawable(R.drawable.ic_keyboard)!!.mutate().apply { setTint(getColor(R.color.cream)); setBounds(0, 0, d.px(22), d.px(22)) }, null, null, null)
            compoundDrawablePadding = d.px(10)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.px(14) })
        setContentView(root)
    }

    /** The orange corners of the viewfinder. */
    private class Brackets(context: android.content.Context, private val arm: Float, stroke: Float, color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
        override fun onDraw(c: Canvas) {
            val i = paint.strokeWidth / 2; val w = width - i; val h = height - i
            c.drawPath(Path().apply {
                moveTo(i, i + arm); lineTo(i, i); lineTo(i + arm, i)
                moveTo(w - arm, i); lineTo(w, i); lineTo(w, i + arm)
                moveTo(w, h - arm); lineTo(w, h); lineTo(w - arm, h)
                moveTo(i + arm, h); lineTo(i, h); lineTo(i, h - arm)
            }, paint)
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
            return
        }
        if (preview.isAvailable) open() else preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) = open()
            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(t: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == CAMERA_PERMISSION && results.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            showToast("Camera access is needed to scan. You can still type the URL and code.")
            finish()
        }
    }

    override fun onPause() {
        close()
        super.onPause()
    }

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        if (camera != null || done) return
        val manager = getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: return run { showToast("No camera found"); finish() }
        val sizes = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        // QR codes on a laptop screen decode well at VGA; bigger frames only cost CPU and battery.
        val size = sizes.filter { it.width <= 1280 && it.height <= 960 }.maxByOrNull { it.width * it.height } ?: sizes.firstOrNull() ?: Size(640, 480)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).also { frames = it }
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use { decode(it) } }, handler)
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                val texture = preview.surfaceTexture ?: return
                texture.setDefaultBufferSize(size.width, size.height)
                val view = Surface(texture)
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(view, reader.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(view); addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }.build()
                        runCatching { session.setRepeatingRequest(request, null, handler) }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) = fail()
                }, handler)
            }
            override fun onDisconnected(device: CameraDevice) { device.close(); camera = null }
            override fun onError(device: CameraDevice, error: Int) { device.close(); camera = null; fail() }
        }, handler)
    }

    private fun decode(image: android.media.Image) {
        if (done) return
        val plane = image.planes[0]
        val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
        val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
        val text = runCatching { reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text }.getOrNull() ?: return
        reader.reset()
        val link = PairingLink.parse(text)
        if (link == null) {
            if (!warned) { warned = true; runOnUiThread { showToast("That QR code isn't a Herdr pairing code") } }
            return
        }
        done = true
        runOnUiThread {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_LINK, text))
            finish()
        }
    }

    private fun fail() = runOnUiThread { showToast("The camera couldn't start"); finish() }

    private fun close() {
        camera?.close(); camera = null
        frames?.close(); frames = null
    }

    companion object {
        const val EXTRA_LINK = "herdr.pair.link"
        /** The user would rather type the bridge address and code. */
        const val RESULT_TYPE = RESULT_FIRST_USER
        private const val CAMERA_PERMISSION = 7301
    }
}
