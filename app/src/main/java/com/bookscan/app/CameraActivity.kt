package com.bookscan.app

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Live scanner: shows the detected paper, captures automatically once it is held still. */
class CameraActivity : Activity() {

    companion object {
        const val EXTRA_DOC = "doc"
        const val EXTRA_OPEN_DOC = "openDoc"
        private const val REQ_CAMERA = 7
        private const val STILL_MS = 750L        // must be held still this long before auto capture
        private const val MOVE_LIMIT = 0.012f    // corner movement (fraction of frame) that still counts as "still"
        private const val CHANGE_LIMIT = 13.0    // image change that means "new page"
    }

    private lateinit var doc: Doc
    private var openDocOnDone = false

    // views
    private lateinit var texture: TextureView
    private lateinit var overlay: OverlayView
    private lateinit var flashView: View
    private lateinit var status: TextView
    private lateinit var thumb: ImageView
    private lateinit var badge: TextView
    private lateinit var autoBtn: TextView
    private lateinit var modeBtn: TextView
    private lateinit var torchBtn: TextView
    private lateinit var filterBtn: TextView
    private lateinit var shutter: View

    // camera
    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private var anaThread: HandlerThread? = null
    private var anaHandler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var yuvReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewSize = Size(1440, 1080)
    private var sensorOrientation = 90
    private var focalPx = 0.0
    private var hasTorch = false
    private var opening = false
    private var resumed = false

