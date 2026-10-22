package com.bookscan.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.min

object Export {
    const val DIR = "BookScan"

    /** key, label, width pt, height pt (portrait). */
    val sizes = listOf(
        Triple("AUTO", "자동 (스캔한 종이 비율 그대로)", 0.0 to 0.0),
        Triple("A4", "A4 (210×297mm)", 595.28 to 841.89),
        Triple("B5", "B5 (182×257mm)", 515.91 to 728.50),
        Triple("A5", "A5 (148×210mm)", 419.53 to 595.28),
        Triple("LETTER", "Letter (8.5×11in)", 612.0 to 792.0)
    )
    val dpis = listOf(150 to "보통 (150dpi, 작은 파일)", 200 to "고화질 (200dpi)", 300 to "최고 (300dpi, 큰 파일)")

    fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "스캔" }

    /** Writes the document as a PDF. */
    fun writePdf(doc: Doc, sizeKey: String, dpi: Int, out: OutputStream, progress: (Int, Int) -> Unit) {
        val pdf = PdfWriter(out)
        val base = sizes.firstOrNull { it.first == sizeKey }?.third ?: (0.0 to 0.0)
        doc.pages.forEachIndexed { i, p ->
            progress(i, doc.pages.size)
            val file = doc.outFile(p)
            if (!file.exists()) Vision.render(doc, p)
            val m0 = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_UNCHANGED or Imgcodecs.IMREAD_IGNORE_ORIENTATION)
            if (m0.empty()) return@forEachIndexed
            val iw = m0.cols().toDouble(); val ih = m0.rows().toDouble()
            val landscape = iw > ih
            val pw: Double; val ph: Double
            if (base.first == 0.0) {
                val short = 595.28
                if (landscape) { ph = short; pw = short * iw / ih } else { pw = short; ph = short * ih / iw }
            } else {
                if (landscape) { pw = base.second; ph = base.first } else { pw = base.first; ph = base.second }
            }
            val s = min(pw / iw, ph / ih)
            val w = iw * s; val h = ih * s
            val x = (pw - w) / 2; val y = (ph - h) / 2
            val targetW = (w / 72.0 * dpi).toInt()
            val m = if (m0.cols() > targetW * 1.05) {
                val r = Mat()
                Imgproc.resize(m0, r, Size(targetW.toDouble(), m0.rows() * targetW.toDouble() / m0.cols()), 0.0, 0.0, Imgproc.INTER_AREA)
                m0.release(); r
            } else m0
            val buf = MatOfByte()
            Imgcodecs.imencode(".jpg", m, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 86))
            pdf.addPage(buf.toArray(), m.cols(), m.rows(), m.channels(), pw, ph, x, y, w, h)
            buf.release(); m.release()
        }
        progress(doc.pages.size, doc.pages.size)
        pdf.finish(doc.name)
    }

    private fun insert(ctx: Context, collection: Uri, name: String, mime: String, relDir: String, write: (OutputStream) -> Unit): Uri {
        val r = ctx.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = r.insert(collection, v) ?: throw IllegalStateException("저장 위치를 만들 수 없습니다")
        try {
            r.openOutputStream(uri)?.use(write) ?: throw IllegalStateException("파일을 열 수 없습니다")
            v.clear(); v.put(MediaStore.MediaColumns.IS_PENDING, 0)
            r.update(uri, v, null, null)
        } catch (e: Exception) {
            r.delete(uri, null, null); throw e
        }
        return uri
    }

    /** Saves a document-type file into Documents/BookScan (falls back to Download/BookScan). */
    fun saveDocument(ctx: Context, name: String, mime: String, write: (OutputStream) -> Unit): Pair<Uri, String> =
        try {
            insert(ctx, MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), name, mime,
                Environment.DIRECTORY_DOCUMENTS + "/" + DIR, write) to "문서/$DIR"
        } catch (e: Exception) {
            insert(ctx, MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), name, mime,
                Environment.DIRECTORY_DOWNLOADS + "/" + DIR, write) to "다운로드/$DIR"
        }

    fun savePdf(ctx: Context, doc: Doc, sizeKey: String, dpi: Int, progress: (Int, Int) -> Unit): Pair<Uri, String> =
        saveDocument(ctx, safeName(doc.name) + ".pdf", "application/pdf") { writePdf(doc, sizeKey, dpi, it, progress) }

    fun saveText(ctx: Context, name: String, text: String): Pair<Uri, String> =
        saveDocument(ctx, safeName(name) + ".txt", "text/plain") { it.write(text.toByteArray(Charsets.UTF_8)) }

    /** Saves every page image into Pictures/BookScan/<name>. Returns the count saved. */
    fun saveImages(ctx: Context, doc: Doc, progress: (Int, Int) -> Unit): Int {
        val folder = Environment.DIRECTORY_PICTURES + "/" + DIR + "/" + safeName(doc.name)
        var n = 0
        doc.pages.forEachIndexed { i, p ->
            progress(i, doc.pages.size)
            val f = doc.outFile(p)
            if (!f.exists()) Vision.render(doc, p)
            if (!f.exists()) return@forEachIndexed
            insert(ctx, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                String.format("%s_%03d.jpg", safeName(doc.name), i + 1), "image/jpeg", folder) { o ->
                FileInputStream(f).use { it.copyTo(o) }
            }
            n++
        }
        return n
    }

    fun share(ctx: Context, uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "공유").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** Builds a temporary PDF and opens the Android print dialog with it. */
    fun printPdfFile(ctx: Context, file: File, jobName: String) {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .build()
        pm.print(jobName, FilePrintAdapter(file, safeName(jobName) + ".pdf"), attrs)
    }

    fun printFile(ctx: Context): File = File(ctx.cacheDir, "print.pdf")

    private class FilePrintAdapter(val file: File, val name: String) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?, callback: LayoutResultCallback?, extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) { callback?.onLayoutCancelled(); return }
            val info = PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            callback?.onLayoutFinished(info, oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>?, destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?, callback: WriteResultCallback?
        ) {
            try {
                FileInputStream(file).use { i -> FileOutputStream(destination!!.fileDescriptor).use { o -> i.copyTo(o) } }
                callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback?.onWriteFailed(e.message)
            }
        }
    }

}
