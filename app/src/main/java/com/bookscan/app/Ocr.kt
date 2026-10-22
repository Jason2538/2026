package com.bookscan.app

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/** On-device text recognition (Korean + Latin), no internet needed. */
object Ocr {
    class Line(val text: String, val box: Rect?)
    class Result(val text: String, val lines: List<Line>)

    private val client: TextRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    /** Callback runs on the main thread. */
    fun recognize(bmp: Bitmap, done: (Result?, Exception?) -> Unit) {
        client.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t: Text -> done(toResult(t), null) }
            .addOnFailureListener { e: Exception -> done(null, e) }
    }

    private fun toResult(t: Text): Result {
        val lines = ArrayList<Line>()
        val sb = StringBuilder()
        for (b in t.textBlocks) {
            for (l in b.lines) lines.add(Line(l.text, l.boundingBox))
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(b.lines.joinToString("\n") { it.text })
        }
        return Result(sb.toString(), lines)
    }
}
