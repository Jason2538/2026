package com.bookscan.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Shared colors. */
object C {
    const val BG = 0xFFF4F5F7.toInt()
    const val CARD = 0xFFFFFFFF.toInt()
    const val TEXT = 0xFF15171C.toInt()
    const val SUB = 0xFF6B7280.toInt()
    const val LINE = 0xFFE3E6EB.toInt()
    const val ACCENT = 0xFF2F6BFF.toInt()
    const val ACCENT_SOFT = 0xFFE7EEFF.toInt()
    const val DANGER = 0xFFE5484D.toInt()
    const val DARK = 0xFF0D0E11.toInt()
    const val DARK_CHIP = 0xCC23262D.toInt()
    const val GOOD = 0xFF2ECC71.toInt()
}

/** Background work: [io] is a single thread that owns all document file changes. */
object Bg {
    val io: ExecutorService = Executors.newSingleThreadExecutor()
    val work: ExecutorService = Executors.newFixedThreadPool(2)
    val main = Handler(Looper.getMainLooper())
    fun ui(block: () -> Unit) { main.post(block) }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun roundRect(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

fun rippled(bg: GradientDrawable, rippleColor: Int = 0x22000000): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), bg, null)

fun Context.label(text: String, sizeSp: Float, color: Int = C.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

/** Pill shaped button. */
fun Context.pill(
    text: String, bg: Int, fg: Int, sizeSp: Float = 15f, strokeColor: Int = 0,
    onClick: (View) -> Unit
): TextView = TextView(this).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(fg)
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    gravity = Gravity.CENTER
    setPadding(dp(18), dp(11), dp(18), dp(11))
    background = rippled(roundRect(bg, dpf(100f), strokeColor, if (strokeColor != 0) dp(1) else 0))
    isClickable = true
    setOnClickListener(onClick)
}

/** Square-ish tool button with a symbol on top and a caption below. */
fun Context.toolButton(symbol: String, caption: String, fg: Int, bg: Int, onClick: (View) -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = rippled(roundRect(bg, dpf(14f)))
        isClickable = true
        setOnClickListener(onClick)
        addView(label(symbol, 20f, fg, true).apply { gravity = Gravity.CENTER })
        addView(label(caption, 12f, fg).apply { gravity = Gravity.CENTER })
    }

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

/** Pads a view by the system bars (the app targets SDK 35, so it is edge-to-edge). */
fun View.padForSystemBars(extraLeft: Int = 0, extraTop: Int = 0, extraRight: Int = 0, extraBottom: Int = 0) {
    setOnApplyWindowInsetsListener { v, insets ->
        val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        v.setPadding(b.left + extraLeft, b.top + extraTop, b.right + extraRight, b.bottom + extraBottom)
        insets
    }
    requestApplyInsets()
}

fun Activity.dialogBuilder(): AlertDialog.Builder =
    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)

fun Activity.askText(title: String, initial: String, onOk: (String) -> Unit) {
    val input = EditText(this).apply {
        setText(initial)
        setSelectAllOnFocus(true)
        setSingleLine(true)
    }
    val box = LinearLayout(this).apply {
        setPadding(dp(22), dp(8), dp(22), 0)
        addView(input, lp(MATCH, WRAP))
    }
    dialogBuilder().setTitle(title).setView(box)
        .setPositiveButton("확인") { _, _ -> onOk(input.text.toString().trim()) }
        .setNegativeButton("취소", null)
        .show()
    input.requestFocus()
}

/** Decodes an image file no larger than [maxSide] pixels on its long side. */
fun decodeSampled(file: File, maxSide: Int): Bitmap? {
    if (!file.exists()) return null
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, o)
    if (o.outWidth <= 0) return null
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
    val o2 = BitmapFactory.Options().apply { inSampleSize = s }
    return BitmapFactory.decodeFile(file.absolutePath, o2)
}

fun imageSize(file: File): Pair<Int, Int> {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, o)
    return o.outWidth to o.outHeight
}

/** Asynchronous thumbnail loading with an in-memory cache. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 10).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(view: ImageView, file: File, maxSide: Int = 480) {
        val key = file.absolutePath + "@" + file.lastModified() + "@" + maxSide
        view.tag = key
        val hit = cache.get(key)
        if (hit != null) { view.setImageBitmap(hit); return }
        view.setImageDrawable(null)
        Bg.work.execute {
            val bmp = try { decodeSampled(file, maxSide) } catch (e: Throwable) { null }
            if (bmp != null) cache.put(key, bmp)
            Bg.ui { if (view.tag == key && bmp != null) view.setImageBitmap(bmp) }
        }
    }
}

fun darkChip(ctx: Context): GradientDrawable = roundRect(C.DARK_CHIP, ctx.dpf(100f))
fun colorWithAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

/** White status/navigation bar icons (for dark screens). */
fun Activity.darkSystemBars() {
    try { window.decorView } catch (_: Throwable) { return }
    window.insetsController?.setSystemBarsAppearance(
        0,
        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    )
}
