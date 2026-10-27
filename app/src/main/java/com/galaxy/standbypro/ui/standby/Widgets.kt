package com.galaxy.standbypro.ui.standby

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galaxy.standbypro.data.AppSettings
import com.galaxy.standbypro.data.BatteryInfo
import com.galaxy.standbypro.data.CalendarEvent
import com.galaxy.standbypro.data.EventsState
import com.galaxy.standbypro.data.WeatherState
import com.galaxy.standbypro.data.WidgetType
import com.galaxy.standbypro.music.NowPlaying
import com.galaxy.standbypro.ui.theme.InterDisplay
import com.galaxy.standbypro.ui.theme.StandByPalette
import com.galaxy.standbypro.ui.theme.TabularNums
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.min

/** 위젯이 화면 밖(설정 열기, 권한 요청, 음악 제어)과 소통하는 통로 */
interface StandByActions {
    fun playPause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun openSettings()
    fun requestCalendarPermission()
    fun playLocal(index: Int)
    fun toggleShuffle()
    fun cycleRepeat()
}

@Immutable
class WidgetData(
    val settings: AppSettings,
    val battery: BatteryInfo,
    val weather: WeatherState,
    val events: EventsState,
    val nowPlaying: NowPlaying?,
    val hideMusicDetails: Boolean,
)

@Composable
fun WidgetContent(type: WidgetType, data: WidgetData, actions: StandByActions, modifier: Modifier = Modifier) {
    val s = data.settings
    when (type) {
        WidgetType.ANALOG_CLOCK -> Box(modifier, contentAlignment = Alignment.Center) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AnalogClock(Modifier.size(min(maxWidth.value, maxHeight.value).dp * 0.94f), DialStyle.WIDGET, showSeconds = s.showSeconds)
            }
        }
        WidgetType.DIGITAL_CLOCK -> DigitalClockWidget(s, modifier)
        WidgetType.CALENDAR -> CalendarWidget(modifier)
        WidgetType.TODAY -> TodayWidget(modifier)
        WidgetType.WORLD_CLOCK -> WorldClockWidget(s, modifier)
        WidgetType.BATTERY -> BatteryWidget(data.battery, modifier)
        WidgetType.CHARGING -> ChargingWidget(data.battery, modifier)
        WidgetType.WEATHER -> WeatherWidget(data.weather, modifier)
        WidgetType.NOW_PLAYING -> NowPlayingWidget(data.nowPlaying, data.hideMusicDetails, actions, modifier)
        WidgetType.EVENTS -> EventsWidget(data.events, actions, modifier)
    }
}

