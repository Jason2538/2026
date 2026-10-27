package com.galaxy.standbypro.ui.standby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import com.galaxy.standbypro.data.AppSettings
import com.galaxy.standbypro.data.HourFormat
import com.galaxy.standbypro.data.NightModeSetting
import com.galaxy.standbypro.ui.theme.StandByPalette
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sin

// ═══════════════════════════ 색상 (주간 / 야간) ═══════════════════════════

@Immutable
data class StandByColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val accent: Color,
    val widgetAccent: Color,
    val secondHand: Color,
    val dial: Color,
    val night: Boolean,
)

fun dayColors(accent: Color, widgetAccent: Color) = StandByColors(
    primary = Color(0xFFF2F2F7),
    secondary = StandByPalette.Gray1,
    tertiary = StandByPalette.Gray2,
    accent = accent,
    widgetAccent = widgetAccent,
    secondHand = StandByPalette.SecondHand,
    dial = StandByPalette.Gray6,
    night = false,
)

/** 아이폰 StandBy 야간 모드처럼 모든 요소를 붉은 단색으로 */
fun nightColors() = StandByColors(
    primary = StandByPalette.NightRed,
    secondary = StandByPalette.NightRedDim,
    tertiary = Color(0xFF4A0C08),
    accent = StandByPalette.NightRed,
    widgetAccent = StandByPalette.NightRed,
    secondHand = StandByPalette.NightRed,
    dial = Color(0xFF140403),
    night = true,
)

val LocalStandByColors = staticCompositionLocalOf { dayColors(Color.White, StandByPalette.Red) }

/** 사진·앨범 커버를 붉은 단색으로 바꾸는 필터 (야간 모드) */
val RedMonochromeFilter: ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.30f, 0.59f, 0.11f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

// ═══════════════════════════ 시간 ═══════════════════════════

/** 시스템 시각/시간대가 바뀌면 증가 → 모든 시계가 즉시 다시 계산 */
val LocalTimeVersion = staticCompositionLocalOf { 0 }

@Composable
fun TimeChangeObserver(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) TimeZone.setDefault(null)
                version++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    CompositionLocalProvider(LocalTimeVersion provides version, content = content)
}

enum class Tick { FRAME, SECOND, MINUTE }

/** 현재 시각(ms). FRAME = 부드러운 초침용, MINUTE = 디지털 시계용(분 경계에 맞춰 갱신) */
@Composable
fun rememberNow(tick: Tick): State<Long> {
    val version = LocalTimeVersion.current
    val state = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(tick, version) {
        when (tick) {
            Tick.FRAME -> while (true) {
                withFrameMillis { state.longValue = System.currentTimeMillis() }
            }
            Tick.SECOND -> while (true) {
                val now = System.currentTimeMillis()
                state.longValue = now
                delay(1000 - now % 1000 + 2)
            }
            Tick.MINUTE -> while (true) {
                val now = System.currentTimeMillis()
                state.longValue = now
                delay(60_000 - now % 60_000 + 5)
            }
        }
    }
    return state
}

fun use24Hour(context: Context, format: HourFormat): Boolean = when (format) {
    HourFormat.SYSTEM -> DateFormat.is24HourFormat(context)
    HourFormat.H12 -> false
    HourFormat.H24 -> true
}

fun zoned(millis: Long, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
    Instant.ofEpochMilli(millis).atZone(zone)

/** 아이폰 StandBy처럼 오전/오후 표시 없이 시:분 */
fun hourText(t: ZonedDateTime, is24: Boolean): String =
    if (is24) "%02d".format(t.hour) else (t.hour % 12).let { if (it == 0) 12 else it }.toString()

fun minuteText(t: ZonedDateTime): String = "%02d".format(t.minute)

fun timeText(t: ZonedDateTime, is24: Boolean): String = "${hourText(t, is24)}:${minuteText(t)}"

fun amPm(t: ZonedDateTime): String = if (t.hour < 12) "오전" else "오후"

private val dateFull = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)
private val dateShort = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
private val weekdayFmt = DateTimeFormatter.ofPattern("EEEE", Locale.KOREAN)

fun dateFullText(t: ZonedDateTime): String = dateFull.format(t)
fun dateShortText(t: ZonedDateTime): String = dateShort.format(t)
fun weekdayText(t: ZonedDateTime): String = weekdayFmt.format(t)

val CityNames = mapOf(
    "Asia/Seoul" to "서울",
    "Asia/Tokyo" to "도쿄",
    "Asia/Shanghai" to "상하이",
    "Asia/Hong_Kong" to "홍콩",
    "Asia/Taipei" to "타이베이",
    "Asia/Singapore" to "싱가포르",
    "Asia/Bangkok" to "방콕",
    "Asia/Ho_Chi_Minh" to "호찌민",
    "Asia/Dubai" to "두바이",
    "Asia/Kolkata" to "뉴델리",
    "Europe/London" to "런던",
    "Europe/Paris" to "파리",
    "Europe/Berlin" to "베를린",
    "Europe/Rome" to "로마",
    "Europe/Madrid" to "마드리드",
    "Europe/Moscow" to "모스크바",
    "America/New_York" to "뉴욕",
    "America/Chicago" to "시카고",
    "America/Denver" to "덴버",
    "America/Los_Angeles" to "로스앤젤레스",
    "America/Vancouver" to "밴쿠버",
    "America/Toronto" to "토론토",
    "America/Sao_Paulo" to "상파울루",
    "Pacific/Honolulu" to "호놀룰루",
    "Pacific/Auckland" to "오클랜드",
    "Australia/Sydney" to "시드니",
    "Africa/Cairo" to "카이로",
    "UTC" to "UTC",
)

