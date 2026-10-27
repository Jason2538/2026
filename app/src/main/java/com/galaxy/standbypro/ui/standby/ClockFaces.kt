package com.galaxy.standbypro.ui.standby

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.galaxy.standbypro.ui.theme.InterDisplay
import com.galaxy.standbypro.ui.theme.TabularNums
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class DialStyle {
    /** 눈금만 있는 미니멀 다이얼 */
    MINIMAL,
    /** 숫자 1~12 + 분 눈금 (전체 화면 클래식) */
    CLASSIC,
    /** 위젯용: 어두운 회색 원판 + 숫자 */
    WIDGET,
    /** 세계 시계: 낮(밝은 원판) / 밤(어두운 원판) 자동 */
    WORLD,
}

/**
 * 아날로그 시계. 정적인 원판(눈금/숫자)과 움직이는 바늘을 서로 다른 Canvas로 나눠
 * 매 프레임 바늘만 다시 그린다(60fps에서도 가볍게).
 */
@Composable
fun AnalogClock(
    modifier: Modifier = Modifier,
    style: DialStyle,
    zone: ZoneId = ZoneId.systemDefault(),
    showSeconds: Boolean = true,
    smoothSeconds: Boolean = true,
) {
    val colors = LocalStandByColors.current
    val tick = when {
        !showSeconds -> Tick.SECOND
        smoothSeconds && !colors.night -> Tick.FRAME
        else -> Tick.SECOND
    }
    // 바늘용 시각: 여기서 읽지 않고 Canvas 그리기 단계에서만 읽는다 (재구성 방지)
    val nowState = rememberNow(tick)
    val minuteNow by rememberNow(Tick.MINUTE)
    val textMeasurer = rememberTextMeasurer(cacheSize = 24)

    // 세계 시계는 해당 도시의 낮/밤에 따라 원판 색이 바뀐다
    val isDayAtZone = style == DialStyle.WORLD && zoned(minuteNow, zone).hour in 6..17

    val dialFill: Color? = when (style) {
        DialStyle.WIDGET -> colors.dial
        DialStyle.WORLD -> if (colors.night) colors.dial else if (isDayAtZone) Color(0xFFF2F2F7) else Color(0xFF2C2C2E)
        else -> null
    }
    val handColor = when {
        style == DialStyle.WORLD && !colors.night && isDayAtZone -> Color.Black
        else -> colors.primary
    }
    val markColor = when {
        style == DialStyle.WORLD && !colors.night && isDayAtZone -> Color(0xFF3A3A3C)
        else -> colors.secondary
    }

    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        // ── 원판 (시간과 무관 → 크기/색이 바뀔 때만 다시 그림) ──
        Canvas(Modifier.fillMaxSize()) {
            val r = min(size.width, size.height) / 2f
            val c = center
            if (dialFill != null) drawCircle(dialFill, radius = r, center = c)

            when (style) {
                DialStyle.MINIMAL -> {
                    for (i in 0 until 60) {
                        val major = i % 5 == 0
                        tickMark(c, r * 0.97f, if (major) r * 0.09f else r * 0.035f, i * 6f,
                            if (major) r * 0.022f else r * 0.008f,
                            if (major) colors.primary.copy(alpha = 0.9f) else colors.tertiary)
                    }
                }
                DialStyle.CLASSIC, DialStyle.WIDGET -> {
                    val inset = if (style == DialStyle.WIDGET) 0.9f else 0.98f
                    for (i in 0 until 60) {
                        val major = i % 5 == 0
                        tickMark(c, r * inset, if (major) r * 0.06f else r * 0.03f, i * 6f,
                            if (major) r * 0.016f else r * 0.007f,
                            if (major) colors.primary else colors.tertiary)
                    }
                    val numberRadius = r * (if (style == DialStyle.WIDGET) 0.66f else 0.77f)
                    val fontSize: TextUnit = (r * (if (style == DialStyle.WIDGET) 0.2f else 0.15f)).toSp()
                    val textStyle = TextStyle(
                        color = colors.primary,
                        fontFamily = InterDisplay,
                        fontWeight = FontWeight.Medium,
                        fontSize = fontSize,
                        fontFeatureSettings = TabularNums,
                    )
                    for (n in 1..12) {
                        val a = Math.toRadians(n * 30.0 - 90.0)
                        val layout = textMeasurer.measure(n.toString(), textStyle)
                        val p = Offset(
                            c.x + (cos(a) * numberRadius).toFloat() - layout.size.width / 2f,
                            c.y + (sin(a) * numberRadius).toFloat() - layout.size.height / 2f,
                        )
                        drawText(layout, topLeft = p)
                    }
                }
                DialStyle.WORLD -> {
                    for (i in 0 until 12) {
                        tickMark(c, r * 0.9f, r * 0.08f, i * 30f, r * 0.03f, markColor)
                    }
                }
            }
        }

        // ── 바늘 (시간 State를 draw 단계에서만 읽음 → 재구성 없이 다시 그리기만) ──
        Canvas(Modifier.fillMaxSize()) {
            val now = nowState.value
            val t = zoned(now, zone)
            val r = min(size.width, size.height) / 2f
            val c = center
            val ms = (now % 1000).toFloat()
            val sec = t.second + if (tick == Tick.FRAME) ms / 1000f else 0f
            val minute = t.minute + sec / 60f
            val hour = (t.hour % 12) + minute / 60f

            val wide = style == DialStyle.WORLD
            hand(c, hour * 30f, r * 0.5f, r * (if (wide) 0.085f else 0.062f), r * 0.13f, handColor)
            hand(c, minute * 6f, r * 0.78f, r * (if (wide) 0.07f else 0.048f), r * 0.13f, handColor)
            drawCircle(handColor, radius = r * 0.05f, center = c)

            if (showSeconds && !wide) {
                rotate(sec * 6f, c) {
                    drawLine(
                        colors.secondHand, Offset(c.x, c.y + r * 0.16f), Offset(c.x, c.y - r * 0.9f),
                        strokeWidth = r * 0.014f, cap = StrokeCap.Round,
                    )
                }
                drawCircle(colors.secondHand, radius = r * 0.034f, center = c)
                drawCircle(Color.Black, radius = r * 0.013f, center = c)
            } else {
                drawCircle(Color.Black, radius = r * 0.018f, center = c)
            }
        }
    }
}