    // state shared with the analysis thread
    @Volatile private var rel = 90
    @Volatile private var displayDeg = 0
    @Volatile private var autoOn = true
    @Volatile private var twoPage = false
    @Volatile private var rtl = false
    @Volatile private var torch = false
    @Volatile private var filter = Filters.PRINT_BW
    @Volatile private var capturing = false
    @Volatile private var lastQuad: FloatArray? = null
    @Volatile private var lastSig: ByteArray? = null
    private var prevQuad: FloatArray? = null
    private var stillSince = 0L
    private var missCount = 0
    private var armed = true
    private var captureSig: ByteArray? = null
    private var yBytes = ByteArray(0)
    private val pending = AtomicInteger(0)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) { updateTransform() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val d = Docs.load(this, intent.getStringExtra(EXTRA_DOC) ?: "")
        if (d == null) { finish(); return }
        doc = d
        openDocOnDone = intent.getBooleanExtra(EXTRA_OPEN_DOC, false)
        autoOn = Settings.autoCapture(this)
        twoPage = Settings.twoPage(this)
        rtl = Settings.rightToLeft(this)
        filter = Settings.filter(this)
        buildUi()
        refreshButtons()
        refreshThumb()
    }

    // ------------------------------------------------------------------ UI

    private fun chip(text: String, onClick: (View) -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(0xFFFFFFFF.toInt())
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = rippled(darkChip(this@CameraActivity), 0x33FFFFFF)
        isClickable = true
        setOnClickListener(onClick)
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        texture = TextureView(this)
        overlay = OverlayView(this)
        flashView = View(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()); alpha = 0f }
        root.addView(texture, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(flashView, FrameLayout.LayoutParams(MATCH, MATCH))

        val ui = FrameLayout(this)
        ui.padForSystemBars(dp(12), dp(10), dp(12), dp(12))
        root.addView(ui, FrameLayout.LayoutParams(MATCH, MATCH))

        // top bar
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(chip("✕") { finishScanning() })
        top.addView(View(this), lp(0, 1, 1f))
        torchBtn = chip("") { torch = !torch; refreshButtons(); startRepeating() }
        modeBtn = chip("") { pickMode() }
        filterBtn = chip("") { pickFilter() }
        autoBtn = chip("") { autoOn = !autoOn; Settings.setAutoCapture(this, autoOn); refreshButtons() }
        listOf(torchBtn, modeBtn, filterBtn, autoBtn).forEach {
            top.addView(it, lp(WRAP, WRAP).apply { leftMargin = dp(8) })
        }
        ui.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = darkChip(this@CameraActivity)
            text = "문서를 화면에 비춰 주세요"
        }
        ui.addView(status, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(62) })

        // bottom bar
        val bottom = FrameLayout(this)
        val thumbBox = FrameLayout(this).apply {
            background = roundRect(0xFF2A2D33.toInt(), dpf(12f), 0xFFFFFFFF.toInt(), dp(2))
            setPadding(dp(2), dp(2), dp(2), dp(2))
            isClickable = true
            setOnClickListener { finishScanning() }
        }
        thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true }
        thumbBox.addView(thumb, FrameLayout.LayoutParams(MATCH, MATCH))
        badge = TextView(this).apply {
            textSize = 12f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            background = roundRect(C.ACCENT, dpf(100f)); setPadding(dp(7), dp(2), dp(7), dp(2))
        }
        thumbBox.addView(badge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END))
        bottom.addView(thumbBox, FrameLayout.LayoutParams(dp(64), dp(84), Gravity.START or Gravity.CENTER_VERTICAL))

        shutter = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0xFFFFFFFF.toInt()); setStroke(dp(5), 0x66FFFFFF)
            }
            isClickable = true
            setOnClickListener { takePicture(manual = true) }
        }
        bottom.addView(shutter, FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER))
        bottom.addView(pill("완료", C.ACCENT, 0xFFFFFFFF.toInt(), 16f) { finishScanning() },
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL))
        ui.addView(bottom, FrameLayout.LayoutParams(MATCH, dp(96), Gravity.BOTTOM))

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { maybeOpen() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { updateTransform() }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        setContentView(root)
    }

    private fun refreshButtons() {
        torchBtn.text = if (torch) "조명 켜짐" else "조명"
        torchBtn.visibility = if (hasTorch || device == null) View.VISIBLE else View.GONE
        modeBtn.text = if (!twoPage) "한 쪽" else if (rtl) "두 쪽 ←" else "두 쪽 →"
        filterBtn.text = Filters.name(filter)
        autoBtn.text = if (autoOn) "자동 촬영 켜짐" else "자동 촬영 꺼짐"
        autoBtn.background = rippled(
            if (autoOn) roundRect(C.ACCENT, dpf(100f)) else darkChip(this), 0x33FFFFFF
        )
        overlay.twoPage = twoPage
    }

    private fun pickMode() {
        val items = arrayOf("한 쪽씩 (문서·낱장)", "두 쪽 펼침 — 왼쪽 쪽 먼저", "두 쪽 펼침 — 오른쪽 쪽 먼저")
        val cur = if (!twoPage) 0 else if (rtl) 2 else 1
        dialogBuilder().setTitle("촬영 방식").setSingleChoiceItems(items, cur) { dlg, which ->
            twoPage = which > 0; rtl = which == 2
            Settings.setTwoPage(this, twoPage); Settings.setRightToLeft(this, rtl)
            refreshButtons(); dlg.dismiss()
        }.show()
    }

    private fun pickFilter() {
        val items = Filters.all.map { Filters.name(it) + "\n" + Filters.hint(it) }.toTypedArray()
        dialogBuilder().setTitle("촬영 후 보정").setSingleChoiceItems(items, Filters.all.indexOf(filter)) { dlg, which ->
            filter = Filters.all[which]; Settings.setFilter(this, filter); refreshButtons(); dlg.dismiss()
        }.show()
    }

    private fun refreshThumb() {
        val n = doc.pages.size
        badge.text = n.toString()
        badge.visibility = if (n > 0) View.VISIBLE else View.GONE
        doc.pages.lastOrNull()?.let { Thumbs.load(thumb, doc.thumbFile(it), 240) } ?: thumb.setImageDrawable(null)
    }

    private fun setStatus(s: String) { if (status.text != s) status.text = s }

    private var finishing = false

    private fun finishScanning() {
        if (finishing) return
        finishing = true
        val id = doc.id
        Bg.io.execute {
            // wait until every photo has been processed (they run on this same thread)
            Bg.ui {
                val d = Docs.load(this, id)
                if (d != null && d.pages.isEmpty() && openDocOnDone) {
                    Docs.delete(d)
                } else if (d != null && openDocOnDone) {
                    startActivity(Intent(this, DocActivity::class.java).putExtra(DocActivity.EXTRA_DOC, id))
                }
                finish()
            }
        }
        if (pending.get() > 0) setStatus("저장 중…")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { finishScanning() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event?.repeatCount == 0) takePicture(manual = true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        resumed = true
        camThread = HandlerThread("cam").also { it.start(); camHandler = Handler(it.looper) }
        anaThread = HandlerThread("analysis").also { it.start(); anaHandler = Handler(it.looper) }
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, Bg.main)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        } else maybeOpen()
    }

    override fun onPause() {
        resumed = false
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        closeCamera()
        camThread?.quitSafely(); anaThread?.quitSafely()
        camThread = null; anaThread = null; camHandler = null; anaHandler = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) maybeOpen()
            else { toast("카메라 권한이 있어야 스캔할 수 있어요"); finish() }
        }
    }

    // ------------------------------------------------------------------ camera

    private fun pickSize(sizes: Array<Size>, aspect: Double, maxW: Int, maxH: Int): Size {
        val fit = sizes.filter { abs(it.width.toDouble() / it.height - aspect) < 0.02 && it.width <= maxW && it.height <= maxH }
        return fit.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.filter { it.width <= maxW && it.height <= maxH }.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }!!
    }

    private fun maybeOpen() {
        if (!resumed || opening || device != null || !texture.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val id = mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: mgr.cameraIdList.firstOrNull() ?: run { toast("카메라를 찾을 수 없어요"); return }
            val ch = mgr.getCameraCharacteristics(id)
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            hasTorch = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val jpegs = map.getOutputSizes(ImageFormat.JPEG)
            val four3 = jpegs.filter { abs(it.width.toDouble() / it.height - 4.0 / 3) < 0.02 }
            val jpeg = (four3.ifEmpty { jpegs.toList() }).maxByOrNull { it.width.toLong() * it.height }!!
            val aspect = jpeg.width.toDouble() / jpeg.height
            previewSize = pickSize(map.getOutputSizes(SurfaceTexture::class.java), aspect, 1920, 1440)
            val yuv = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888), aspect, 800, 600)

            val fl = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            focalPx = if (fl != null && phys != null && phys.width > 0) fl.toDouble() / phys.width * max(jpeg.width, jpeg.height) else 0.0

            jpegReader = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2).also { r ->
                r.setOnImageAvailableListener({ onJpeg(it) }, camHandler)
            }
            yuvReader = ImageReader.newInstance(yuv.width, yuv.height, ImageFormat.YUV_420_888, 2).also { r ->
                r.setOnImageAvailableListener({ onFrame(it) }, anaHandler)
            }
            opening = true
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    opening = false
                    if (!resumed) { cam.close(); return }
                    device = cam
                    Bg.ui { refreshButtons(); updateTransform() }
                    createSession()
                }
                override fun onDisconnected(cam: CameraDevice) { opening = false; cam.close(); device = null }
                override fun onError(cam: CameraDevice, error: Int) {
                    opening = false; cam.close(); device = null
                    Bg.ui { toast("카메라 오류 ($error)") }
                }
            }, camHandler)
        } catch (e: SecurityException) {
            opening = false; toast("카메라 권한이 필요해요")
        } catch (e: Exception) {
            opening = false; toast("카메라를 열 수 없어요: ${e.message}")
        }
    }

    private fun createSession() {
        val cam = device ?: return
        val st = texture.surfaceTexture ?: return
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val ps = Surface(st)
        previewSurface = ps
        val outs = listOf(ps, yuvReader!!.surface, jpegReader!!.surface)
        try {
            @Suppress("DEPRECATION")
            cam.createCaptureSession(outs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (device == null) { s.close(); return }
                    session = s
                    startRepeating()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { Bg.ui { toast("카메라 설정에 실패했어요") } }
            }, camHandler)
        } catch (e: Exception) {
            Bg.ui { toast("카메라 시작 실패: ${e.message}") }
        }
    }

    private fun startRepeating() {
        val s = session ?: return; val cam = device ?: return
        val ps = previewSurface ?: return; val yr = yuvReader ?: return
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps); b.addTarget(yr.surface)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            s.setRepeatingRequest(b.build(), null, camHandler)
        } catch (e: Exception) { /* session closed */ }
    }

    private fun closeCamera() {
        try { session?.close() } catch (e: Exception) {}
        session = null
        try { device?.close() } catch (e: Exception) {}
        device = null
        jpegReader?.close(); jpegReader = null
        yuvReader?.close(); yuvReader = null
        previewSurface?.release(); previewSurface = null
        opening = false
        capturing = false
    }

    /** Fits the preview inside the view (letterboxed) and keeps it upright for any screen rotation. */
    private fun updateTransform() {
        val vw = texture.width.toFloat(); val vh = texture.height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val r = when (display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
        }
        displayDeg = r
        rel = (sensorOrientation - r + 360) % 360
        val natW: Float; val natH: Float
        if (sensorOrientation % 180 == 90) { natW = previewSize.height.toFloat(); natH = previewSize.width.toFloat() }
        else { natW = previewSize.width.toFloat(); natH = previewSize.height.toFloat() }
        val dw = if (r % 180 == 0) natW else natH
        val dh = if (r % 180 == 0) natH else natW
        val s = min(vw / dw, vh / dh)
        val cx = vw / 2; val cy = vh / 2
        val m = Matrix()
        m.postScale(natW / vw, natH / vh, cx, cy)
        m.postRotate(-r.toFloat(), cx, cy)
        m.postScale(s, s, cx, cy)
        texture.setTransform(m)
        overlay.content.set(cx - dw * s / 2, cy - dh * s / 2, cx + dw * s / 2, cy + dh * s / 2)
        overlay.invalidate()
    }

    // ------------------------------------------------------------------ analysis

    private fun rotateNorm(q: FloatArray, deg: Int, fw: Int, fh: Int): FloatArray {
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val x = q[2 * i]; val y = q[2 * i + 1]
            val (nx, ny) = when (deg) { 90 -> (1 - y) to x; 180 -> (1 - x) to (1 - y); 270 -> y to (1 - x); else -> x to y }
            out[2 * i] = nx; out[2 * i + 1] = ny
        }
        // re-order corners in upright pixel space
        val uw = if (deg % 180 == 0) fw else fh; val uh = if (deg % 180 == 0) fh else fw
        val px = FloatArray(8) { if (it % 2 == 0) out[it] * uw else out[it] * uh }
        val o = Vision.order(px)
        return FloatArray(8) { if (it % 2 == 0) o[it] / uw else o[it] / uh }
    }

    private fun onFrame(reader: ImageReader) {
        val img = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        val w = img.width; val h = img.height
        try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val rs = plane.rowStride
            if (yBytes.size != w * h) yBytes = ByteArray(w * h)
            if (rs == w) buf.get(yBytes, 0, w * h)
            else for (row in 0 until h) { buf.position(row * rs); buf.get(yBytes, row * w, w) }
        } catch (e: Exception) {
            img.close(); return
        }
        img.close()

        val y = Mat(h, w, CvType.CV_8UC1)
        y.put(0, 0, yBytes)
        val small: Mat
        if (max(w, h) > Vision.DETECT_SIDE) {
            val s = Vision.DETECT_SIDE.toDouble() / max(w, h)
            small = Mat()
            Imgproc.resize(y, small, org.opencv.core.Size(w * s, h * s), 0.0, 0.0, Imgproc.INTER_AREA)
            y.release()
        } else small = y
        val sw = small.cols(); val sh = small.rows()
        val raw = try { Vision.detectQuad(small) } catch (e: Exception) { null }
        val sigMat = Mat()
        Imgproc.resize(small, sigMat, org.opencv.core.Size(32.0, 24.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val sig = ByteArray(32 * 24)
        sigMat.get(0, 0, sig)
        sigMat.release(); small.release()
        lastSig = sig

        val relNow = rel
        val q = raw?.let { r -> rotateNorm(FloatArray(8) { if (it % 2 == 0) r[it] / sw else r[it] / sh }, relNow, sw, sh) }
        lastQuad = q
        val now = System.currentTimeMillis()

        if (!armed) {
            val cs = captureSig
            if (q == null && missCount >= 3) armed = true
            if (cs != null && sigDiff(cs, sig) > CHANGE_LIMIT) armed = true
        }

        var progress = 0f
        val msg: String
        if (q == null) {
            missCount++
            prevQuad = null; stillSince = now
            msg = if (!armed) "다음 페이지를 준비하세요" else "문서를 화면에 비춰 주세요"
        } else {
            missCount = 0
            val p = prevQuad
            var move = 1f
            if (p != null) { move = 0f; for (i in 0 until 4) move = max(move, hypot(q[2 * i] - p[2 * i], q[2 * i + 1] - p[2 * i + 1])) }
            prevQuad = q
            if (move > MOVE_LIMIT) stillSince = now
            if (autoOn && armed) {
                progress = ((now - stillSince).toFloat() / STILL_MS).coerceIn(0f, 1f)
                msg = if (progress < 1f) "그대로 잡고 있으세요…" else "촬영!"
                if (progress >= 1f && !capturing) Bg.ui { takePicture(manual = false) }
            } else if (autoOn) {
                msg = "촬영됨 — 페이지를 넘겨 주세요"
            } else msg = "촬영 버튼(또는 볼륨 키)을 누르세요"
        }
        val pendingNow = pending.get()
        Bg.ui {
            overlay.update(q, progress)
            setStatus(if (pendingNow > 0 && q == null) "저장 중… ($pendingNow)" else msg)
        }
    }

    private fun sigDiff(a: ByteArray, b: ByteArray): Double {
        var s = 0L
        for (i in a.indices) s += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
        return s.toDouble() / a.size
    }

    // ------------------------------------------------------------------ capture

    private var liveAtCapture: FloatArray? = null
    private var relAtCapture = 0

    private fun takePicture(manual: Boolean) {
        val s = session; val cam = device; val jr = jpegReader
        if (s == null || cam == null || jr == null || capturing) return
        capturing = true
        liveAtCapture = lastQuad?.copyOf()
        relAtCapture = rel
        val sig = lastSig
        anaHandler?.post { armed = false; captureSig = sig; stillSince = System.currentTimeMillis() }
        ObjectAnimator.ofFloat(flashView, "alpha", 0.7f, 0f).setDuration(260).start()
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(jr.surface)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    capturing = false
                }
            }, camHandler)
        } catch (e: Exception) {
            capturing = false
        }
        if (manual) shutter.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
            shutter.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
    }

    private fun onJpeg(reader: ImageReader) {
        val img = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        val bytes: ByteArray
        try {
            val b = img.planes[0].buffer
            bytes = ByteArray(b.remaining())
            b.get(bytes)
        } finally {
            img.close()
        }
        capturing = false
        val live = liveAtCapture
        val r = relAtCapture
        val f = focalPx
        val two = twoPage; val order = rtl; val fl = filter
        pending.incrementAndGet()
        Bg.io.execute {
            try {
                val buf = MatOfByte(*bytes)
                val m = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION)
                buf.release()
                if (!m.empty()) {
                    val up = Vision.rotate(m, r)
                    if (up !== m) m.release()
                    Vision.addPhoto(doc, up, live, f, two, order, fl)
                    up.release()
                }
            } catch (e: Throwable) {
                Bg.ui { toast("사진 처리 실패: ${e.message}") }
            } finally {
                pending.decrementAndGet()
                Bg.ui { refreshThumb() }
            }
        }
    }

    // ------------------------------------------------------------------ overlay

    class OverlayView(ctx: Context) : View(ctx) {
        val content = RectF()
        var twoPage = false
        private var quad: FloatArray? = null
        private var progress = 0f
        private var missFrames = 0
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = ctx.dpf(3f); strokeJoin = Paint.Join.ROUND
        }
        private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = ctx.dpf(2f); color = 0xCCFFFFFF.toInt()
            pathEffect = DashPathEffect(floatArrayOf(ctx.dpf(8f), ctx.dpf(6f)), 0f)
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt() }
        private val path = Path()

        fun update(q: FloatArray?, p: Float) {
            if (q == null) {
                missFrames++
                if (missFrames > 2) quad = null
            } else {
                missFrames = 0
                val old = quad
                quad = if (old == null) q.copyOf() else FloatArray(8) { old[it] * 0.45f + q[it] * 0.55f }
            }
            progress = p
            invalidate()
        }

        private fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt() shl s
            return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        }

        override fun onDraw(canvas: Canvas) {
            val q = quad ?: return
            if (content.width() <= 0) return
            fun x(i: Int) = content.left + q[2 * i] * content.width()
            fun y(i: Int) = content.top + q[2 * i + 1] * content.height()
            val col = blend(C.ACCENT, C.GOOD, progress)
            path.reset()
            path.moveTo(x(0), y(0)); path.lineTo(x(1), y(1)); path.lineTo(x(2), y(2)); path.lineTo(x(3), y(3)); path.close()
            fill.color = colorWithAlpha(col, (50 + 70 * progress).toInt())
            stroke.color = col
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
            if (twoPage) {
                canvas.drawLine((x(0) + x(1)) / 2, (y(0) + y(1)) / 2, (x(3) + x(2)) / 2, (y(3) + y(2)) / 2, dash)
            }
            val r = context.dpf(5f)
            for (i in 0 until 4) canvas.drawCircle(x(i), y(i), r, dot)
        }
    }
}