fun cityName(zoneId: String): String =
    CityNames[zoneId] ?: zoneId.substringAfterLast('/').replace('_', ' ')

fun safeZone(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrDefault(ZoneId.systemDefault())

// ═══════════════════════════ 번인 방지: 픽셀 이동 ═══════════════════════════

@Immutable
data class BurnInConfig(
    val enabled: Boolean,
    val maxShift: Dp,
    val smooth: Boolean,
    val intervalSec: Int,
)

class BurnInState(val config: BurnInConfig, val step: MutableIntState)

val LocalBurnIn = compositionLocalOf<BurnInState?> { null }

@Composable
fun ProvideBurnIn(config: BurnInConfig, content: @Composable () -> Unit) {
    val step = remember { mutableIntStateOf(0) }
    LaunchedEffect(config.enabled, config.intervalSec) {
        if (!config.enabled) {
            step.intValue = 0
            return@LaunchedEffect
        }
        while (true) {
            delay(config.intervalSec * 1000L)
            step.intValue = step.intValue + 1
        }
    }
    val state = remember(config) { BurnInState(config, step) }
    CompositionLocalProvider(LocalBurnIn provides state, content = content)
}

/**
 * 단계(step)마다 원 안의 서로 다른 지점으로 이동. 이전 위치에 누적하지 않고
 * 매번 '중심 기준 절대 위치'를 계산하므로 오래 켜 두어도 한쪽으로 밀리지 않는다.
 * 황금각으로 퍼뜨려 같은 지점에 자주 머물지 않게 한다.
 */
fun burnInOffset(step: Int, seed: Int, max: Float): Pair<Float, Float> {
    if (max <= 0f) return 0f to 0f
    val golden = 2.39996323
    val angle = step * golden + seed * 1.7
    val ring = (((step * 5 + seed * 3) % 4) + 1) / 4f
    return (cos(angle) * max * ring).toFloat() to (sin(angle) * max * ring).toFloat()
}

/** 이 요소를 번인 방지 패턴에 따라 이동. seed가 다르면 서로 다른 방향으로 움직인다. */
@Composable
fun Modifier.burnIn(seed: Int, scale: Float = 1f): Modifier {
    val state = LocalBurnIn.current ?: return this
    val cfg = state.config
    val target = if (cfg.enabled) burnInOffset(state.step.intValue, seed, cfg.maxShift.value * scale) else (0f to 0f)
    val spec: AnimationSpec<Float> =
        if (cfg.smooth) tween(durationMillis = 2_400, easing = FastOutSlowInEasing) else snap()
    val x by animateFloatAsState(target.first, spec, label = "burnInX")
    val y by animateFloatAsState(target.second, spec, label = "burnInY")
    return this.offset { IntOffset(x.dp.roundToPx(), y.dp.roundToPx()) }
}

// ═══════════════════════════ 야간 모드 감지 ═══════════════════════════

fun isInNightWindow(millis: Long, startHour: Int, endHour: Int): Boolean {
    val h = zoned(millis).hour
    return if (startHour == endHour) false
    else if (startHour < endHour) h in startHour until endHour
    else h >= startHour || h < endHour
}

/**
 * 자동: 조도 센서가 있으면 주변이 어두울 때(히스테리시스 적용), 없으면 설정한 시간대에 야간 모드.
 * 센서는 화면이 보일 때(STARTED)만 듣는다.
 */
@Composable
fun rememberNightActive(s: AppSettings): Boolean {
    when (s.nightMode) {
        NightModeSetting.OFF -> return false
        NightModeSetting.ALWAYS -> return true
        NightModeSetting.AUTO -> Unit
    }
    val now by rememberNow(Tick.MINUTE)
    val timeNight = isInNightWindow(now, s.nightStartHour, s.nightEndHour)
    val context = LocalContext.current
    val sensorManager = remember(context) { context.getSystemService(SensorManager::class.java) }
    val lightSensor = remember(sensorManager) { sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT) }
    if (!s.nightUseLightSensor || sensorManager == null || lightSensor == null) return timeNight

    var luxNight by remember { mutableStateOf<Boolean?>(null) }
    val threshold = s.nightLuxThreshold
    LifecycleStartEffect(lightSensor, threshold) {
        var belowSince = 0L
        var aboveSince = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val lux = event.values[0]
                val t = SystemClock.elapsedRealtime()
                if (luxNight == null) luxNight = lux <= threshold
                if (lux <= threshold) {
                    aboveSince = 0L
                    if (belowSince == 0L) belowSince = t
                    if (luxNight != true && t - belowSince > 6_000) luxNight = true
                } else if (lux > threshold * 2f + 2f) {
                    belowSince = 0L
                    if (aboveSince == 0L) aboveSince = t
                    if (luxNight != false && t - aboveSince > 4_000) luxNight = false
                } else {
                    belowSince = 0L
                    aboveSince = 0L
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        onStopOrDispose { sensorManager.unregisterListener(listener) }
    }
    return luxNight ?: timeNight
}
