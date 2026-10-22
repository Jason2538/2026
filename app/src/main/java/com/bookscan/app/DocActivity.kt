package com.bookscan.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import java.io.FileOutputStream
import java.util.Collections

/** One scanned document: pages grid and all the export actions. */
class DocActivity : Activity() {
    companion object { const val EXTRA_DOC = "doc" }

    private lateinit var doc: Doc
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var grid: GridView
    private val adapter = PageAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = Docs.load(this, intent.getStringExtra(EXTRA_DOC) ?: "")
        if (d == null) { finish(); return }
        doc = d

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        root.padForSystemBars()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(20), dp(6))
        }
        header.addView(pill("←", 0, C.TEXT, 20f) { finish() })
        val tbox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setOnClickListener { rename() }
        }
        title = label(doc.name, 22f, C.TEXT, true).apply { setSingleLine(true) }
        sub = label("", 13f, C.SUB)
        tbox.addView(title); tbox.addView(sub)
        header.addView(tbox, lp(0, WRAP, 1f))
        header.addView(pill("PDF 저장", C.ACCENT, 0xFFFFFFFF.toInt(), 15f) { askPdf(PdfAction.SAVE) })
        root.addView(header)

        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(170)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(14); verticalSpacing = dp(14)
            setPadding(dp(20), dp(10), dp(20), dp(20))
            clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(0)
            adapter = this@DocActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> edit(pos) }
            setOnItemLongClickListener { _, _, pos, _ -> pageMenu(pos); true }
        }
        root.addView(grid, lp(MATCH, 0, 1f))

        val barScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(C.CARD)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        fun add(sym: String, cap: String, act: () -> Unit) =
            bar.addView(toolButton(sym, cap, C.TEXT, C.BG) { act() }, lp(dp(92), WRAP).apply { rightMargin = dp(10) })
        add("📷", "더 스캔") {
            startActivity(Intent(this, CameraActivity::class.java).putExtra(CameraActivity.EXTRA_DOC, doc.id))
        }
        add("🖼", "사진 추가") { Importer.pick(this) }
        add("Aa", "글자 인식") { if (doc.pages.isNotEmpty()) ocr(0) }
        add("🖨", "인쇄") { askPdf(PdfAction.PRINT) }
        add("↗", "PDF 공유") { askPdf(PdfAction.SHARE) }
        add("🗂", "이미지 저장") { saveImages() }
        add("✨", "전체 보정") { filterAll() }
        barScroll.addView(bar)
        root.addView(View(this).apply { setBackgroundColor(C.LINE) }, lp(MATCH, dp(1)))
        root.addView(barScroll, lp(MATCH, WRAP))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        title.text = doc.name
        val papers = doc.pages.mapNotNull { it.paper }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        sub.text = "${doc.pages.size}쪽" + (papers?.let { " · $it" } ?: "") + "  ·  쪽을 눌러 자르기·보정, 길게 눌러 순서·삭제"
        adapter.notifyDataSetChanged()
    }

    private fun rename() = askText("이름 바꾸기", doc.name) { n ->
        if (n.isNotEmpty()) Bg.io.execute { doc.name = n; Docs.save(doc); Bg.ui { refresh() } }
    }

    private fun edit(pos: Int) =
        startActivity(Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_DOC, doc.id).putExtra(EditActivity.EXTRA_PAGE, pos))

    private fun ocr(pos: Int) =
        startActivity(Intent(this, OcrActivity::class.java).putExtra(OcrActivity.EXTRA_DOC, doc.id).putExtra(OcrActivity.EXTRA_PAGE, pos))

    private fun pageMenu(pos: Int) {
        val items = arrayOf("자르기·보정", "글자 인식", "앞으로 옮기기", "뒤로 옮기기", "삭제")
        dialogBuilder().setTitle("${pos + 1}쪽").setItems(items) { _, which ->
            when (which) {
                0 -> edit(pos)
                1 -> ocr(pos)
                2, 3 -> {
                    val to = if (which == 2) pos - 1 else pos + 1
                    if (to in doc.pages.indices) Bg.io.execute {
                        Collections.swap(doc.pages, pos, to); Docs.save(doc); Bg.ui { refresh() }
                    }
                }
                4 -> dialogBuilder().setTitle("${pos + 1}쪽을 삭제할까요?")
                    .setPositiveButton("삭제") { _, _ ->
                        val p = doc.pages.getOrNull(pos) ?: return@setPositiveButton
                        Bg.io.execute { Docs.removePage(doc, p); Bg.ui { refresh() } }
                    }.setNegativeButton("취소", null).show()
            }
        }.show()
    }

    private fun filterAll() {
        if (doc.pages.isEmpty()) return
        val items = Filters.all.map { Filters.name(it) + "\n" + Filters.hint(it) }.toTypedArray()
        dialogBuilder().setTitle("모든 쪽에 적용할 보정").setItems(items) { _, which ->
            val f = Filters.all[which]
            val p = Progress(this, "보정 적용 중")
            Bg.io.execute {
                val list = doc.pages.toList()
                list.forEachIndexed { i, page ->
                    p.step(i + 1, list.size)
                    page.filter = f
                    try { Vision.render(doc, page) } catch (e: Throwable) {}
                }
                Docs.save(doc)
                p.dismiss()
                Bg.ui { refresh() }
            }
        }.show()
    }

    private enum class PdfAction { SAVE, SHARE, PRINT }

    private fun askPdf(action: PdfAction) {
        if (doc.pages.isEmpty()) { toast("쪽이 없어요"); return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(6), dp(24), 0) }
        box.addView(label("종이 크기", 14f, C.SUB, true))
        val sizeGroup = RadioGroup(this)
        val curSize = Settings.pdfSize(this)
        Export.sizes.forEachIndexed { i, (key, name, _) ->
            sizeGroup.addView(RadioButton(this).apply { id = 100 + i; text = name; textSize = 15f; isChecked = key == curSize })
        }
        box.addView(sizeGroup)
        box.addView(label("화질", 14f, C.SUB, true).apply { setPadding(0, dp(12), 0, 0) })
        val dpiGroup = RadioGroup(this)
        val curDpi = Settings.pdfDpi(this)
        Export.dpis.forEachIndexed { i, (dpi, name) ->
            dpiGroup.addView(RadioButton(this).apply { id = 200 + i; text = name; textSize = 15f; isChecked = dpi == curDpi })
        }
        box.addView(dpiGroup)
        if (action == PdfAction.PRINT) box.addView(label("인쇄 화면에서 용지를 고르고 ‘실제 크기’로 인쇄하면 원본 종이 크기 그대로 나와요.", 13f, C.SUB).apply { setPadding(0, dp(10), 0, 0) })
        val titleText = when (action) { PdfAction.SAVE -> "PDF로 저장"; PdfAction.SHARE -> "PDF 공유"; PdfAction.PRINT -> "인쇄" }
        dialogBuilder().setTitle(titleText).setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton(if (action == PdfAction.PRINT) "다음" else "저장") { _, _ ->
                val si = (sizeGroup.checkedRadioButtonId - 100).coerceIn(0, Export.sizes.size - 1)
                val di = (dpiGroup.checkedRadioButtonId - 200).coerceIn(0, Export.dpis.size - 1)
                val size = Export.sizes[si].first; val dpi = Export.dpis[di].first
                Settings.setPdfSize(this, size); Settings.setPdfDpi(this, dpi)
                runPdf(action, size, dpi)
            }.setNegativeButton("취소", null).show()
    }

    private fun runPdf(action: PdfAction, size: String, dpi: Int) {
        val p = Progress(this, "PDF 만드는 중")
        Bg.io.execute {
            try {
                if (action == PdfAction.PRINT) {
                    val f = Export.printFile(this)
                    FileOutputStream(f).use { Export.writePdf(doc, size, dpi, it) { i, n -> p.step(i, n) } }
                    p.dismiss()
                    Bg.ui { Export.printPdfFile(this, f, doc.name) }
                } else {
                    val (uri, where) = Export.savePdf(this, doc, size, dpi) { i, n -> p.step(i, n) }
                    p.dismiss()
                    Bg.ui {
                        if (action == PdfAction.SHARE) Export.share(this, uri, "application/pdf")
                        else toast("저장했어요: $where/${Export.safeName(doc.name)}.pdf")
                    }
                }
            } catch (e: Throwable) {
                p.dismiss()
                Bg.ui { toast("PDF 만들기 실패: ${e.message}") }
            }
        }
    }

    private fun saveImages() {
        if (doc.pages.isEmpty()) return
        val p = Progress(this, "이미지 저장 중")
        Bg.io.execute {
            try {
                val n = Export.saveImages(this, doc) { i, t -> p.step(i, t) }
                p.dismiss()
                Bg.ui { toast("사진 폴더(Pictures/${Export.DIR}/${Export.safeName(doc.name)})에 ${n}장 저장했어요") }
            } catch (e: Throwable) {
                p.dismiss(); Bg.ui { toast("저장 실패: ${e.message}") }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Importer.REQ_PICK && resultCode == RESULT_OK) {
            Importer.run(this, doc, Importer.uris(data)) { n -> toast("${n}장 추가했어요"); refresh() }
        }
    }

    private inner class PageAdapter : BaseAdapter() {
        override fun getCount() = doc.pages.size
        override fun getItem(position: Int): Any = doc.pages[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = (convertView as? FrameLayout) ?: FrameLayout(this@DocActivity).apply {
                background = roundRect(C.CARD, dpf(14f), C.LINE, dp(1))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(ImageView(context).apply { id = 1; scaleType = ImageView.ScaleType.FIT_CENTER },
                    FrameLayout.LayoutParams(MATCH, dp(220)))
                addView(TextView(context).apply {
                    id = 2; textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
                    background = roundRect(0xCC15171C.toInt(), dpf(100f)); setPadding(dp(8), dp(2), dp(8), dp(2))
                }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(4) })
            }
            val p = doc.pages[position]
            Thumbs.load(v.findViewById<ImageView>(1)!!, doc.thumbFile(p))
            v.findViewById<TextView>(2)!!.text = "${position + 1}"
            return v
        }
    }
}