// ─────────────────────────── 디지털 시계 ───────────────────────────
@Composable
private fun DigitalClockWidget(s: AppSettings, modifier: Modifier) {
    val colors = LocalStandByColors.current
    val context = LocalContext.current
    val now by rememberNow(Tick.MINUTE)
    val t = zoned(now)
    val is24 = use24Hour(context, s.hourFormat)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val fs = rememberFitFontSize("88:88", FontWeight.SemiBold, constraints.maxWidth * 0.92f, constraints.maxHeight * 0.55f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ClockText(timeText(t, is24), fs, if (colors.night) colors.primary else colors.primary)
            if (s.showDate) {
                Text(dateFullText(t), color = colors.secondary, fontSize = (fs.value * 0.13f).sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ─────────────────────────── 달력 ───────────────────────────
@Composable
private fun CalendarWidget(modifier: Modifier) {
    val colors = LocalStandByColors.current
    val now by rememberNow(Tick.MINUTE)
    val today: LocalDate = zoned(now).toLocalDate()
    val first = today.withDayOfMonth(1)
    val offset = first.dayOfWeek.value % 7 // 일요일 시작
    val days = today.lengthOfMonth()
    val rows = ceil((offset + days) / 7.0).toInt()
    val weekdays = listOf("일", "월", "화", "수", "목", "금", "토")

    BoxWithConstraints(modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
        val cellW = maxWidth / 7
        val cellH = maxHeight / (rows + 1.9f)
        val fs = min(cellW.value * 0.42f, cellH.value * 0.5f).sp
        Column {
            Text(
                "${today.monthValue}월",
                color = colors.widgetAccent,
                fontSize = fs * 1.1f,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = cellW * 0.2f).height(cellH * 0.95f),
            )
            Row(Modifier.height(cellH * 0.8f)) {
                weekdays.forEachIndexed { i, d ->
                    Text(
                        d,
                        modifier = Modifier.width(cellW),
                        textAlign = TextAlign.Center,
                        color = if (i == 0) colors.widgetAccent.copy(alpha = 0.75f) else colors.secondary,
                        fontSize = fs * 0.78f,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            for (r in 0 until rows) {
                Row(Modifier.height(cellH)) {
                    for (c in 0 until 7) {
                        val day = r * 7 + c - offset + 1
                        Box(Modifier.width(cellW).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            if (day in 1..days) {
                                val isToday = day == today.dayOfMonth
                                if (isToday) {
                                    Box(
                                        Modifier
                                            .size(min(cellW.value, cellH.value).dp * 0.9f)
                                            .background(colors.widgetAccent, CircleShape),
                                    )
                                }
                                Text(
                                    day.toString(),
                                    color = when {
                                        isToday -> if (colors.night) Color.Black else Color.White
                                        c == 0 -> colors.secondary
                                        else -> colors.primary
                                    },
                                    fontSize = fs,
                                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                                    fontFamily = InterDisplay,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────── 오늘 날짜 ───────────────────────────
@Composable
private fun TodayWidget(modifier: Modifier) {
    val colors = LocalStandByColors.current
    val now by rememberNow(Tick.MINUTE)
    val t = zoned(now)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val big = (maxHeight.value * 0.42f).coerceAtMost(maxWidth.value * 0.5f).sp
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(weekdayText(t), color = colors.widgetAccent, fontSize = big * 0.2f, fontWeight = FontWeight.Bold)
            Text(
                t.dayOfMonth.toString(),
                color = colors.primary,
                fontSize = big,
                lineHeight = big,
                fontWeight = FontWeight.Medium,
                fontFamily = InterDisplay,
            )
            Text("${t.year}년 ${t.monthValue}월", color = colors.secondary, fontSize = big * 0.16f, fontWeight = FontWeight.Medium)
        }
    }
}

// ─────────────────────────── 세계 시계 ───────────────────────────
@Composable
private fun WorldClockWidget(s: AppSettings, modifier: Modifier) {
    val zones = s.worldClockZones.ifEmpty { listOf("Asia/Seoul") }.take(4)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val cols = if (zones.size == 1) 1 else 2
        val rows = ceil(zones.size / cols.toDouble()).toInt()
        val dial: Dp = min(maxWidth.value / cols, maxHeight.value / rows * 0.72f).dp * 0.7f
        Column(verticalArrangement = Arrangement.spacedBy(dial * 0.12f), horizontalAlignment = Alignment.CenterHorizontally) {
            zones.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(dial * 0.3f)) {
                    row.forEach { id -> WorldCity(id, dial, compact = true) }
                }
            }
        }
    }
}

// ─────────────────────────── 배터리 ───────────────────────────
@Composable
private fun BatteryWidget(b: BatteryInfo, modifier: Modifier) {
    val colors = LocalStandByColors.current
    val ringColor = when {
        colors.night -> colors.primary
        b.levelPercent <= 20 && !b.isPluggedIn -> StandByPalette.Red
        else -> StandByPalette.Green
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ring = min(maxWidth.value, maxHeight.value * 0.78f).dp * 0.82f
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    progressRing(b.levelPercent / 100f, ringColor, colors.tertiary.copy(alpha = 0.35f), size.minDimension * 0.08f)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (b.isPluggedIn) {
                        Canvas(Modifier.size(ring * 0.16f, ring * 0.22f)) { drawBolt(ringColor) }
                    }
                    Text(
                        "${b.levelPercent}%",
                        color = colors.primary,
                        fontSize = (ring.value * 0.22f).sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = InterDisplay,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (b.isPluggedIn) "${b.plug.label} · ${b.speedLabel}" else "배터리 사용 중",
                color = colors.secondary,
                fontSize = 15.sp,
            )
        }
    }
}

// ─────────────────────────── 충전 상태 ───────────────────────────
@Composable
private fun ChargingWidget(b: BatteryInfo, modifier: Modifier) {
    val colors = LocalStandByColors.current
    BoxWithConstraints(modifier.padding(16.dp), contentAlignment = Alignment.CenterStart) {
        val big = (maxHeight.value * 0.2f).coerceAtMost(maxWidth.value * 0.22f).sp
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when {
                    !b.isPluggedIn -> "충전기 연결 안 됨"
                    b.isFull -> "완충됨"
                    b.isCharging -> "충전 중"
                    else -> "연결됨"
                },
                color = if (colors.night) colors.primary else StandByPalette.Green,
                fontSize = big * 0.32f,
                fontWeight = FontWeight.Bold,
            )
            val watts = b.watts
            Text(
                if (watts != null && b.isPluggedIn) "%.1fW".format(watts) else "${b.levelPercent}%",
                color = colors.primary,
                fontSize = big,
                lineHeight = big,
                fontWeight = FontWeight.SemiBold,
                fontFamily = InterDisplay,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = TabularNums),
            )
            val details = buildList {
                add("방식" to b.plug.label)
                if (b.isPluggedIn) add("속도" to b.speedLabel)
                b.currentMa?.let { add("전류" to "${it} mA") }
                if (b.voltageMv > 0) add("전압" to "%.2f V".format(b.voltageMv / 1000f))
                if (b.temperatureC > 0f) add("온도" to "%.1f°C".format(b.temperatureC))
                b.timeToFullMs?.let { ms ->
                    val m = ms / 60_000
                    add("완충까지" to if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분")
                }
            }
            details.take(5).forEach { (k, v) ->
                Row {
                    Text(k, color = colors.secondary, fontSize = big * 0.24f, modifier = Modifier.width((big.value * 1.1f).dp))
                    Text(v, color = colors.primary, fontSize = big * 0.24f, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ─────────────────────────── 날씨 ───────────────────────────
@Composable
private fun WeatherWidget(state: WeatherState, modifier: Modifier) {
    val colors = LocalStandByColors.current
    BoxWithConstraints(modifier.padding(12.dp), contentAlignment = Alignment.Center) {
        val big = (maxHeight.value * 0.3f).coerceAtMost(maxWidth.value * 0.3f).sp
        val info = when (state) {
            is WeatherState.Ready -> state.info
            is WeatherState.Error -> state.last
            else -> null
        }
        when {
            state is WeatherState.NotConfigured -> CenterNote("날씨", "설정 > 위젯에서 지역을 선택하세요")
            info == null && state is WeatherState.Loading -> CenterNote("날씨", "불러오는 중…")
            info == null -> CenterNote("날씨", "네트워크에 연결되면 표시됩니다")
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(info.city, color = colors.primary, fontSize = big * 0.22f, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size((big.value * 1.1f).dp)) { drawWeatherIcon(info.kind, info.isDay, colors.night) }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${Math.round(info.temperatureC)}°",
                        color = colors.primary,
                        fontSize = big,
                        lineHeight = big,
                        fontWeight = FontWeight.Light,
                        fontFamily = InterDisplay,
                    )
                }
                Text(info.description, color = colors.primary, fontSize = big * 0.2f, fontWeight = FontWeight.Medium)
                if (info.highC != null && info.lowC != null) {
                    Text(
                        "최고 ${Math.round(info.highC)}° 최저 ${Math.round(info.lowC)}°",
                        color = colors.secondary,
                        fontSize = big * 0.17f,
                    )
                }
            }
        }
    }
}

// ─────────────────────────── 재생 중인 음악 ───────────────────────────
@Composable
private fun NowPlayingWidget(np: NowPlaying?, hideDetails: Boolean, actions: StandByActions, modifier: Modifier) {
    val colors = LocalStandByColors.current
    if (np == null) {
        Box(modifier, contentAlignment = Alignment.Center) { CenterNote("음악", "재생 중인 음악 없음") }
        return
    }
    BoxWithConstraints(modifier.padding(12.dp), contentAlignment = Alignment.Center) {
        val art = min(maxWidth.value * 0.5f, maxHeight.value * 0.52f).dp
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(art).clip(RoundedCornerShape(art * 0.1f))) {
                val bmp = np.artwork?.bitmap
                if (bmp != null && !hideDetails) {
                    val image = remember(bmp) { bmp.asImageBitmap() }
                    Image(
                        image, contentDescription = "앨범 커버", contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        colorFilter = if (colors.night) RedMonochromeFilter else null,
                    )
                } else {
                    DefaultArtwork(Modifier.fillMaxSize(), night = colors.night)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (hideDetails) "음악 재생 중" else np.title.ifBlank { "알 수 없는 곡" },
                color = colors.primary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            Text(
                if (hideDetails) np.appLabel else np.artist,
                color = colors.secondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GlyphButton(Glyph.PREVIOUS, 36.dp, colors.primary, np.canPrevious, actions::previous)
                GlyphButton(if (np.isPlaying) Glyph.PAUSE else Glyph.PLAY, 44.dp, colors.primary, np.canPlayPause, actions::playPause)
                GlyphButton(Glyph.NEXT, 36.dp, colors.primary, np.canNext, actions::next)
            }
        }
    }
}

// ─────────────────────────── 일정 ───────────────────────────
@Composable
private fun EventsWidget(state: EventsState, actions: StandByActions, modifier: Modifier) {
    val colors = LocalStandByColors.current
    val now by rememberNow(Tick.MINUTE)
    val t = zoned(now)
    Column(modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text(weekdayText(t), color = colors.widgetAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("${t.monthValue}월 ${t.dayOfMonth}일", color = colors.primary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        when (state) {
            is EventsState.NoPermission -> {
                Text("일정을 보려면 캘린더 권한이 필요합니다", color = colors.secondary, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "권한 허용",
                    color = colors.widgetAccent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { actions.requestCalendarPermission() }
                        .padding(vertical = 6.dp),
                )
            }
            is EventsState.Ready -> {
                if (state.events.isEmpty()) {
                    Text("예정된 일정 없음", color = colors.secondary, fontSize = 16.sp)
                } else {
                    state.events.take(4).forEach { e -> EventRow(e, t.toLocalDate()) }
                }
            }
        }
    }
}

@Composable
private fun EventRow(e: CalendarEvent, today: LocalDate) {
    val colors = LocalStandByColors.current
    val begin = zoned(e.begin)
    val dayDiff = ChronoUnit.DAYS.between(today, begin.toLocalDate())
    val whenText = buildString {
        if (dayDiff == 1L) append("내일 ")
        if (e.allDay) append("하루 종일") else append("${amPm(begin)} ${timeText(begin, false)}")
    }
    val bar = if (colors.night) colors.primary else Color(e.color or 0xFF000000.toInt())
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(34.dp).background(bar, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(e.title, color = colors.primary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(whenText, color = colors.secondary, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
fun CenterNote(title: String, message: String, modifier: Modifier = Modifier) {
    val colors = LocalStandByColors.current
    Column(modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = colors.primary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(message, color = colors.secondary, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

/** 위젯 영역 크기에 맞춘 dp 계산 도우미 */
@Composable
fun Float.pxToDp(): Dp = with(LocalDensity.current) { this@pxToDp.toDp() }
