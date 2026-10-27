package com.galaxy.standbypro.ui.standby

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.galaxy.standbypro.data.WeatherKind

enum class Glyph(val description: String) {
    PLAY("재생"), PAUSE("일시정지"), NEXT("다음 곡"), PREVIOUS("이전 곡"),
    SHUFFLE("셔플"), REPEAT("반복"), REPEAT_ONE("한 곡 반복"),
}

/** 직접 그린 미디어 버튼. 지원되지 않는 명령이면 흐리게 표시되고 눌리지 않는다. */
@Composable
fun GlyphButton(
    glyph: Glyph,
    size: Dp,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = glyph.description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(size * 0.18f)) {
            drawGlyph(glyph, if (enabled) color else color.copy(alpha = 0.28f))
        }
    }
}

fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val w = size.width
    val h = size.height
    fun p(x: Float, y: Float) = Offset(x * w, y * h)
    val round = Stroke(width = w * 0.07f, join = StrokeJoin.Round, cap = StrokeCap.Round)

    fun triangle(a: Offset, b: Offset, c: Offset) {
        val path = Path().apply {
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close()
        }
        drawPath(path, color)
        drawPath(path, color, style = round)
    }

    when (glyph) {
        Glyph.PLAY -> triangle(p(0.26f, 0.14f), p(0.26f, 0.86f), p(0.88f, 0.5f))
        Glyph.PAUSE -> {
            val cr = CornerRadius(w * 0.05f)
            drawRoundRect(color, p(0.2f, 0.14f), Size(w * 0.2f, h * 0.72f), cr)
            drawRoundRect(color, p(0.6f, 0.14f), Size(w * 0.2f, h * 0.72f), cr)
        }
        Glyph.NEXT -> {
            triangle(p(0.08f, 0.24f), p(0.08f, 0.76f), p(0.5f, 0.5f))
            triangle(p(0.5f, 0.24f), p(0.5f, 0.76f), p(0.92f, 0.5f))
        }
        Glyph.PREVIOUS -> {
            triangle(p(0.92f, 0.24f), p(0.92f, 0.76f), p(0.5f, 0.5f))
            triangle(p(0.5f, 0.24f), p(0.5f, 0.76f), p(0.08f, 0.5f))
        }
        Glyph.SHUFFLE -> {
            val line = Stroke(width = w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val a = Path().apply {
                moveTo(0.1f * w, 0.3f * h); lineTo(0.3f * w, 0.3f * h)
                cubicTo(0.55f * w, 0.3f * h, 0.5f * w, 0.7f * h, 0.75f * w, 0.7f * h); lineTo(0.88f * w, 0.7f * h)
            }
            val b = Path().apply {
                moveTo(0.1f * w, 0.7f * h); lineTo(0.3f * w, 0.7f * h)
                cubicTo(0.55f * w, 0.7f * h, 0.5f * w, 0.3f * h, 0.75f * w, 0.3f * h); lineTo(0.88f * w, 0.3f * h)
            }
            drawPath(a, color, style = line)
            drawPath(b, color, style = line)
            arrowHead(p(0.9f, 0.3f), color, w)
            arrowHead(p(0.9f, 0.7f), color, w)
        }
        Glyph.REPEAT, Glyph.REPEAT_ONE -> {
            val line = Stroke(width = w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val top = Path().apply {
                moveTo(0.14f * w, 0.56f * h); lineTo(0.14f * w, 0.42f * h)
                quadraticBezierTo(0.14f * w, 0.28f * h, 0.28f * w, 0.28f * h); lineTo(0.84f * w, 0.28f * h)
            }
            val bottom = Path().apply {
                moveTo(0.86f * w, 0.44f * h); lineTo(0.86f * w, 0.58f * h)
                quadraticBezierTo(0.86f * w, 0.72f * h, 0.72f * w, 0.72f * h); lineTo(0.16f * w, 0.72f * h)
            }
            drawPath(top, color, style = line)
            drawPath(bottom, color, style = line)
            arrowHead(p(0.86f, 0.28f), color, w)
            arrowHead(p(0.14f, 0.72f), color, w, left = true)
            if (glyph == Glyph.REPEAT_ONE) {
                drawLine(color, p(0.5f, 0.4f), p(0.5f, 0.6f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
                drawLine(color, p(0.5f, 0.4f), p(0.44f, 0.45f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
            }
        }
    }
}

private fun DrawScope.arrowHead(tip: Offset, color: Color, w: Float, left: Boolean = false) {
    val d = w * 0.12f
    val dir = if (left) 1f else -1f
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x + dir * d, tip.y - d * 0.8f)
        lineTo(tip.x + dir * d, tip.y + d * 0.8f)
        close()
    }
    drawPath(path, color)
}

/** 앨범 커버가 없을 때 쓰는 앱 자체 기본 아트워크 */
@Composable
fun DefaultArtwork(modifier: Modifier = Modifier, night: Boolean = false) {
    Canvas(modifier) {
        val top = if (night) Color(0xFF3A0905) else Color(0xFF3A3A4A)
        val bottom = if (night) Color(0xFF140303) else Color(0xFF131318)
        drawRect(Brush.linearGradient(listOf(top, bottom), start = Offset.Zero, end = Offset(size.width, size.height)))
        val note = if (night) Color(0xFF9E1A12) else Color(0xFF8E8E9A)
        val w = size.width
        val h = size.height
        // 음표: 두 개의 머리 + 기둥 + 들보
        drawOval(note, Offset(w * 0.28f, h * 0.6f), Size(w * 0.16f, h * 0.12f))
        drawOval(note, Offset(w * 0.56f, h * 0.54f), Size(w * 0.16f, h * 0.12f))
        drawLine(note, Offset(w * 0.425f, h * 0.66f), Offset(w * 0.425f, h * 0.3f), strokeWidth = w * 0.035f)
        drawLine(note, Offset(w * 0.705f, h * 0.6f), Offset(w * 0.705f, h * 0.24f), strokeWidth = w * 0.035f)
        val beam = Path().apply {
            moveTo(w * 0.408f, h * 0.29f); lineTo(w * 0.722f, h * 0.22f)
            lineTo(w * 0.722f, h * 0.3f); lineTo(w * 0.408f, h * 0.37f); close()
        }
        drawPath(beam, note)
    }
}

/** 날씨 아이콘 (직접 그림) */
fun DrawScope.drawWeatherIcon(kind: WeatherKind, isDay: Boolean, night: Boolean) {
    val w = size.width
    val h = size.height
    val sun = if (night) Color(0xFFE5261C) else Color(0xFFFFD60A)
    val cloud = if (night) Color(0xFF8C1610) else Color(0xFFE5E5EA)
    val cloudDark = if (night) Color(0xFF5A0E0A) else Color(0xFF8E8E93)
    val rain = if (night) Color(0xFFB81E15) else Color(0xFF64A8FF)
    val snow = if (night) Color(0xFFB81E15) else Color.White

    fun drawSunOrMoon(cx: Float, cy: Float, r: Float) {
        if (isDay) {
            drawCircle(sun, r, Offset(cx, cy))
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val c = kotlin.math.cos(a).toFloat()
                val s = kotlin.math.sin(a).toFloat()
                drawLine(sun, Offset(cx + c * r * 1.35f, cy + s * r * 1.35f), Offset(cx + c * r * 1.75f, cy + s * r * 1.75f), strokeWidth = r * 0.22f, cap = StrokeCap.Round)
            }
        } else {
            val moon = if (night) Color(0xFFE5261C) else Color(0xFFF2F2F7)
            val path = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r))
            }
            val cut = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(cx - r * 0.35f, cy - r * 1.15f, cx + r * 1.45f, cy + r * 0.65f))
            }
            val crescent = Path().apply { op(path, cut, androidx.compose.ui.graphics.PathOperation.Difference) }
            drawPath(crescent, moon)
        }
    }

    fun drawCloud(cx: Float, cy: Float, s: Float, color: Color) {
        drawCircle(color, s * 0.28f, Offset(cx - s * 0.22f, cy + s * 0.05f))
        drawCircle(color, s * 0.36f, Offset(cx + s * 0.05f, cy - s * 0.08f))
        drawCircle(color, s * 0.26f, Offset(cx + s * 0.3f, cy + s * 0.07f))
        drawRoundRect(color, Offset(cx - s * 0.5f, cy + s * 0.02f), Size(s * 1.06f, s * 0.31f), CornerRadius(s * 0.16f))
    }

    when (kind) {
        WeatherKind.CLEAR -> drawSunOrMoon(w * 0.5f, h * 0.5f, w * 0.22f)
        WeatherKind.PARTLY_CLOUDY -> {
            drawSunOrMoon(w * 0.38f, h * 0.38f, w * 0.17f)
            drawCloud(w * 0.55f, h * 0.6f, w * 0.62f, cloud)
        }
        WeatherKind.CLOUDY -> {
            drawCloud(w * 0.6f, h * 0.42f, w * 0.45f, cloudDark)
            drawCloud(w * 0.48f, h * 0.56f, w * 0.7f, cloud)
        }
        WeatherKind.FOG -> {
            for (i in 0 until 4) {
                val y = h * (0.3f + i * 0.14f)
                drawLine(cloud, Offset(w * (0.18f + (i % 2) * 0.06f), y), Offset(w * (0.82f - (i % 2) * 0.06f), y), strokeWidth = h * 0.06f, cap = StrokeCap.Round)
            }
        }
        WeatherKind.DRIZZLE, WeatherKind.RAIN -> {
            drawCloud(w * 0.5f, h * 0.4f, w * 0.7f, cloud)
            val n = if (kind == WeatherKind.RAIN) 3 else 2
            for (i in 0 until n) {
                val x = w * (0.32f + i * 0.17f)
                drawLine(rain, Offset(x, h * 0.66f), Offset(x - w * 0.06f, h * 0.84f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
            }
        }
        WeatherKind.SNOW -> {
            drawCloud(w * 0.5f, h * 0.4f, w * 0.7f, cloud)
            for (i in 0 until 3) {
                drawCircle(snow, w * 0.04f, Offset(w * (0.32f + i * 0.17f), h * (0.74f + (i % 2) * 0.08f)))
            }
        }
        WeatherKind.STORM -> {
            drawCloud(w * 0.5f, h * 0.38f, w * 0.7f, cloudDark)
            val bolt = Path().apply {
                moveTo(w * 0.52f, h * 0.55f); lineTo(w * 0.4f, h * 0.74f); lineTo(w * 0.5f, h * 0.74f)
                lineTo(w * 0.44f, h * 0.92f); lineTo(w * 0.62f, h * 0.68f); lineTo(w * 0.52f, h * 0.68f); close()
            }
            drawPath(bolt, sun)
        }
    }
}

/** 배터리 위젯용 번개 모양 */
fun DrawScope.drawBolt(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.58f, 0f); lineTo(w * 0.12f, h * 0.58f); lineTo(w * 0.46f, h * 0.58f)
        lineTo(w * 0.38f, h); lineTo(w * 0.88f, h * 0.4f); lineTo(w * 0.54f, h * 0.4f); close()
    }
    drawPath(path, color)
}
