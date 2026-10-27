package com.galaxy.standbypro.ui.standby

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galaxy.standbypro.data.AppSettings
import com.galaxy.standbypro.data.ClockStyle
import com.galaxy.standbypro.ui.theme.InterDisplay
import com.galaxy.standbypro.ui.theme.TabularNums
import com.galaxy.standbypro.ui.theme.accentColor
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.min

/** 모드 B: 전체 화면 시계. 위아래로 스와이프해 스타일 변경 */
@Composable
fun ClockPage(
    settings: AppSettings,
    onStyleChange: (ClockStyle) -> Unit,
    modifier: Modifier = Modifier,
    forcedStyle: ClockStyle? = null,
) {
    val styles = ClockStyle.entries
    if (forcedStyle != null) {
        ClockStyleContent(forcedStyle, settings, Modifier.fillMaxSize())
        return
    }
    val pager = rememberPagerState(initialPage = settings.clockStyle.ordinal) { styles.size }

    LaunchedEffect(pager.settledPage) {
        val chosen = styles[pager.settledPage]
        if (chosen != settings.clockStyle) onStyleChange(chosen)
    }
    LaunchedEffect(settings.clockStyle) {
        val target = settings.clockStyle.ordinal
        if (pager.currentPage != target && !pager.isScrollInProgress) pager.animateScrollToPage(target)
    }

    Box(modifier) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            ClockStyleContent(styles[page], settings, Modifier.fillMaxSize())
        }
        PagerDots(
            count = styles.size,
            current = pager.currentPage,
            visible = pager.isScrollInProgress,
            vertical = true,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
        )
        AnimatedVisibility(
            visible = pager.isScrollInProgress,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
        ) {
            Text(styles[pager.currentPage].label, color = LocalStandByColors.current.secondary, fontSize = 13.sp)
        }
    }
}

@Composable
fun ClockStyleContent(style: ClockStyle, s: AppSettings, modifier: Modifier = Modifier) {
    when (style) {
        ClockStyle.BIG_DIGITAL -> BigDigitalClock(s, modifier)
        ClockStyle.MINIMAL_ANALOG -> FullAnalogClock(s, DialStyle.MINIMAL, modifier)
        ClockStyle.CLASSIC_ANALOG -> FullAnalogClock(s, DialStyle.CLASSIC, modifier)
        ClockStyle.COLOR_DIGITAL -> ColorDigitalClock(s, modifier)
        ClockStyle.WORLD -> WorldClockFull(s, modifier)
        ClockStyle.OLED_SIMPLE -> OledSimpleClock(s, modifier)
    }
}

/** 샘플 문자열("88:88")이 주어진 영역에 꼭 맞는 글자 크기 (시간이 바뀌어도 크기 고정) */
@Composable
fun rememberFitFontSize(
    sample: String,
    weight: FontWeight,
    maxWidthPx: Float,
    maxHeightPx: Float,
): TextUnit {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(sample, weight, maxWidthPx, maxHeightPx, density) {
        val base = measurer.measure(
            sample,
            TextStyle(fontFamily = InterDisplay, fontWeight = weight, fontSize = 100.sp, lineHeight = 100.sp, fontFeatureSettings = TabularNums),
        )
        val w = base.size.width.coerceAtLeast(1)
        val h = base.size.height.coerceAtLeast(1)
        val scale = min(maxWidthPx / w, maxHeightPx / h)
        (100f * scale).coerceAtLeast(8f).sp
    }
}

