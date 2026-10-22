package com.bookscan.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Image enhancement presets. */
object Filters {
    const val ORIGINAL = "ORIG"
    const val PRINT_BW = "PRINT_BW"
    const val PRINT_COLOR = "PRINT_COLOR"
    const val BINARY = "BINARY"
    const val GRAY = "GRAY"
    const val VIVID = "VIVID"

    val all = listOf(PRINT_BW, PRINT_COLOR, BINARY, VIVID, GRAY, ORIGINAL)

    fun name(f: String) = when (f) {
        PRINT_BW -> "인쇄용 흑백"
        PRINT_COLOR -> "인쇄용 컬러"
        BINARY -> "완전 흑백"
        GRAY -> "회색조"
        VIVID -> "선명하게"
        else -> "원본"
    }

    fun hint(f: String) = when (f) {
        PRINT_BW -> "그림자·종이색을 지우고 글자를 진하게. 인쇄하면 문서 파일처럼 보입니다"
        PRINT_COLOR -> "바탕은 하얗게, 형광펜·컬러 글씨는 그대로"
        BINARY -> "검정/흰색 두 가지로만. 파일이 가장 작습니다"
        GRAY -> "색만 뺀 회색 사진"
        VIVID -> "사진·그림이 많은 페이지용 대비 보정"
        else -> "보정 없이 자르기만"
    }

    /** Filters where the strength slider does something. */
    fun hasStrength(f: String) = f == PRINT_BW || f == PRINT_COLOR || f == BINARY || f == VIVID
}

class Page(
    val id: String,
    var orig: String,          // file name of the full, upright original photo
    var quad: FloatArray?,     // 4 corners (TL,TR,BR,BL) x,y in original pixels; null = whole photo
    var filter: String,
    var rot: Int = 0,          // extra rotation after cropping, degrees clockwise
    var strength: Int = 50,
    var focal: Double = 0.0,   // focal length in original pixels (0 = unknown)
    var snap: Boolean = true,  // snap to standard paper ratio
    var paper: String? = null  // detected paper ratio label
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("orig", orig); put("filter", filter); put("rot", rot)
        put("str", strength); put("f", focal); put("snap", snap)
        paper?.let { put("paper", it) }
        quad?.let { q -> put("quad", JSONArray().apply { q.forEach { put(it.toDouble()) } }) }
    }

    companion object {
        fun fromJson(o: JSONObject): Page {
            val qa = o.optJSONArray("quad")
            val q = if (qa != null && qa.length() == 8) FloatArray(8) { qa.getDouble(it).toFloat() } else null
            return Page(
                o.getString("id"), o.getString("orig"), q, o.optString("filter", Filters.PRINT_BW),
                o.optInt("rot", 0), o.optInt("str", 50), o.optDouble("f", 0.0), o.optBoolean("snap", true),
                if (o.has("paper")) o.getString("paper") else null
            )
        }
    }
}

class Doc(val id: String, val dir: File, var name: String, var created: Long, var updated: Long) {
    /** Copy-on-write so screens can read while the background thread appends. */
    val pages: MutableList<Page> = CopyOnWriteArrayList()

    fun origFile(p: Page) = File(dir, p.orig)
    fun outFile(p: Page) = File(dir, "p_${p.id}.jpg")
    fun thumbFile(p: Page) = File(dir, "t_${p.id}.jpg")
    fun coverThumb(): File? = pages.firstOrNull()?.let { thumbFile(it) }
}

object Docs {
    /** One shared instance per document, so every screen and the background thread see the same pages. */
    private val cache = ConcurrentHashMap<String, Doc>()

    fun root(ctx: Context): File = File(ctx.filesDir, "docs").apply { mkdirs() }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 16)

    fun defaultName(): String = "스캔 " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date())

    fun create(ctx: Context, name: String = defaultName()): Doc {
        val id = newId()
        val dir = File(root(ctx), id).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val d = Doc(id, dir, name, now, now)
        save(d)
        cache[id] = d
        return d
    }

    fun load(ctx: Context, id: String): Doc? {
        if (id.isEmpty()) return null
        cache[id]?.let { if (it.dir.exists()) return it }
        val d = loadDir(File(root(ctx), id)) ?: return null
        return cache.putIfAbsent(id, d) ?: d
    }

    private fun loadDir(dir: File): Doc? {
        val f = File(dir, "doc.json")
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val d = Doc(dir.name, dir, o.optString("name", "스캔"), o.optLong("created"), o.optLong("updated"))
            val arr = o.optJSONArray("pages") ?: JSONArray()
            for (i in 0 until arr.length()) d.pages.add(Page.fromJson(arr.getJSONObject(i)))
            d
        } catch (e: Exception) {
            null
        }
    }

    fun list(ctx: Context): List<Doc> =
        (root(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { load(ctx, it.name) }
            .sortedByDescending { it.updated }

    @Synchronized
    fun save(d: Doc) {
        d.updated = System.currentTimeMillis()
        val o = JSONObject().apply {
            put("name", d.name); put("created", d.created); put("updated", d.updated)
            put("pages", JSONArray().apply { d.pages.forEach { put(it.toJson()) } })
        }
        val tmp = File(d.dir, "doc.json.tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(File(d.dir, "doc.json"))) {
            File(d.dir, "doc.json").writeText(o.toString()); tmp.delete()
        }
    }

    fun delete(d: Doc) { cache.remove(d.id); d.dir.deleteRecursively() }

    /** Removes a page and its files (the original is kept while another page still uses it). */
    fun removePage(d: Doc, p: Page) {
        d.pages.remove(p)
        d.outFile(p).delete(); d.thumbFile(p).delete()
        if (d.pages.none { it.orig == p.orig }) d.origFile(p).delete()
        save(d)
    }
}

object Settings {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun autoCapture(ctx: Context) = sp(ctx).getBoolean("auto", true)
    fun setAutoCapture(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("auto", v).apply()
    fun twoPage(ctx: Context) = sp(ctx).getBoolean("two", false)
    fun setTwoPage(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("two", v).apply()
    fun filter(ctx: Context) = sp(ctx).getString("filter", Filters.PRINT_BW) ?: Filters.PRINT_BW
    fun setFilter(ctx: Context, v: String) = sp(ctx).edit().putString("filter", v).apply()
    fun rightToLeft(ctx: Context) = sp(ctx).getBoolean("rtl", false)
    fun setRightToLeft(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("rtl", v).apply()
    fun pdfSize(ctx: Context) = sp(ctx).getString("pdfSize", "AUTO") ?: "AUTO"
    fun setPdfSize(ctx: Context, v: String) = sp(ctx).edit().putString("pdfSize", v).apply()
    fun pdfDpi(ctx: Context) = sp(ctx).getInt("pdfDpi", 200)
    fun setPdfDpi(ctx: Context, v: Int) = sp(ctx).edit().putInt("pdfDpi", v).apply()
}
