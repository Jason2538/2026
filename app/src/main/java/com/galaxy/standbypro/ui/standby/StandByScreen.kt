package com.galaxy.standbypro.ui.standby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.standbypro.data.AppSettings
import com.galaxy.standbypro.data.ClockStyle
import com.galaxy.standbypro.data.StandByPage
import com.galaxy.standbypro.ui.theme.StandByMaterialTheme
import com.galaxy.standbypro.ui.theme.accentColor
import kotlinx.coroutines.delay
import kotlin.math.min

/** StandBy를 띄우는 쪽(Activity 또는 DreamService)이 제공하는 기능 */
interface StandByHost {
    val isDream: Boolean
    /** 0..1, 음수면 시스템 밝기 사용. 현재 창에만 적용되며 시스템 설정은 바꾸지 않음 */
    fun setWindowBrightness(value: Float)
    fun setKeepScreenOn(on: Boolean)
    fun screenOff()
    fun openSettings()
    fun requestCalendarPermission()
    fun isDeviceLocked(): Boolean
    fun close()
}

@Composable
fun StandByRoot(vm: StandByViewModel, host: StandByHost) {
    val loaded by vm.settings.collectAsStateWithLifecycle()
    StandByMaterialTheme {
        val s = loaded
        if (s == null) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        } else {
            TimeChangeObserver { StandByContent(s, vm, host) }
        }
    }
}

