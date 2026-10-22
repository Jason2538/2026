package com.bookscan.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small "working…" dialog. */
class Progress(activity: Activity, title: String) {
    private val text = activity.label("", 15f, C.SUB)
    private val dlg: AlertDialog = activity.dialogBuilder().setTitle(title)
        .setView(LinearLayout(activity).apply {
            setPadding(activity.dp(24), activity.dp(8), activity.dp(24), activity.dp(8))
            addView(text)
        })
        .setCancelable(false).show()

    fun set(s: String) { text.text = s }
    fun step(i: Int, n: Int) = Bg.ui { set("$i / $n") }
    fun dismiss() = Bg.ui { try { dlg.dismiss() } catch (e: Exception) {} }
}

object Importer {
    const val REQ_PICK = 41

    fun pick(activity: Activity) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION")
        activity.startActivityForResult(i, REQ_PICK)
    }

    fun uris(data: Intent?): List<Uri> {
        if (data == null) return emptyList()
        val clip = data.clipData
        if (clip != null) return (0 until clip.itemCount).map { clip.getItemAt(it).uri }
        return listOfNotNull(data.data)
    }

    /** Imports pictures into [doc] on the document thread, then calls [done] on the main thread. */
    fun run(activity: Activity, doc: Doc, uris: List<Uri>, done: (Int) -> Unit) {
        if (uris.isEmpty()) { done(0); return }
        val two = Settings.twoPage(activity); val rtl = Settings.rightToLeft(activity); val f = Settings.filter(activity)
        val p = Progress(activity, "사진 가져오는 중")
        Bg.io.execute {
            var ok = 0
            uris.forEachIndexed { i, u ->
                p.step(i + 1, uris.size)
                try { if (Vision.importUri(activity, doc, u, two, rtl, f)) ok++ } catch (e: Throwable) {}
            }
            p.dismiss()
            Bg.ui { done(ok) }
        }
    }
}

/** Library of scanned documents. */
class MainActivity : Activity() {
    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private var docs: List<Doc> = emptyList()
    private var importTarget: Doc? = null
    private val fmt = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.padForSystemBars(dp(24), dp(18), dp(24), 0)
        col.addView(label("북스캔", 30f, C.TEXT, true))
        col.addView(label("책과 문서를 비추면 종이만 잘라서 PDF로 저장해요", 15f, C.SUB).apply { setPadding(0, dp(4), 0, dp(18)) })

        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(190)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(16); verticalSpacing = dp(16)
            clipToPadding = false
            setPadding(0, 0, 0, dp(120))
            selector = android.graphics.drawable.ColorDrawable(0)
            adapter = DocAdapter()
            setOnItemClickListener { _, _, pos, _ -> open(docs[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> menu(docs[pos]); true }
        }
        col.addView(grid, lp(MATCH, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        empty = label("아직 스캔한 문서가 없어요.\n아래 ‘스캔하기’를 눌러 시작하세요.", 16f, C.SUB).apply { gravity = Gravity.CENTER }
        root.addView(empty, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        actions.padForSystemBars(0, 0, dp(24), dp(24))
        actions.addView(pill("사진 가져오기", C.CARD, C.TEXT, 16f, C.LINE) {
            importTarget = Docs.create(this); Importer.pick(this)
        }.apply { elevation = dpf(4f) })
        actions.addView(pill("스캔하기", C.ACCENT, 0xFFFFFFFF.toInt(), 17f) { scan() }
            .apply { elevation = dpf(6f); setPadding(dp(28), dp(15), dp(28), dp(15)) },
            lp(WRAP, WRAP).apply { leftMargin = dp(12) })
        root.addView(actions, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
        showLastCrash()
    }

    /** If the app crashed last time, show the error so it can be copied and sent. */
    private fun showLastCrash() {
        val f = CrashLog.file(application)
        if (!f.exists()) return
        val text = try { f.readText() } catch (e: Exception) { "" }
        f.delete()
        if (text.isBlank()) return
        val tv = label(text, 12f, C.TEXT).apply { setTextIsSelectable(true); setPadding(dp(22), dp(8), dp(22), dp(8)) }
        dialogBuilder().setTitle("지난번에 오류가 났어요")
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton("오류 내용 복사") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("북스캔 오류", text))
                toast("복사했어요. 채팅에 붙여넣어 보내 주세요")
            }
            .setNegativeButton("닫기", null).show()
    }

    private fun reload() {
        Bg.work.execute {
            val list = Docs.list(this)
            Bg.ui {
                docs = list
                (grid.adapter as BaseAdapter).notifyDataSetChanged()
                empty.visibility = if (docs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun scan() {
        if (!CrashLog.openCvReady) { toast("이미지 엔진을 불러오지 못했어요. 앱을 다시 설치해 주세요"); return }
        val d = Docs.create(this)
        startActivity(Intent(this, CameraActivity::class.java)
            .putExtra(CameraActivity.EXTRA_DOC, d.id).putExtra(CameraActivity.EXTRA_OPEN_DOC, true))
    }

    private fun open(d: Doc) = startActivity(Intent(this, DocActivity::class.java).putExtra(DocActivity.EXTRA_DOC, d.id))

    private fun menu(d: Doc) {
        dialogBuilder().setTitle(d.name).setItems(arrayOf("열기", "이름 바꾸기", "삭제")) { _, which ->
            when (which) {
                0 -> open(d)
                1 -> askText("이름 바꾸기", d.name) { n -> if (n.isNotEmpty()) Bg.io.execute { d.name = n; Docs.save(d); Bg.ui { reload() } } }
                2 -> dialogBuilder().setTitle("삭제할까요?").setMessage("‘${d.name}’ (${d.pages.size}쪽)을 지웁니다. 되돌릴 수 없어요.")
                    .setPositiveButton("삭제") { _, _ -> Bg.io.execute { Docs.delete(d); Bg.ui { reload() } } }
                    .setNegativeButton("취소", null).show()
            }
        }.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != Importer.REQ_PICK) return
        val d = importTarget ?: return
        importTarget = null
        val uris = if (resultCode == RESULT_OK) Importer.uris(data) else emptyList()
        if (uris.isEmpty()) { Bg.io.execute { if (d.pages.isEmpty()) Docs.delete(d) }; return }
        Importer.run(this, d, uris) { n ->
            if (n == 0) { toast("사진을 가져오지 못했어요"); Bg.io.execute { Docs.delete(d) } } else open(d)
        }
    }

    private inner class DocAdapter : BaseAdapter() {
        override fun getCount() = docs.size
        override fun getItem(position: Int): Any = docs[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = roundRect(C.CARD, dpf(18f), C.LINE, dp(1))
                setPadding(dp(10), dp(10), dp(10), dp(12))
                val img = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = roundRect(0xFFEDEFF3.toInt(), dpf(12f))
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    id = 1
                }
                addView(img, lp(MATCH, dp(230)))
                addView(label("", 16f, C.TEXT, true).apply { id = 2; setSingleLine(true); setPadding(dp(4), dp(10), dp(4), 0) })
                addView(label("", 13f, C.SUB).apply { id = 3; setPadding(dp(4), dp(2), dp(4), 0) })
            }
            val d = docs[position]
            val img = v.findViewById<ImageView>(1)!!
            val cover = d.coverThumb()
            if (cover != null) Thumbs.load(img, cover) else { img.tag = null; img.setImageDrawable(null) }
            v.findViewById<TextView>(2)!!.text = d.name
            v.findViewById<TextView>(3)!!.text = "${d.pages.size}쪽 · ${fmt.format(Date(d.updated))}"
            return v
        }
    }
}
