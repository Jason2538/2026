package com.bookscan.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Magnifier
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.min

/** Adjust the crop corners, rotation and enhancement of one page. */
class EditActivity : Activity() {
    companion object {
        const val EXTRA_DOC = "doc"
        const val EXTRA_PAGE = "page"
    }

    private lateinit var doc: Doc
    private lateinit var page: Page
    private var index = 0

    // working copy
    private lateinit var work: Page

    private lateinit var crop: CropView
    private lateinit var result: ImageView
    private lateinit var tabCrop: TextView
    private lateinit var tabLook: TextView
    private lateinit var cropTools: LinearLayout
    private lateinit var lookTools: LinearLayout
    private lateinit var info: TextView
    private lateinit var snapBtn: TextView
    private lateinit var hint: TextView
    private lateinit var strengthRow: LinearLayout
    private lateinit var seek: SeekBar
    private val filterChips = HashMap<String, TextView>()
    private var origW = 1
    private var origH = 1
    private var previewSeq = 0
    private var lookMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = Docs.load(this, intent.getStringExtra(EXTRA_DOC) ?: "")
        index = intent.getIntExtra(EXTRA_PAGE, 0)
        val p = d?.pages?.getOrNull(index)
        if (d == null || p == null) { finish(); return }
        doc = d; page = p
        work = Page(p.id, p.orig, p.quad?.copyOf(), p.filter, p.rot, p.strength, p.focal, p.snap, p.paper)
        buildUi()
        loadOriginal()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.DARK) }
        root.padForSystemBars()

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(14), dp(8))
        }
        top.addView(pill("←", 0, 0xFFFFFFFF.toInt(), 20f) { finish() })
        top.addView(label("${index + 1} / ${doc.pages.size}쪽", 17f, 0xFFFFFFFF.toInt(), true), lp(0, WRAP, 1f))
        tabCrop = pill("자르기", 0, 0xFFFFFFFF.toInt(), 15f) { setMode(false) }
        tabLook = pill("보정", 0, 0xFFFFFFFF.toInt(), 15f) { setMode(true) }
        top.addView(tabCrop); top.addView(tabLook, lp(WRAP, WRAP).apply { rightMargin = dp(16) })
        top.addView(pill("저장", C.ACCENT, 0xFFFFFFFF.toInt(), 15f) { save() })
        root.addView(top)

        val stage = FrameLayout(this)
        crop = CropView(this)
        result = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(20), dp(20), dp(20), dp(20)) }
        stage.addView(crop, FrameLayout.LayoutParams(MATCH, MATCH))
        stage.addView(result, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(stage, lp(MATCH, 0, 1f))

        info = label("", 13f, 0xFFB8BEC9.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(4)) }
        root.addView(info, lp(MATCH, WRAP))

        // crop tools
        cropTools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(14)) }
        fun tool(sym: String, cap: String, act: () -> Unit) = toolButton(sym, cap, 0xFFFFFFFF.toInt(), 0xFF23262D.toInt()) { act() }
            .also { cropTools.addView(it, lp(dp(96), WRAP).apply { leftMargin = dp(6); rightMargin = dp(6) }) }
        tool("◎", "자동 감지") { autoDetect() }
        tool("▢", "전체 선택") { crop.setQuad(Vision.fullQuad(origW, origH)); updateInfo() }
        tool("↻", "회전") { work.rot = (work.rot + 90) % 360; updateInfo(); if (lookMode) requestPreview() }
        snapBtn = tool("▭", "") { work.snap = !work.snap; updateInfo() }.getChildAt(1) as TextView
        root.addView(cropTools)

        // look tools
        lookTools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(14)) }
        val chipsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (f in Filters.all) {
            val c = pill(Filters.name(f), 0xFF23262D.toInt(), 0xFFFFFFFF.toInt(), 14f) {
                work.filter = f; refreshLook(); requestPreview()
            }
            filterChips[f] = c
            chips.addView(c, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        chipsScroll.addView(chips)
        lookTools.addView(chipsScroll, lp(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
        hint = label("", 13f, 0xFFB8BEC9.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4)) }
        lookTools.addView(hint, lp(MATCH, WRAP))
        strengthRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        strengthRow.addView(label("연하게", 13f, 0xFFB8BEC9.toInt()))
        seek = SeekBar(this).apply {
            max = 100; progress = work.strength
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { if (fromUser) work.strength = v }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { requestPreview() }
            })
        }
        strengthRow.addView(seek, lp(0, WRAP, 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        strengthRow.addView(label("진하게", 13f, 0xFFB8BEC9.toInt()))
        lookTools.addView(strengthRow, lp(MATCH, WRAP).apply { leftMargin = dp(40); rightMargin = dp(40) })
        root.addView(lookTools)

        crop.onChange = { updateInfo() }
        setContentView(root)
        darkSystemBars() // needs the window decor, so only after setContentView
        setMode(false)
    }

    private fun setMode(look: Boolean) {
        lookMode = look
        crop.visibility = if (look) View.GONE else View.VISIBLE
        result.visibility = if (look) View.VISIBLE else View.GONE
        cropTools.visibility = if (look) View.GONE else View.VISIBLE
        lookTools.visibility = if (look) View.VISIBLE else View.GONE
        tabCrop.background = rippled(roundRect(if (!look) 0xFF353944.toInt() else 0, dpf(100f)))
        tabLook.background = rippled(roundRect(if (look) 0xFF353944.toInt() else 0, dpf(100f)))
        if (look) { refreshLook(); requestPreview() }
        updateInfo()
    }

    private fun refreshLook() {
        for ((f, c) in filterChips) c.background = rippled(roundRect(if (f == work.filter) C.ACCENT else 0xFF23262D.toInt(), dpf(100f)))
        hint.text = Filters.hint(work.filter)
        strengthRow.visibility = if (Filters.hasStrength(work.filter)) View.VISIBLE else View.INVISIBLE
    }

    private var imageReady = false

    private fun currentQuad(): FloatArray? = crop.quad?.copyOf() ?: work.quad?.copyOf()

    private fun updateInfo() {
        if (!imageReady) { info.text = "원본 사진 여는 중…"; return }
        work.quad = currentQuad() ?: Vision.fullQuad(origW, origH)
        val sz = Vision.outputSize(work.quad!!, origW, origH, work.focal, work.snap)
        val w = if (work.rot % 180 == 0) sz.w else sz.h
        val h = if (work.rot % 180 == 0) sz.h else sz.w
        snapBtn.text = if (work.snap) "비율 맞춤 켜짐" else "비율 맞춤 꺼짐"
        info.text = buildString {
            append("결과 ${w}×${h}px")
            sz.paper?.let { append(" · $it") }
            if (work.rot != 0) append(" · ${work.rot}° 회전")
            if (!lookMode) append(" · 모서리를 끌어 맞추세요")
        }
    }

    private fun loadOriginal() {
        val f = doc.origFile(page)
        Bg.work.execute {
            val (w, h) = imageSize(f)
            val bmp = decodeSampled(f, 2000)
            Bg.ui {
                if (bmp == null || w <= 0) { toast("원본 사진을 열 수 없어요"); return@ui }
                origW = w; origH = h
                imageReady = true
                crop.setImage(bmp, w.toFloat() / bmp.width)
                crop.setQuad(work.quad ?: Vision.fullQuad(w, h))
                updateInfo()
            }
        }
    }

    private fun autoDetect() {
        val f = doc.origFile(page)
        Bg.work.execute {
            val m = Vision.readImage(f.absolutePath)
            val q = if (m.empty()) null else Vision.detectOnImage(m)
            m.release()
            Bg.ui {
                if (q == null) toast("종이 테두리를 찾지 못했어요. 모서리를 직접 맞춰 주세요")
                else { crop.setQuad(q); updateInfo() }
            }
        }
    }

    private fun requestPreview() {
        if (!imageReady) return
        work.quad = currentQuad() ?: Vision.fullQuad(origW, origH)
        val snapshot = Page(work.id, work.orig, work.quad?.copyOf(), work.filter, work.rot, work.strength, work.focal, work.snap)
        val seq = ++previewSeq
        Bg.work.execute {
            if (seq != previewSeq) return@execute
            val bmp = try { Vision.renderPreview(doc, snapshot, 1600) } catch (e: Throwable) { null }
            Bg.ui { if (seq == previewSeq && bmp != null) result.setImageBitmap(bmp) }
        }
    }

    private fun save() {
        if (!imageReady) { finish(); return }
        work.quad = currentQuad() ?: Vision.fullQuad(origW, origH)
        val p = Progress(this, "저장 중")
        Bg.io.execute {
            page.quad = work.quad; page.filter = work.filter; page.rot = work.rot
            page.strength = work.strength; page.snap = work.snap
            try { Vision.render(doc, page) } catch (e: Throwable) {}
            Docs.save(doc)
            p.dismiss()
            Bg.ui { finish() }
        }
    }

    // ------------------------------------------------------------------ crop view

    class CropView(ctx: Context) : View(ctx) {
        var quad: FloatArray? = null; private set
        var onChange: (() -> Unit)? = null
        private var bmp: Bitmap? = null
        private var origPerBmp = 1f
        private var s = 1f
        private var ox = 0f
        private var oy = 0f
        private val pad = ctx.dpf(30f)
        private val imgRect = RectF()
        private val shade = Paint().apply { color = 0x99000000.toInt() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(2.5f); color = C.ACCENT }
        private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(3f); color = C.ACCENT }
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val path = Path()
        private var drag = -1          // 0..3 corners, 4..7 edges (edge i = corners i and i+1)
        private var lastX = 0f
        private var lastY = 0f
        private val magnifier: Magnifier = Magnifier.Builder(this)
            .setSize(ctx.dp(120), ctx.dp(120)).setInitialZoom(2.5f).setCornerRadius(ctx.dpf(60f)).build()

        fun setImage(b: Bitmap, scale: Float) { bmp = b; origPerBmp = scale; layoutImage(); invalidate() }
        fun setQuad(q: FloatArray) { quad = q.copyOf(); invalidate(); onChange?.invoke() }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { layoutImage() }

        private fun layoutImage() {
            val b = bmp ?: return
            if (width == 0) return
            val bw = b.width * origPerBmp; val bh = b.height * origPerBmp   // original pixels
            s = min((width - 2 * pad) / bw, (height - 2 * pad) / bh)
            ox = (width - bw * s) / 2; oy = (height - bh * s) / 2
            imgRect.set(ox, oy, ox + bw * s, oy + bh * s)
        }

        private fun vx(x: Float) = ox + x * s
        private fun vy(y: Float) = oy + y * s

        override fun onDraw(canvas: Canvas) {
            val b = bmp ?: return
            canvas.drawBitmap(b, null, imgRect, bmpPaint)
            val q = quad ?: return
            path.reset()
            path.fillType = Path.FillType.EVEN_ODD
            path.addRect(imgRect, Path.Direction.CW)
            path.moveTo(vx(q[0]), vy(q[1]))
            for (i in 1 until 4) path.lineTo(vx(q[2 * i]), vy(q[2 * i + 1]))
            path.close()
            canvas.drawPath(path, shade)
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                canvas.drawLine(vx(q[2 * i]), vy(q[2 * i + 1]), vx(q[2 * j]), vy(q[2 * j + 1]), line)
            }
            val r = context.dpf(11f)
            for (i in 0 until 4) {
                canvas.drawCircle(vx(q[2 * i]), vy(q[2 * i + 1]), r, handleFill)
                canvas.drawCircle(vx(q[2 * i]), vy(q[2 * i + 1]), r, handleRing)
                val j = (i + 1) % 4
                val mx = (vx(q[2 * i]) + vx(q[2 * j])) / 2; val my = (vy(q[2 * i + 1]) + vy(q[2 * j + 1])) / 2
                canvas.drawCircle(mx, my, r * 0.6f, handleFill)
            }
        }

        private fun hit(x: Float, y: Float): Int {
            val q = quad ?: return -1
            var best = -1; var bd = context.dpf(44f)
            for (i in 0 until 4) {
                val d = hypot(vx(q[2 * i]) - x, vy(q[2 * i + 1]) - y)
                if (d < bd) { bd = d; best = i }
            }
            if (best >= 0) return best
            bd = context.dpf(36f)
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                val mx = (vx(q[2 * i]) + vx(q[2 * j])) / 2; val my = (vy(q[2 * i + 1]) + vy(q[2 * j + 1])) / 2
                val d = hypot(mx - x, my - y)
                if (d < bd) { bd = d; best = 4 + i }
            }
            return best
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val q = quad ?: return false
            val b = bmp ?: return false
            val maxX = b.width * origPerBmp - 1; val maxY = b.height * origPerBmp - 1
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    drag = hit(e.x, e.y)
                    lastX = e.x; lastY = e.y
                    if (drag < 0) return false
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (drag < 0) return false
                    val dx = (e.x - lastX) / s; val dy = (e.y - lastY) / s
                    lastX = e.x; lastY = e.y
                    val idx = if (drag < 4) intArrayOf(drag) else intArrayOf(drag - 4, (drag - 3) % 4)
                    for (i in idx) {
                        q[2 * i] = (q[2 * i] + dx).coerceIn(0f, maxX)
                        q[2 * i + 1] = (q[2 * i + 1] + dy).coerceIn(0f, maxY)
                    }
                    val mx: Float; val my: Float
                    if (drag < 4) { mx = vx(q[2 * drag]); my = vy(q[2 * drag + 1]) } else { mx = e.x; my = e.y }
                    magnifier.show(mx, my, mx, (my - context.dpf(110f)).coerceAtLeast(context.dpf(60f)))
                    invalidate()
                    onChange?.invoke()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    drag = -1
                    magnifier.dismiss()
                }
            }
            return true
        }
    }
}
