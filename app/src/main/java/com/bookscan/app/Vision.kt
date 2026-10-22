package com.bookscan.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * All image work: finding the paper, measuring its real proportions, flattening it,
 * splitting book spreads and the "looks like a printed file" enhancement.
 */
object Vision {
    const val DETECT_SIDE = 640
    const val MAX_ORIG_SIDE = 4000
    const val MAX_OUT_SIDE = 3600

    private val RATIOS = listOf(
        "A4·B5 비율" to sqrt(2.0),
        "레터 비율" to 279.4 / 215.9,
        "신국판 비율" to 225.0 / 152.0,
        "4×6 비율" to 1.5
    )

    // ---------------------------------------------------------------- detection

    /** Orders 4 points as TL, TR, BR, BL. */
    fun order(p: FloatArray): FloatArray {
        val idx = (0 until 4).toList()
        val sum = idx.map { p[2 * it] + p[2 * it + 1] }
        val diff = idx.map { p[2 * it + 1] - p[2 * it] }
        val tl = idx.minByOrNull { sum[it] }!!
        val br = idx.maxByOrNull { sum[it] }!!
        val tr = idx.minByOrNull { diff[it] }!!
        val bl = idx.maxByOrNull { diff[it] }!!
        val out = FloatArray(8)
        listOf(tl, tr, br, bl).forEachIndexed { i, k -> out[2 * i] = p[2 * k]; out[2 * i + 1] = p[2 * k + 1] }
        // Degenerate ordering (same point twice) – fall back to angle sort around the centre.
        if (setOf(tl, tr, br, bl).size < 4) {
            val cx = (0 until 4).map { p[2 * it] }.average(); val cy = (0 until 4).map { p[2 * it + 1] }.average()
            val sorted = idx.sortedBy { kotlin.math.atan2(p[2 * it + 1] - cy, p[2 * it] - cx) } // -pi..pi, starts near TL
            sorted.forEachIndexed { i, k -> out[2 * i] = p[2 * k]; out[2 * i + 1] = p[2 * k + 1] }
        }
        return out
    }