@Composable
private fun BigDigitalClock(s: AppSettings, modifier: Modifier) {
    val colors = LocalStandByColors.current
    val context = LocalContext.current
    val now by rememberNow(Tick.MINUTE)
    val t = zoned(now)
    val is24 = use24Hour(context, s.hourFormat)
    val color = if (colors.night) colors.primary else accentColor(s.clockColorIndex)

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val fontSize = rememberFitFontSize("88:88", FontWeight.SemiBold, wPx * 0.94f, hPx * (if (s.showDate) 0.8f else 0.95f))
        Column(horizontalAlignment = Alignment.Start) {
            if (s.showDate) {
                Text(
                    text = dateFullText(t),
                    color = colors.secondary,
                    fontSize = (fontSize.value * 0.085f).sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .burnIn(seed = 11)
                        .padding(start = with(density) { (fontSize.toPx() * 0.04f).toDp() }),
                )
            }
            ClockText(timeText(t, is24), fontSize, color, Modifier.burnIn(seed = 12), weight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ColorDigitalClock(s: AppSettings, modifier: Modifier) {
    val colors = LocalStandByColors.current
    val context = LocalContext.current
    val now by rememberNow(Tick.MINUTE)
    val t = zoned(now)
    val is24 = use24Hour(context, s.hourFormat)
    // 기본(흰색)일 때는 오렌지로 칠해 '컬러' 느낌을 살림
    val base = if (s.clockColorIndex == 0) accentColor(1) else accentColor(s.clockColorIndex)
    val hourColor = if (colors.night) colors.primary else base
    val minuteColor = if (colors.night) colors.secondary else lerp(base, Color.White, 0.45f)

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val fontSize = rememberFitFontSize("88 88", FontWeight.Black, wPx * 0.92f, hPx * 0.86f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (s.showDate) {
                Text(
                    dateFullText(t),
                    color = hourColor.copy(alpha = 0.85f),
                    fontSize = (fontSize.value * 0.08f).sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.burnIn(21),
                )
            }
            Row(Modifier.burnIn(22), verticalAlignment = Alignment.CenterVertically) {
                ClockText(hourText(t, is24), fontSize, hourColor, weight = FontWeight.Black, letterSpacingEm = -0.04f)
                Spacer(Modifier.width(with(LocalDensity.current) { (fontSize.toPx() * 0.12f).toDp() }))
                ClockText(minuteText(t), fontSize, minuteColor, weight = FontWeight.Black, letterSpacingEm = -0.04f)
            }
        }
    }
}

@Composable
private fun FullAnalogClock(s: AppSettings, dial: DialStyle, modifier: Modifier) {
    val colors = LocalStandByColors.current
    val now by rememberNow(Tick.MINUTE)
    val t = zoned(now)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val landscape = maxWidth > maxHeight
        val clockSize: Dp = min(maxHeight.value, maxWidth.value).dp * 0.94f
        val gap: Dp = maxWidth * 0.05f
        if (landscape && s.showDate) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                AnalogClock(Modifier.size(clockSize).burnIn(31), dial, showSeconds = s.showSeconds)
                Spacer(Modifier.width(gap))
                Column(Modifier.burnIn(32)) {
                    Text(weekdayText(t), color = if (colors.night) colors.primary else colors.widgetAccent, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Text("${t.monthValue}월 ${t.dayOfMonth}일", color = colors.primary, fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = InterDisplay)
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AnalogClock(
                    Modifier.size(if (s.showDate) clockSize * 0.86f else clockSize).burnIn(31),
                    dial,
                    showSeconds = s.showSeconds,
                )
                if (s.showDate) {
                    Text(dateFullText(t), color = colors.secondary, fontSize = 16.sp, modifier = Modifier.burnIn(32))
                }
            }
        }
    }
}

@Composable
private fun OledSimpleClock(s: AppSettings, modifier: Modifier) {
    val colors = LocalStandByColors.current
    val context = LocalContext.current
    val now by rememberNow(Tick.MINUTE)
    val t = zoned(now)
    val is24 = use24Hour(context, s.hourFormat)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val hPx = constraints.maxHeight.toFloat()
        val wPx = constraints.maxWidth.toFloat()
        val fontSize = rememberFitFontSize("88:88", FontWeight.Thin, wPx * 0.45f, hPx * 0.3f)
        // 최소한의 픽셀만 켜고, 다른 요소보다 3배 넓게 이동
        Column(Modifier.burnIn(41, scale = 3f), horizontalAlignment = Alignment.CenterHorizontally) {
            ClockText(timeText(t, is24), fontSize, colors.secondary, weight = FontWeight.Thin)
            if (s.showDate) {
                Text(dateShortText(t), color = colors.tertiary, fontSize = (fontSize.value * 0.13f).sp)
            }
        }
    }
}

@Composable
private fun WorldClockFull(s: AppSettings, modifier: Modifier) {
    val zones = s.worldClockZones.ifEmpty { listOf(ZoneId.systemDefault().id) }.take(4)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val landscape = maxWidth > maxHeight
        val perRow = if (landscape) zones.size else 2
        val cell = if (landscape) min(maxWidth.value / zones.size * 0.78f, maxHeight.value * 0.62f).dp
        else min(maxWidth.value / 2f * 0.72f, maxHeight.value / 3f).dp
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            zones.chunked(perRow).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(cell * 0.18f)) {
                    row.forEachIndexed { i, id ->
                        WorldCity(id, cell, Modifier.burnIn(50 + rowIndex * 4 + i))
                    }
                }
                Spacer(Modifier.height(cell * 0.12f))
            }
        }
    }
}

@Composable
fun WorldCity(zoneId: String, dialSize: Dp, modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = LocalStandByColors.current
    val zone = remember(zoneId) { safeZone(zoneId) }
    val now by rememberNow(Tick.MINUTE)
    val here = zoned(now)
    val there = zoned(now, zone)
    val dayDiff = ChronoUnit.DAYS.between(here.toLocalDate(), there.toLocalDate())
    val day = when {
        dayDiff > 0 -> "내일"
        dayDiff < 0 -> "어제"
        else -> "오늘"
    }
    val offsetHours = (there.offset.totalSeconds - here.offset.totalSeconds) / 3600f
    val offsetText = when {
        offsetHours == 0f -> "+0시간"
        offsetHours % 1f == 0f -> "%+d시간".format(offsetHours.toInt())
        else -> "%+.1f시간".format(offsetHours)
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnalogClock(Modifier.size(dialSize), DialStyle.WORLD, zone = zone, showSeconds = false)
        Spacer(Modifier.height(if (compact) 4.dp else 10.dp))
        Text(
            cityName(zoneId),
            color = colors.primary,
            fontSize = if (compact) 13.sp else 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!compact) {
            Text("$day, $offsetText", color = colors.secondary, fontSize = 13.sp, maxLines = 1)
        }
    }
}

/** 스택/스타일 스와이프 시 잠깐 나타나는 점 표시 (아이폰 스택 인디케이터) */
@Composable
fun PagerDots(count: Int, current: Int, visible: Boolean, vertical: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalStandByColors.current
    AnimatedVisibility(visible = visible && count > 1, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val dot: @Composable (Int) -> Unit = { i ->
            Box(
                Modifier
                    .padding(3.dp)
                    .size(if (i == current) 7.dp else 5.dp)
                    .background(if (i == current) colors.primary else colors.tertiary, CircleShape),
            )
        }
        if (vertical) Column(horizontalAlignment = Alignment.CenterHorizontally) { repeat(count) { dot(it) } }
        else Row(verticalAlignment = Alignment.CenterVertically) { repeat(count) { dot(it) } }
    }
}
