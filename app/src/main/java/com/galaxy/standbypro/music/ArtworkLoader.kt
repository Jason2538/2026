package com.galaxy.standbypro.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 앨범 커버 로딩/축소/색 추출.
 * - 음악 앱이 메타데이터로 준 비트맵 또는 URI만 사용 (인터넷 스크래핑 없음)
 * - 최대 [MAX_SIZE]px 로 줄여 메모리 사용을 제한
 */
class ArtworkLoader(context: Context) {
    private val appContext = context.applicationContext

    suspend fun fromBitmap(source: Bitmap, key: String): Artwork? = withContext(Dispatchers.Default) {
        runCatching {
            if (source.isRecycled) return@runCatching null
            val scaled = downscale(source)
            build(scaled, key)
        }.getOrNull()
    }

    suspend fun fromUri(uri: String, key: String): Artwork? = withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(appContext)
                .data(uri)
                .size(MAX_SIZE)
                .allowHardware(false)
                .build()
            val result = appContext.imageLoader.execute(request)
            val drawable = (result as? SuccessResult)?.drawable ?: return@runCatching null
            val bmp = (drawable as? BitmapDrawable)?.bitmap ?: drawable.toBitmap()
            build(downscale(bmp), key)
        }.getOrNull()
    }

    /** 로컬 음악 파일에 들어 있는 커버(ID3 APIC 등)를 추출 */
    suspend fun fromAudioFile(uri: Uri, key: String): Artwork? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            val bytes = retriever.embeddedPicture ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIZE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@withContext null
            build(downscale(bmp), key)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun downscale(src: Bitmap): Bitmap {
        val largest = max(src.width, src.height)
        if (largest <= MAX_SIZE) {
            return if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        }
        val ratio = MAX_SIZE.toFloat() / largest
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        return Bitmap.createScaledBitmap(soft, (soft.width * ratio).toInt().coerceAtLeast(1), (soft.height * ratio).toInt().coerceAtLeast(1), true)
    }

    private fun build(bitmap: Bitmap, key: String): Artwork {
        val palette = Palette.from(bitmap).maximumColorCount(16).generate()
        val accentRaw = palette.vibrantSwatch?.rgb
            ?: palette.lightVibrantSwatch?.rgb
            ?: palette.dominantSwatch?.rgb
            ?: Color.WHITE
        val darkRaw = palette.darkVibrantSwatch?.rgb
            ?: palette.darkMutedSwatch?.rgb
            ?: palette.dominantSwatch?.rgb
            ?: Color.DKGRAY
        return Artwork(bitmap, readableOnBlack(accentRaw), darken(darkRaw), key)
    }

    /** 검정 배경 위에서 너무 어둡지 않도록 명도 보정 */
    private fun readableOnBlack(color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        if (hsl[2] < 0.55f) hsl[2] = 0.62f
        if (hsl[2] > 0.85f) hsl[2] = 0.8f
        return ColorUtils.HSLToColor(hsl)
    }

    private fun darken(color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[2] = hsl[2].coerceIn(0.12f, 0.28f)
        return ColorUtils.HSLToColor(hsl)
    }

    companion object {
        const val MAX_SIZE = 768
    }
}