private fun DrawScope.tickMark(center: Offset, outer: Float, length: Float, angleDeg: Float, width: Float, color: Color) {
    val a = Math.toRadians(angleDeg - 90.0)
    val ca = cos(a).toFloat()
    val sa = sin(a).toFloat()
    drawLine(
        color,
        Offset(center.x + ca * (outer - length), center.y + sa * (outer - length)),
        Offset(center.x + ca * outer, center.y + sa * outer),
        strokeWidth = width,
        cap = StrokeCap.Round,
    )
}

/** 아이폰 스타일 바늘: 중심부 가는 축 + 바깥쪽 굵고 둥근 막대 */
private fun DrawScope.hand(center: Offset, angleDeg: Float, length: Float, width: Float, stem: Float, color: Color) {
    rotate(angleDeg, center) {
        drawLine(color, center, Offset(center.x, center.y - stem), strokeWidth = width * 0.42f, cap = StrokeCap.Round)
        drawLine(
            color, Offset(center.x, center.y - stem), Offset(center.x, center.y - length),
            strokeWidth = width, cap = StrokeCap.Round,
        )
    }
}

/** 원형 진행 링 (배터리 위젯 등) */
fun DrawScope.progressRing(fraction: Float, color: Color, track: Color, strokeWidth: Float) {
    val inset = strokeWidth / 2f
    val arcSize = androidx.compose.ui.geometry.Size(size.width - strokeWidth, size.height - strokeWidth)
    drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(strokeWidth))
    drawArc(
        color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize,
        style = Stroke(strokeWidth, cap = StrokeCap.Round),
    )
}

/** 큰 숫자 시계 텍스트 (Inter Display, 고정폭 숫자) */
@Composable
fun ClockText(
    text: String,
    fontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.SemiBold,
    letterSpacingEm: Float = -0.02f,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        style = TextStyle(
            fontFamily = InterDisplay,
            fontWeight = weight,
            fontSize = fontSize,
            lineHeight = fontSize,
            letterSpacing = letterSpacingEm.em,
            fontFeatureSettings = TabularNums,
        ),
    )
}