    private fun quadArea(q: FloatArray): Double {
        var a = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            a += q[2 * i] * q[2 * j + 1] - q[2 * j] * q[2 * i + 1]
        }
        return abs(a) / 2
    }

    private fun median(gray: Mat): Double {
        val n = (gray.total() * gray.channels()).toInt()
        val buf = ByteArray(n)
        gray.get(0, 0, buf)
        val hist = IntArray(256)
        for (b in buf) hist[b.toInt() and 0xFF]++
        var acc = 0
        for (i in 0 until 256) { acc += hist[i]; if (acc >= n / 2) return i.toDouble() }
        return 128.0
    }

    private fun quadFromContour(c: MatOfPoint, minArea: Double): Pair<FloatArray, Double>? {
        if (Imgproc.contourArea(c) < minArea) return null
        val hullIdx = MatOfInt()
        Imgproc.convexHull(c, hullIdx)
        val pts = c.toArray()
        val hullPts = hullIdx.toArray().map { pts[it] }
        hullIdx.release()
        if (hullPts.size < 4) return null
        val hull = MatOfPoint2f(*hullPts.toTypedArray())
        val peri = Imgproc.arcLength(hull, true)
        try {
            for (eps in doubleArrayOf(0.02, 0.03, 0.04, 0.06)) {
                val ap = MatOfPoint2f()
                Imgproc.approxPolyDP(hull, ap, eps * peri, true)
                val a = ap.toArray()
                ap.release()
                if (a.size == 4) {
                    val ip = MatOfPoint(*a)
                    val convex = Imgproc.isContourConvex(ip)
                    ip.release()
                    if (convex) {
                        val q = FloatArray(8)
                        a.forEachIndexed { i, p -> q[2 * i] = p.x.toFloat(); q[2 * i + 1] = p.y.toFloat() }
                        return q to quadArea(q)
                    }
                }
            }
        } finally {
            hull.release()
        }
        return null
    }

    /** Finds the paper in a small grayscale image. Returns ordered corners or null. */
    fun detectQuad(gray: Mat): FloatArray? {
        val w = gray.cols(); val h = gray.rows()
        val imgArea = w.toDouble() * h
        val minArea = imgArea * 0.12
        val blur = Mat(); val closed = Mat(); val e = Mat(); val m = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
        val k3 = Mat.ones(3, 3, CvType.CV_8U)
        var best: FloatArray? = null
        var bestArea = 0.0
        try {
            Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
            val med = median(blur)
            val lo = max(10.0, 0.5 * med); val hi = min(255.0, 1.3 * med)
            Imgproc.morphologyEx(blur, closed, Imgproc.MORPH_CLOSE, kernel)
            for (mode in 0 until 3) {
                when (mode) {
                    0 -> { Imgproc.Canny(closed, e, lo, hi); Imgproc.dilate(e, m, k3) }
                    1 -> { Imgproc.Canny(blur, e, lo * 0.6, hi * 0.6); Imgproc.dilate(e, m, k3) }
                    else -> Imgproc.threshold(closed, m, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
                }
                val contours = ArrayList<MatOfPoint>()
                val hier = Mat()
                Imgproc.findContours(m, contours, hier, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                hier.release()
                for (c in contours) {
                    val r = quadFromContour(c, minArea)
                    c.release()
                    if (r == null) continue
                    val (q, a) = r
                    if (a > imgArea * 0.97) continue
                    var onBorder = 0
                    for (i in 0 until 4) {
                        val x = q[2 * i]; val y = q[2 * i + 1]
                        if (x < 3) onBorder++; if (x > w - 4) onBorder++
                        if (y < 3) onBorder++; if (y > h - 4) onBorder++
                    }
                    if (onBorder >= 3) continue
                    if (a > bestArea) { best = q; bestArea = a }
                }
            }
        } finally {
            blur.release(); closed.release(); e.release(); m.release(); kernel.release(); k3.release()
        }
        return best?.let { order(it) }
    }

    /** Detects the paper in a full colour image, returning corners in that image's pixels. */
    fun detectOnImage(bgr: Mat): FloatArray? {
        val s = DETECT_SIDE.toDouble() / max(bgr.cols(), bgr.rows())
        val small = Mat(); val gray = Mat()
        try {
            Imgproc.resize(bgr, small, Size(bgr.cols() * s, bgr.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY)
            val q = detectQuad(gray) ?: return null
            // pull the corners in a hair so no desk/edge shadow line is left on the page
            val cx = (q[0] + q[2] + q[4] + q[6]) / 4; val cy = (q[1] + q[3] + q[5] + q[7]) / 4
            return FloatArray(8) { i ->
                val c = if (i % 2 == 0) cx else cy
                ((c + (q[i] - c) * 0.992f) / s).toFloat()
            }
        } finally {
            small.release(); gray.release()
        }
    }

    // ---------------------------------------------------------------- geometry

    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /**
     * Real width/height ratio of the photographed rectangle (Zhang & He, "whiteboard scanning").
     * Uses the camera focal length in pixels when known, otherwise estimates it from the corners.
     */
    fun aspect(q: FloatArray, cx: Double, cy: Double, focal: Double, imgLong: Int): Double? {
        fun v(i: Int) = doubleArrayOf(q[2 * i] - cx, q[2 * i + 1] - cy, 1.0)
        val m1 = v(0); val m2 = v(1); val m4 = v(2); val m3 = v(3)
        val d2 = dot(cross(m2, m4), m3); val d3 = dot(cross(m3, m4), m2)
        if (abs(d2) < 1e-9 || abs(d3) < 1e-9) return null
        val k2 = dot(cross(m1, m4), m3) / d2
        val k3 = dot(cross(m1, m4), m2) / d3
        val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }
        val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }
        var f2 = if (focal > 0) focal * focal else -1.0
        if (f2 < 0) {
            val den = n2[2] * n3[2]
            if (abs(den) > 1e-12) {
                val est = -(n2[0] * n3[0] + n2[1] * n3[1]) / den
                if (est > 0) {
                    val f = sqrt(est)
                    if (f > 0.4 * imgLong && f < 4.0 * imgLong) f2 = est
                }
            }
        }
        val r = if (f2 < 0) {
            // Nearly front-on (or unknown focal length): plain side lengths.
            if (abs(n2[2]) > 1e-3 || abs(n3[2]) > 1e-3) return null
            hypot(n2[0], n2[1]) / hypot(n3[0], n3[1])
        } else {
            sqrt((n2[0] * n2[0] + n2[1] * n2[1] + f2 * n2[2] * n2[2]) / (n3[0] * n3[0] + n3[1] * n3[1] + f2 * n3[2] * n3[2]))
        }
        return if (r.isFinite() && r > 0.15 && r < 7) r else null
    }

    data class OutSize(val w: Int, val h: Int, val paper: String?)

    fun outputSize(q: FloatArray, imgW: Int, imgH: Int, focal: Double, snap: Boolean, cap: Int = MAX_OUT_SIDE): OutSize {
        fun d(a: Int, b: Int) = hypot((q[2 * a] - q[2 * b]).toDouble(), (q[2 * a + 1] - q[2 * b + 1]).toDouble())
        val mw = max(d(0, 1), d(3, 2)); val mh = max(d(0, 3), d(1, 2))
        var w = mw; var h = mh
        val measured = mw / max(1.0, mh)
        val r = aspect(q, imgW / 2.0, imgH / 2.0, focal, max(imgW, imgH))
        if (r != null && r / measured < 2.5 && measured / r < 2.5) {
            val long = max(mw, mh)
            if (r >= 1) { w = long; h = long / r } else { h = long; w = long * r }
        }
        var paper: String? = null
        val ratio = max(w, h) / max(1.0, min(w, h))
        for ((name, rr) in RATIOS) {
            if (abs(ratio - rr) / rr < 0.03) {
                paper = name
                if (snap) { if (h >= w) h = w * rr else w = h * rr }
                break
            }
        }
        val s = min(1.0, cap / max(w, h))
        return OutSize(max(8, (w * s).roundToInt()), max(8, (h * s).roundToInt()), paper)
    }

    private fun pts(q: FloatArray) = MatOfPoint2f(
        Point(q[0].toDouble(), q[1].toDouble()), Point(q[2].toDouble(), q[3].toDouble()),
        Point(q[4].toDouble(), q[5].toDouble()), Point(q[6].toDouble(), q[7].toDouble())
    )

    private fun rectPts(w: Int, h: Int) = MatOfPoint2f(
        Point(0.0, 0.0), Point(w - 1.0, 0.0), Point(w - 1.0, h - 1.0), Point(0.0, h - 1.0)
    )

    fun fullQuad(w: Int, h: Int) = floatArrayOf(0f, 0f, w - 1f, 0f, w - 1f, h - 1f, 0f, h - 1f)

    fun warp(src: Mat, q: FloatArray, w: Int, h: Int): Mat {
        val a = pts(q); val b = rectPts(w, h)
        val m = Imgproc.getPerspectiveTransform(a, b)
        val out = Mat()
        Imgproc.warpPerspective(src, out, m, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        a.release(); b.release(); m.release()
        return out
    }

    fun rotate(src: Mat, deg: Int): Mat {
        val code = when (((deg % 360) + 360) % 360) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return src
        }
        val out = Mat()
        Core.rotate(src, out, code)
        return out
    }

    /** x position of the book gutter (darkest column near the middle). */
    fun splitGutter(bgr: Mat): Int {
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val w = gray.cols(); val h = gray.rows()
        val roi = gray.submat(h / 10, h * 9 / 10, 0, w)
        val cols = Mat()
        Core.reduce(roi, cols, 0, Core.REDUCE_AVG, CvType.CV_32F)
        val v = FloatArray(w)
        cols.get(0, 0, v)
        roi.release(); cols.release(); gray.release()
        val rad = max(2, w / 150)
        val sm = FloatArray(w) { i ->
            var s = 0f; var n = 0
            for (j in max(0, i - rad)..min(w - 1, i + rad)) { s += v[j]; n++ }
            s / n
        }
        val a = (w * 0.38).toInt(); val b = (w * 0.62).toInt()
        var best = w / 2; var bv = Float.MAX_VALUE
        for (i in a until b) if (sm[i] < bv) { bv = sm[i]; best = i }
        return best
    }

    // ---------------------------------------------------------------- enhancement

    private fun background(ch: Mat): Mat {
        val w = ch.cols(); val h = ch.rows()
        val small = Mat()
        Imgproc.resize(ch, small, Size(max(8, w / 4).toDouble(), max(8, h / 4).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val k = max(7, (min(small.cols(), small.rows()) / 25) or 1)
        val kern = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
        val t = Mat()
        Imgproc.dilate(small, t, kern)
        Imgproc.medianBlur(t, small, 21)
        Imgproc.GaussianBlur(small, t, Size(0.0, 0.0), k / 2.0)
        val out = Mat()
        Imgproc.resize(t, out, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release(); t.release(); kern.release()
        return out
    }

    private fun lut(black: Double, white: Double, gamma: Double): Mat {
        val b = ByteArray(256) { x ->
            val v = ((x - black) / (white - black)).coerceIn(0.0, 1.0).pow(gamma)
            (v * 255).roundToInt().toByte()
        }
        val m = Mat(1, 256, CvType.CV_8UC1)
        m.put(0, 0, b)
        return m
    }

    private fun sharpen(src: Mat, amount: Double, sigma: Double = 1.2): Mat {
        val blur = Mat(); val out = Mat()
        Imgproc.GaussianBlur(src, blur, Size(0.0, 0.0), sigma)
        Core.addWeighted(src, 1 + amount, blur, -amount, 0.0, out)
        blur.release()
        return out
    }

    /** Removes shadows/paper tint: each pixel divided by the local paper brightness. */
    private fun flatten(ch: Mat): Mat {
        val bg = background(ch)
        val out = Mat()
        Core.divide(ch, bg, out, 255.0)
        bg.release()
        return out
    }

    private fun printBw(bgr: Mat, s: Int): Mat {
        val g = Mat()
        Imgproc.cvtColor(bgr, g, Imgproc.COLOR_BGR2GRAY)
        val n = flatten(g); g.release()
        val l = lut(40 + s * 0.6, 222.0, 1.0 + s / 100.0)
        val t = Mat()
        Core.LUT(n, l, t); n.release(); l.release()
        val out = sharpen(t, 0.5); t.release()
        return out
    }

    fun applyFilter(bgr: Mat, filter: String, strength: Int): Mat {
        val s = strength.coerceIn(0, 100)
        return when (filter) {
            Filters.PRINT_BW -> printBw(bgr, s)
            Filters.BINARY -> {
                val g = printBw(bgr, 50)
                val tmp = Mat(); val out = Mat()
                val otsu = Imgproc.threshold(g, tmp, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
                Imgproc.threshold(g, out, (otsu + (s - 50) * 0.8).coerceIn(5.0, 250.0), 255.0, Imgproc.THRESH_BINARY)
                g.release(); tmp.release()
                out
            }
            Filters.PRINT_COLOR -> {
                val chans = ArrayList<Mat>()
                Core.split(bgr, chans)
                val flat = chans.map { c -> flatten(c).also { c.release() } }
                val merged = Mat()
                Core.merge(flat, merged)
                flat.forEach { it.release() }
                val l = lut(20 + s * 0.4, 226.0, 1.0 + s / 200.0)
                val t = Mat()
                Core.LUT(merged, l, t); merged.release(); l.release()
                val out = sharpen(t, 0.4); t.release()
                out
            }
            Filters.GRAY -> Mat().also { Imgproc.cvtColor(bgr, it, Imgproc.COLOR_BGR2GRAY) }
            Filters.VIVID -> {
                val lab = Mat()
                Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
                val chans = ArrayList<Mat>()
                Core.split(lab, chans)
                val clahe = Imgproc.createCLAHE(1.0 + s / 25.0, Size(8.0, 8.0))
                val l2 = Mat()
                clahe.apply(chans[0], l2)
                chans[0].release(); chans[0] = l2
                Core.merge(chans, lab)
                chans.forEach { it.release() }
                val t = Mat()
                Imgproc.cvtColor(lab, t, Imgproc.COLOR_Lab2BGR); lab.release()
                val out = sharpen(t, 0.35); t.release()
                out
            }
            else -> bgr.clone()
        }
    }

    // ---------------------------------------------------------------- page files

    fun readImage(path: String): Mat =
        Imgcodecs.imread(path, Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION)

    private fun writeJpeg(path: String, m: Mat, quality: Int) {
        Imgcodecs.imwrite(path, m, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality))
    }

    private fun downscale(m: Mat, maxSide: Int): Pair<Mat, Double> {
        val long = max(m.cols(), m.rows())
        if (long <= maxSide) return m to 1.0
        val s = maxSide.toDouble() / long
        val out = Mat()
        Imgproc.resize(m, out, Size(m.cols() * s, m.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA)
        return out to s
    }

    /** Crops/flattens the page from its original photo (also fills in the paper label). */
    private fun cropPage(src: Mat, page: Page, cap: Int, scale: Double = 1.0): Mat {
        var q = page.quad?.let { FloatArray(8) { i -> (it[i] * scale).toFloat() } } ?: fullQuad(src.cols(), src.rows())
        if (quadArea(q) < 0.01 * src.cols() * src.rows()) q = fullQuad(src.cols(), src.rows())
        val sz = outputSize(q, src.cols(), src.rows(), page.focal * scale, page.snap, cap)
        page.paper = sz.paper
        return warp(src, q, sz.w, sz.h)
    }

    /** Full-quality render of a page into its output and thumbnail files. */
    fun render(doc: Doc, page: Page) {
        val src = readImage(doc.origFile(page).absolutePath)
        if (src.empty()) return
        val warped = cropPage(src, page, MAX_OUT_SIDE); src.release()
        val rotated = rotate(warped, page.rot)
        if (rotated !== warped) warped.release()
        val out = applyFilter(rotated, page.filter, page.strength); rotated.release()
        writeJpeg(doc.outFile(page).absolutePath, out, 90)
        val (th, _) = downscale(out, 480)
        writeJpeg(doc.thumbFile(page).absolutePath, th, 80)
        if (th !== out) th.release()
        out.release()
    }

    /** Fast, smaller render for the editor preview. */
    fun renderPreview(doc: Doc, page: Page, maxSide: Int): Bitmap? {
        val src0 = readImage(doc.origFile(page).absolutePath)
        if (src0.empty()) return null
        val (src, s) = downscale(src0, 2400)
        if (src !== src0) src0.release()
        val warped = cropPage(src, page, maxSide, s); src.release()
        val rotated = rotate(warped, page.rot)
        if (rotated !== warped) warped.release()
        val out = applyFilter(rotated, page.filter, page.strength); rotated.release()
        val bmp = toBitmap(out); out.release()
        return bmp
    }

    fun toBitmap(m: Mat): Bitmap? {
        val buf = MatOfByte()
        Imgcodecs.imencode(".jpg", m, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 92))
        val bytes = buf.toArray(); buf.release()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /**
     * Adds a photo (already upright) to the document.
     * [live] = corners from the camera preview, normalised 0..1 (used when the still photo
     * detection fails). Returns the created pages.
     */
    fun addPhoto(
        doc: Doc, img0: Mat, live: FloatArray?, focal0: Double,
        twoPage: Boolean, rightToLeft: Boolean, filter: String
    ): List<Page> {
        val (img, s) = downscale(img0, MAX_ORIG_SIDE)
        val focal = focal0 * s
        val w = img.cols(); val h = img.rows()
        val gid = Docs.newId()
        val origName = "o_$gid.jpg"
        writeJpeg(java.io.File(doc.dir, origName).absolutePath, img, 93)

        val detected = detectOnImage(img)
        val liveQ = live?.let { l -> FloatArray(8) { i -> l[i] * if (i % 2 == 0) w else h } }
        val q = when {
            detected != null && liveQ != null -> if (quadArea(detected) >= 0.6 * quadArea(liveQ)) detected else liveQ
            else -> detected ?: liveQ
        }

        val pages = ArrayList<Page>()
        if (!twoPage) {
            pages.add(Page(Docs.newId(), origName, q, filter, focal = focal))
        } else {
            val full = q ?: fullQuad(w, h)
            val sz = outputSize(full, w, h, focal, false)
            val warped = warp(img, full, sz.w, sz.h)
            val x = splitGutter(warped).toDouble()
            warped.release()
            val rect = rectPts(sz.w, sz.h); val quadM = pts(full)
            val inv = Imgproc.getPerspectiveTransform(rect, quadM)
            rect.release(); quadM.release()
            val W = sz.w - 1.0; val H = sz.h - 1.0
            fun back(vararg p: Point): FloatArray {
                val src = MatOfPoint2f(*p); val dst = MatOfPoint2f()
                Core.perspectiveTransform(src, dst, inv)
                val a = dst.toArray(); src.release(); dst.release()
                return FloatArray(8) { i -> if (i % 2 == 0) a[i / 2].x.toFloat() else a[i / 2].y.toFloat() }
            }
            val left = back(Point(0.0, 0.0), Point(x, 0.0), Point(x, H), Point(0.0, H))
            val right = back(Point(x, 0.0), Point(W, 0.0), Point(W, H), Point(x, H))
            inv.release()
            val halves = if (rightToLeft) listOf(right, left) else listOf(left, right)
            halves.forEach { pages.add(Page(Docs.newId(), origName, it, filter, focal = focal)) }
        }
        if (img !== img0) img.release()
        for (p in pages) {
            render(doc, p)
            doc.pages.add(p)
        }
        Docs.save(doc)
        return pages
    }

    /** Imports a gallery picture as page(s). */
    fun importUri(ctx: Context, doc: Doc, uri: Uri, twoPage: Boolean, rightToLeft: Boolean, filter: String): Boolean {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        val rot = try {
            when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) { 0 }
        val buf = MatOfByte(*bytes)
        val m = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION)
        buf.release()
        if (m.empty()) return false
        val up = rotate(m, rot)
        if (up !== m) m.release()
        addPhoto(doc, up, null, 0.0, twoPage, rightToLeft, filter)
        up.release()
        return true
    }
}
