package com.bookscan.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/** Text recognition: tap a line on the page to copy it, or edit/copy the whole text. */
class OcrActivity : Activity() {
    companion object {
        const val EXTRA_DOC = "doc"
        const val EXTRA_PAGE = "page"
    }

    private lateinit var doc: Doc
    private var index = 0
    private lateinit var pageView: OcrPageView
    private lateinit var text: EditText
    private lateinit var pageLabel: TextView
    private lateinit var status: TextView
    private val results = HashMap<String, Ocr.Result>()
    private var allMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = Docs.load(this, intent.getStringExtra(EXTRA_DOC) ?: "")
        if (d == null || d.pages.isEmpty()) { finish(); return }
        doc = d
        index = intent.getIntExtra(EXTRA_PAGE, 0).coerceIn(0, d.pages.size - 1)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        root.padForSystemBars()

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(16), dp(8))
        }
        top.addView(pill("←", 0, C.TEXT, 20f) { finish() })
        top.addView(label("글자 인식", 20f, C.TEXT, true), lp(0, WRAP, 1f))
        top.addView(pill("전체 복사", C.ACCENT, 0xFFFFFFFF.toInt(), 14f) { copy(text.text.toString()) })
        top.addView(pill("TXT 저장", C.CARD, C.TEXT, 14f, C.LINE) { saveTxt() }, lp(WRAP, WRAP).apply { leftMargin = dp(8) })
        top.addView(pill("공유", C.CARD, C.TEXT, 14f, C.LINE) { shareText() }, lp(WRAP, WRAP).apply { leftMargin = dp(8) })
        root.addView(top)

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val body = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(12))
        }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pageView = OcrPageView(this).apply {
            background = roundRect(C.CARD, dpf(16f), C.LINE, dp(1))
            onLineTap = { s -> copy(s, "이 줄을 복사했어요") }
        }
        left.addView(pageView, lp(MATCH, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        nav.addView(pill("◀", C.CARD, C.TEXT, 15f, C.LINE) { go(index - 1) })
        pageLabel = label("", 15f, C.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0) }
        nav.addView(pageLabel)
        nav.addView(pill("▶", C.CARD, C.TEXT, 15f, C.LINE) { go(index + 1) })
        nav.addView(pill("모든 쪽 합치기", C.ACCENT_SOFT, C.ACCENT, 14f) { recognizeAll() }, lp(WRAP, WRAP).apply { leftMargin = dp(12) })
        left.addView(nav)

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = label("줄을 누르면 그 줄만 복사돼요. 아래 글은 고치거나 길게 눌러 원하는 부분만 복사할 수 있어요.", 13f, C.SUB)
            .apply { setPadding(dp(4), dp(8), dp(4), dp(6)) }
        right.addView(status)
        text = EditText(this).apply {
            gravity = Gravity.TOP or Gravity.START
            textSize = 16f
            setTextColor(C.TEXT)
            background = roundRect(C.CARD, dpf(16f), C.LINE, dp(1))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isVerticalScrollBarEnabled = true
        }
        right.addView(text, lp(MATCH, 0, 1f))

        if (landscape) {
            body.addView(left, lp(0, MATCH, 1f))
            body.addView(right, lp(0, MATCH, 1f).apply { leftMargin = dp(16) })
        } else {
            body.addView(left, lp(MATCH, 0, 1.1f))
            body.addView(right, lp(MATCH, 0, 1f).apply { topMargin = dp(10) })
        }
        root.addView(body, lp(MATCH, 0, 1f))
        setContentView(root)
        go(index)
    }

    private fun go(i: Int) {
        if (i !in doc.pages.indices) return
        index = i
        allMode = false
        pageLabel.text = "${i + 1} / ${doc.pages.size}"
        val p = doc.pages[i]
        pageView.set(null, null)
        text.setText("")
        status.text = "인식하는 중…"
        Bg.work.execute {
            val bmp = loadPage(p)
            Bg.ui {
                if (index != i) return@ui
                if (bmp == null) { status.text = "쪽 이미지를 열 수 없어요"; return@ui }
                if (bmp.width < 32 || bmp.height < 32) { status.text = "쪽이 너무 작아요. 자르기를 다시 맞춰 주세요"; return@ui }
                val cached = results[p.id]
                if (cached != null) show(bmp, cached)
                else Ocr.recognize(bmp) { r, e ->
                    if (r != null) results[p.id] = r
                    if (index == i && !allMode) {
                        if (r != null) show(bmp, r) else status.text = "인식 실패: ${e?.message}"
                    }
                }
            }
        }
    }

    private fun loadPage(p: Page): Bitmap? {
        val f = doc.outFile(p)
        if (!f.exists()) Vision.render(doc, p)
        return decodeSampled(f, 2400)
    }

    private fun show(bmp: Bitmap, r: Ocr.Result) {
        pageView.set(bmp, r)
        text.setText(r.text)
        status.text = if (r.lines.isEmpty()) "글자를 찾지 못했어요. ‘보정’에서 인쇄용 흑백을 써 보세요."
        else "${r.lines.size}줄 인식 · 줄을 누르면 그 줄만 복사돼요"
    }

    private fun recognizeAll() {
        allMode = true
        val pages = doc.pages.toList()
        val parts = arrayOfNulls<String>(pages.size)
        status.text = "모든 쪽 인식 중… 0 / ${pages.size}"
        fun next(i: Int) {
            if (!allMode) return
            if (i >= pages.size) {
                val sb = StringBuilder()
                parts.forEachIndexed { k, s -> if (sb.isNotEmpty()) sb.append("\n\n"); sb.append("[${k + 1}쪽]\n").append(s ?: "") }
                text.setText(sb.toString())
                status.text = "모든 쪽(${pages.size}쪽) 글자를 합쳤어요"
                return
            }
            status.text = "모든 쪽 인식 중… ${i + 1} / ${pages.size}"
            val p = pages[i]
            val cached = results[p.id]
            if (cached != null) { parts[i] = cached.text; next(i + 1); return }
            Bg.work.execute {
                val bmp = loadPage(p)
                Bg.ui {
                    if (bmp == null || bmp.width < 32 || bmp.height < 32) { next(i + 1); return@ui }
                    Ocr.recognize(bmp) { r, _ ->
                        if (r != null) { results[p.id] = r; parts[i] = r.text }
                        next(i + 1)
                    }
                }
            }
        }
        next(0)
    }

    private fun copy(s: String, msg: String = "복사했어요") {
        if (s.isBlank()) { toast("복사할 글자가 없어요"); return }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("스캔 글자", s))
        toast(msg)
    }

    private fun saveTxt() {
        val t = text.text.toString()
        if (t.isBlank()) { toast("저장할 글자가 없어요"); return }
        Bg.work.execute {
            try {
                val (_, where) = Export.saveText(this, doc.name + if (allMode) "" else "_${index + 1}쪽", t)
                Bg.ui { toast("저장했어요: $where") }
            } catch (e: Exception) { Bg.ui { toast("저장 실패: ${e.message}") } }
        }
    }

    private fun shareText() {
        val t = text.text.toString()
        if (t.isBlank()) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, t)
        }, "공유"))
    }

    /** Page image with recognised line boxes; tapping a box reports its text. */
    class OcrPageView(ctx: Context) : View(ctx) {
        var onLineTap: ((String) -> Unit)? = null
        private var bmp: Bitmap? = null
        private var res: Ocr.Result? = null
        private val dst = RectF()
        private var s = 1f
        private var hot = -1
        private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x222F6BFF }
        private val hotBox = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x662F6BFF }
        private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
        private val pad = ctx.dpf(10f)

        fun set(b: Bitmap?, r: Ocr.Result?) { bmp = b; res = r; hot = -1; layoutImg(); invalidate() }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutImg()

        private fun layoutImg() {
            val b = bmp ?: return
            if (width == 0) return
            s = min((width - 2 * pad) / b.width, (height - 2 * pad) / b.height)
            val w = b.width * s; val h = b.height * s
            dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        }

        private fun rectOf(i: Int): RectF? {
            val r = res?.lines?.getOrNull(i)?.box ?: return null
            return RectF(dst.left + r.left * s, dst.top + r.top * s, dst.left + r.right * s, dst.top + r.bottom * s)
        }

        override fun onDraw(canvas: Canvas) {
            val b = bmp ?: return
            canvas.drawBitmap(b, null, dst, bp)
            val n = res?.lines?.size ?: 0
            val rad = context.dpf(3f)
            for (i in 0 until n) rectOf(i)?.let { canvas.drawRoundRect(it, rad, rad, if (i == hot) hotBox else box) }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) return true
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val n = res?.lines?.size ?: 0
                for (i in 0 until n) {
                    val r = rectOf(i) ?: continue
                    r.inset(-context.dpf(4f), -context.dpf(4f))
                    if (r.contains(e.x, e.y)) {
                        hot = i; invalidate()
                        onLineTap?.invoke(res!!.lines[i].text)
                        return true
                    }
                }
            }
            return true
        }
    }
}