@Composable
private fun StandByContent(s: AppSettings, vm: StandByViewModel, host: StandByHost) {
    val context = LocalContext.current
    val battery by vm.battery.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val externalAccess by vm.externalAccess.collectAsStateWithLifecycle()
    val localCount by vm.localTrackCount.collectAsStateWithLifecycle()

    // ── 잠금 상태 (잠금 화면에서 음악 정보 숨기기 옵션용) ──
    var locked by remember { mutableStateOf(host.isDeviceLocked()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                locked = host.isDeviceLocked()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        locked = host.isDeviceLocked()
        vm.refreshAccess()
    }
    val hideMusicDetails = s.hideMusicWhenLocked && locked

    // ── 야간 모드 / 색 ──
    val night = rememberNightActive(s)
    val colors = remember(night, s.clockColorIndex, s.widgetAccentIndex) {
        if (night) nightColors() else dayColors(accentColor(s.clockColorIndex), accentColor(s.widgetAccentIndex))
    }

    // ── 사용자 조작 추적 (어둡게/화면 끄기/커버 축소 타이머 기준) ──
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dimmed by remember { mutableStateOf(false) }
    var screenOff by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<StandByPage?>(null) }

    LaunchedEffect(lastInteraction, s.dimAfterMin, s.burnInEnabled) {
        dimmed = false
        if (s.dimAfterMin > 0) {
            delay(s.dimAfterMin * 60_000L)
            dimmed = true
        }
    }
    LaunchedEffect(lastInteraction, s.screenOffAfterMin) {
        screenOff = false
        if (s.screenOffAfterMin > 0) {
            delay(s.screenOffAfterMin * 60_000L)
            screenOff = true
            host.screenOff()
        }
    }
    LaunchedEffect(controlsVisible, lastInteraction) {
        if (controlsVisible) {
            delay(5_000)
            controlsVisible = false
        }
    }

    // ── 창 밝기 / 화면 유지 (현재 창에만 적용) ──
    LaunchedEffect(night, s.useCustomBrightness, s.brightness, s.nightBrightness, screenOff) {
        host.setWindowBrightness(
            when {
                screenOff -> 0f
                night -> s.nightBrightness
                s.useCustomBrightness -> s.brightness
                else -> -1f
            },
        )
    }
    LaunchedEffect(s.keepScreenOn, screenOff) { host.setKeepScreenOn(s.keepScreenOn && !screenOff) }

    // ── 페이지 ──
    val pages = remember(s.musicEnabled) {
        StandByPage.entries.filter { it != StandByPage.MUSIC || s.musicEnabled }
    }
    val pager = rememberPagerState(initialPage = pages.indexOf(s.startPage).coerceAtLeast(0)) { pages.size }
    val musicIndex = pages.indexOf(StandByPage.MUSIC)
    val clockIndex = pages.indexOf(StandByPage.CLOCK)
    val nightClockOnly = night && s.nightClockOnly

    val pendingPage by vm.pendingPage.collectAsStateWithLifecycle()
    LaunchedEffect(pendingPage, pages) {
        val page = pendingPage ?: return@LaunchedEffect
        val i = pages.indexOf(page)
        if (i >= 0) pager.scrollToPage(i)
        vm.consumePageRequest()
    }
    // 음악이 재생되기 시작하면 음악 화면으로 자동 전환
    val np = nowPlaying
    LaunchedEffect(np?.trackKey, np?.isPlaying) {
        if (s.autoSwitchToMusic && np?.isPlaying == true && musicIndex >= 0 && pager.currentPage != musicIndex && editing == null) {
            pager.animateScrollToPage(musicIndex)
        }
    }
    // 일정 시간 음악 화면에 머물면 시계 화면으로 (번인 방지)
    LaunchedEffect(pager.currentPage, lastInteraction, s.musicToClockAfterMin, np?.trackKey) {
        if (s.musicToClockAfterMin > 0 && pager.currentPage == musicIndex && clockIndex >= 0) {
            delay(s.musicToClockAfterMin * 60_000L)
            pager.animateScrollToPage(clockIndex)
        }
    }

    val actions = remember(vm, host) {
        object : StandByActions {
            override fun playPause() = vm.playPause()
            override fun next() = vm.next()
            override fun previous() = vm.previous()
            override fun seekTo(positionMs: Long) = vm.seekTo(positionMs)
            override fun openSettings() = host.openSettings()
            override fun requestCalendarPermission() = host.requestCalendarPermission()
            override fun playLocal(index: Int) = vm.playLocal(index)
            override fun toggleShuffle() = vm.toggleShuffle()
            override fun cycleRepeat() = vm.cycleRepeat()
        }
    }
    val currentPageState = rememberUpdatedState(pages.getOrNull(pager.currentPage))

    // OLED 보호 + 유휴 시 어둡게: 검정 반투명 막을 덮어 흰 요소의 밝기를 낮춤
    val dimTarget = run {
        var keep = 1f
        if (s.oledProtect) keep *= 0.86f
        if (dimmed) keep *= (1f - s.dimLevel)
        1f - keep
    }
    val dimAlpha by animateFloatAsState(dimTarget, tween(1_500), label = "dim")

    CompositionLocalProvider(LocalStandByColors provides colors) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                // 모든 터치의 시작을 '조작'으로 기록 (자식에게 이벤트는 그대로 전달)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { it.pressed && !it.previousPressed }) {
                                lastInteraction = System.currentTimeMillis()
                            }
                        }
                    }
                },
        ) {
            val minDim = min(maxWidth.value, maxHeight.value)
            // 실제 화면 크기에 맞춘 안전 이동 범위 (가장자리 잘림 방지용 여백과 동일)
            val maxShift = min(s.shiftMaxDp.toFloat(), minDim * 0.03f).dp
            val burnConfig = BurnInConfig(
                enabled = s.burnInEnabled,
                maxShift = maxShift,
                smooth = s.smoothShift && !night,
                intervalSec = s.shiftIntervalSec,
            )

            if (screenOff) {
                // 화면 끄기: 아무것도 그리지 않음(검정). 탭하면 다시 켜짐
                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { lastInteraction = System.currentTimeMillis() } })
                return@BoxWithConstraints
            }

            ProvideBurnIn(burnConfig) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(if (s.burnInEnabled) maxShift + 4.dp else 4.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { controlsVisible = !controlsVisible },
                                onLongPress = { editing = currentPageState.value },
                            )
                        },
                ) {
                    if (nightClockOnly) {
                        ClockPage(s, onStyleChange = {}, modifier = Modifier.fillMaxSize(), forcedStyle = nightClockStyle(s.clockStyle))
                    } else {
                        HorizontalPager(
                            state = pager,
                            modifier = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 0,
                            key = { pages[it].name },
                        ) { index ->
                            when (pages[index]) {
                                StandByPage.WIDGETS -> WidgetsPage(
                                    data = WidgetData(s, battery, weather, events, nowPlaying, hideMusicDetails),
                                    actions = actions,
                                    onStackIndexChange = { left, i ->
                                        vm.update { if (left) it.copy(leftIndex = i) else it.copy(rightIndex = i) }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                                StandByPage.CLOCK -> ClockPage(
                                    settings = s,
                                    onStyleChange = { st -> vm.update { it.copy(clockStyle = st) } },
                                    modifier = Modifier.fillMaxSize(),
                                )
                                StandByPage.PHOTOS -> PhotoPage(photos, s, actions, Modifier.fillMaxSize())
                                StandByPage.MUSIC -> MusicPage(
                                    np = nowPlaying,
                                    s = s,
                                    hideDetails = hideMusicDetails,
                                    externalAccess = externalAccess,
                                    localTrackCount = localCount,
                                    interactionKey = lastInteraction,
                                    actions = actions,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }

            // 밝기 낮춤 막 (터치는 통과)
            if (dimAlpha > 0.005f) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimAlpha.coerceIn(0f, 0.9f))))
            }

            ControlsOverlay(
                visible = controlsVisible && editing == null,
                pages = if (nightClockOnly) listOf(StandByPage.CLOCK) else pages,
                currentPage = if (nightClockOnly) 0 else pager.currentPage,
                onEdit = {
                    controlsVisible = false
                    editing = currentPageState.value
                },
                onSettings = {
                    controlsVisible = false
                    host.openSettings()
                },
                onClose = { host.close() },
                modifier = Modifier.fillMaxSize(),
            )

            val update: ((AppSettings) -> AppSettings) -> Unit = { t -> vm.update(t) }
            when (editing) {
                StandByPage.WIDGETS -> EditPanel("위젯 편집", { editing = null }) { widgetEditContent(s, update) }
                StandByPage.CLOCK -> EditPanel("시계 사용자화", { editing = null }) { clockEditContent(s, update) }
                StandByPage.MUSIC -> EditPanel("음악 화면", { editing = null }) { musicEditContent(s, update) }
                StandByPage.PHOTOS -> EditPanel("사진 모드", { editing = null }) {
                    photoEditContent(s, update) {
                        editing = null
                        host.openSettings()
                    }
                }
                null -> Unit
            }
        }
    }
}

/** 야간 '시계만 표시'에서는 아날로그/디지털 중 화면이 덜 밝은 스타일을 사용 */
private fun nightClockStyle(current: ClockStyle): ClockStyle = when (current) {
    ClockStyle.WORLD, ClockStyle.COLOR_DIGITAL -> ClockStyle.BIG_DIGITAL
    else -> current
}
