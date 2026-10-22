package com.bookscan.app

import java.io.OutputStream
import java.util.Locale

/**
 * Minimal streaming PDF writer: one JPEG image per page, embedded as-is (DCTDecode),
 * so files stay small and pages are written one at a time.
 */
class PdfWriter(out: OutputStream) {
    private val os = CountingStream(out)
    private val offsets = HashMap<Int, Long>()
    private val pageIds = ArrayList<Int>()
    private var nextId = 3 // 1 = catalog, 2 = page tree

    init {
        os.write("%PDF-1.4\n".toByteArray(Charsets.US_ASCII))
        os.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
    }

    private fun f(v: Double) = String.format(Locale.US, "%.2f", v)

    private fun begin(id: Int) {
        offsets[id] = os.count
        ascii("$id 0 obj\n")
    }

    private fun ascii(s: String) = os.write(s.toByteArray(Charsets.US_ASCII))

    /**
     * Adds a page of [pageW] x [pageH] points showing the JPEG at the given placement
     * (points, origin bottom-left). [components] is 1 for grayscale JPEG, 3 for colour.
     */
    fun addPage(
        jpeg: ByteArray, imgW: Int, imgH: Int, components: Int,
        pageW: Double, pageH: Double, x: Double, y: Double, w: Double, h: Double
    ) {
        val imgId = nextId++; val contentId = nextId++; val pageId = nextId++
        val cs = if (components == 1) "/DeviceGray" else "/DeviceRGB"
        begin(imgId)
        ascii("<< /Type /XObject /Subtype /Image /Width $imgW /Height $imgH /ColorSpace $cs /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n")
        os.write(jpeg)
        ascii("\nendstream\nendobj\n")

        val content = "q\n1 1 1 rg 0 0 ${f(pageW)} ${f(pageH)} re f\n${f(w)} 0 0 ${f(h)} ${f(x)} ${f(y)} cm\n/Im0 Do\nQ\n"
            .toByteArray(Charsets.US_ASCII)
        begin(contentId)
        ascii("<< /Length ${content.size} >>\nstream\n")
        os.write(content)
        ascii("endstream\nendobj\n")

        begin(pageId)
        ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(pageW)} ${f(pageH)}] /Resources << /XObject << /Im0 $imgId 0 R >> >> /Contents $contentId 0 R >>\nendobj\n")
        pageIds.add(pageId)
    }

    fun pageCount() = pageIds.size

    fun finish(title: String? = null) {
        begin(2)
        ascii("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>\nendobj\n")
        begin(1)
        ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        var infoId = -1
        if (title != null) {
            infoId = nextId++
            begin(infoId)
            ascii("<< /Title ${utf16(title)} /Producer (BookScan) >>\nendobj\n")
        }
        val size = nextId
        val xref = os.count
        val sb = StringBuilder("xref\n0 $size\n0000000000 65535 f \n")
        for (i in 1 until size) sb.append(String.format(Locale.US, "%010d 00000 n \n", offsets[i] ?: 0L))
        sb.append("trailer\n<< /Size $size /Root 1 0 R")
        if (infoId > 0) sb.append(" /Info $infoId 0 R")
        sb.append(" >>\nstartxref\n$xref\n%%EOF\n")
        ascii(sb.toString())
        os.flush()
    }

    private fun utf16(s: String): String {
        val sb = StringBuilder("<FEFF")
        for (ch in s) sb.append(String.format(Locale.US, "%04X", ch.code))
        return sb.append(">").toString()
    }

    private class CountingStream(val inner: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { inner.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); count += len }
        override fun flush() = inner.flush()
        override fun close() = inner.close()
    }
}
