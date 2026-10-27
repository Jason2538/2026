package com.galaxy.standbypro.ui.standby

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.galaxy.standbypro.data.AppSettings
import com.galaxy.standbypro.data.MusicBackground
import com.galaxy.standbypro.music.Artwork
import com.galaxy.standbypro.music.MusicSource
import com.galaxy.standbypro.music.NowPlaying
import com.galaxy.standbypro.ui.theme.InterDisplay
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * 모드 D: 음악 플레이어. 검은 배경 위에 앨범 커버가 떠 있는 가로형 레이아웃.
 * 커버 색에서 뽑은 강조색/그라데이션, 크로스페이드 전환, 번인 방지(이동·어둡게·축소) 적용.
 */
@Composable
fun MusicPage(
    np: NowPlaying?,
    s: AppSettings,
    hideDetails: Boolean,
    externalAccess: Boolean,
    localTrackCount: Int,
    interactionKey: Long,
    actions: StandByActions,
    modifier: Modifier = Modifier,
) {
    val colors = LocalStandByColors.current
    if (np == null) {
        EmptyMusic(s, externalAccess, localTrackCount, actions, modifier)
        return
    }

    // ── 번인 방지: 일정 시간 조작이 없으면 커버 축소 ──
    var shrunk by remember { mutableStateOf(false) }
    LaunchedEffect(np.trackKey, interactionKey, s.coverShrinkAfterMin, s.burnInEnabled) {
        shrunk = false
        if (s.burnInEnabled && s.coverShrinkAfterMin > 0) {
            delay(s.coverShrinkAfterMin * 60_000L)
            shrunk = true
        }
    }
    // 커버를 탭하면 크게 보기 (번인 방지 켜짐: 2분 뒤 자동 복귀)
    var focus by remember { mutableStateOf(false) }
    LaunchedEffect(focus, interactionKey) {
        if (focus && s.burnInEnabled) {
            delay(120_000)
            focus = false
        }
    }

    val art = if (hideDetails || !s.showAlbumArt || s.hideCover) null else np.artwork
    val accent = when {
        colors.night -> colors.primary
        art != null -> Color(art.accent)
        else -> colors.accent
    }
    val brightness = when {
        colors.night -> 0.45f
        s.burnInEnabled || s.oledProtect -> s.coverBrightness
        else -> 1f
    }
    val showCover = s.showAlbumArt && !s.hideCover
    val showInfo = s.showMusicInfo && !focus

    BoxWithConstraints(modifier) {
        val landscape = maxWidth > maxHeight
        val gradientAlpha = when {
            colors.night || s.musicBackground == MusicBackground.BLACK || art == null -> 0f
            s.burnInEnabled || s.oledProtect -> 0.35f
            else -> 0.55f
        }
        if (gradientAlpha > 0f && art != null) {
            val dark = Color(art.dark)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(dark.copy(alpha = gradientAlpha), Color.Transparent),
                            center = if (landscape) Offset(constraints.maxWidth * 0.28f, constraints.maxHeight / 2f)
                            else Offset(constraints.maxWidth / 2f, constraints.maxHeight * 0.3f),
                            radius = constraints.maxWidth * 0.75f,
                        ),
                    ),
            )
        }

        val baseCover: Dp = if (landscape) min(maxHeight.value * 0.8f, maxWidth.value * 0.42f).dp
        else min(maxWidth.value * 0.78f, maxHeight.value * 0.45f).dp
        val focusLimit = if (s.burnInEnabled) 0.9f else 1f
        val targetCover = when {
            focus -> (if (landscape) maxHeight * 0.94f else maxWidth * 0.9f) * focusLimit
            shrunk -> baseCover * s.coverScale * 0.8f
            else -> baseCover * s.coverScale
        }
        val coverSize by animateDpAsState(targetCover, tween(900), label = "coverSize")

        val cover: @Composable () -> Unit = {
            if (showCover) {
                CoverArt(
                    art = art,
                    size = coverSize,
                    brightness = brightness,
                    night = colors.night,
                    glow = accent,
                    modifier = Modifier
                        .burnIn(81)
                        .pointerInput(Unit) { detectTapGestures(onTap = { focus = !focus }) },
                )
            }
        }
        val info: @Composable (Modifier) -> Unit = { m ->
            MusicInfo(np, s, hideDetails, showInfo, accent, actions, m)
        }

        val sidePad: Dp = maxWidth * 0.04f
        val gap: Dp = maxWidth * 0.05f
        if (landscape) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = sidePad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (focus || !showCover) Arrangement.Center else Arrangement.Start,
            ) {
                cover()
                if (!focus) {
                    if (showCover) Spacer(Modifier.width(gap))
                    info(Modifier.weight(1f))
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                cover()
                if (!focus) {
                    Spacer(Modifier.height(28.dp))
                    info(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun CoverArt(art: Artwork?, size: Dp, brightness: Float, night: Boolean, glow: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(size * 0.055f)
    Box(
        modifier
            .size(size)
            .shadow(
                elevation = if (night) 0.dp else 30.dp,
                shape = shape,
                ambientColor = glow.copy(alpha = 0.6f),
                spotColor = glow,
            )
            .clip(shape)
            .background(Color.Black),
    ) {
        // 곡이 바뀌면 커버를 부드럽게 크로스페이드
        Crossfade(targetState = art, animationSpec = tween(650), label = "coverCrossfade") { a ->
            if (a != null) {
                val image = remember(a) { a.bitmap.asImageBitmap() }
                Image(
                    bitmap = image,
                    contentDescription = "앨범 커버",
                    contentScale = ContentScale.Crop,
                    colorFilter = if (night) RedMonochromeFilter else null,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                DefaultArtwork(Modifier.fillMaxSize(), night = night)
            }
        }
        if (brightness < 0.99f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - brightness).coerceIn(0f, 0.85f))))
        }
    }
}

@Composable
private fun MusicInfo(
    np: NowPlaying,
    s: AppSettings,
    hideDetails: Boolean,
    showInfo: Boolean,
    accent: Color,
    actions: StandByActions,
    modifier: Modifier,
) {
    val colors = LocalStandByColors.current
    val context = LocalContext.current
    Column(modifier) {
        if (s.showClockWithMusic) {
            val now by rememberNow(Tick.MINUTE)
            val t = zoned(now)
            Row(Modifier.burnIn(84), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    timeText(t, use24Hour(context, s.hourFormat)),
                    color = colors.secondary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = InterDisplay,
                )
                Spacer(Modifier.width(10.dp))
                Text(np.appLabel, color = colors.tertiary, fontSize = 15.sp, maxLines = 1)
            }
            Spacer(Modifier.height(14.dp))
        }
        if (showInfo) {
            Column(Modifier.burnIn(82)) {
                Text(
                    if (hideDetails) "음악 재생 중" else np.title.ifBlank { "알 수 없는 곡" },
                    color = colors.primary,
                    fontSize = 32.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!hideDetails && np.artist.isNotBlank()) {
                    Text(np.artist, color = accent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!hideDetails && np.album.isNotBlank()) {
                    Text(np.album, color = colors.secondary, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        if (s.showProgress) {
            MusicProgress(np, accent, actions, Modifier.burnIn(83).widthIn(max = 560.dp))
            Spacer(Modifier.height(12.dp))
        }
        MusicControls(np, actions, Modifier.burnIn(85))
    }
}

@Composable
private fun MusicProgress(np: NowPlaying, accent: Color, actions: StandByActions, modifier: Modifier) {
    val colors = LocalStandByColors.current
    // 재생 중일 때만 1초마다 갱신
    val tick by rememberNow(if (np.isPlaying) Tick.SECOND else Tick.MINUTE)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = np.durationMs
    val position = remember(tick, np) { np.currentPosition() }
    val fraction = dragFraction ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val seekable = np.canSeek && duration > 0

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(22.dp)
                .pointerInput(seekable, duration) {
                    if (!seekable) return@pointerInput
                    detectTapGestures { offset ->
                        actions.seekTo((offset.x / size.width * duration).toLong().coerceIn(0L, duration))
                    }
                }
                .pointerInput(seekable, duration) {
                    if (!seekable) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let { actions.seekTo((it * duration).toLong()) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ ->
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
        ) {
            val h = if (dragFraction != null) 10.dp.toPx() else 6.dp.toPx()
            val y = (size.height - h) / 2f
            drawRoundRect(colors.tertiary.copy(alpha = 0.45f), Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
            drawRoundRect(accent, Offset(0f, y), Size(size.width * fraction, h), CornerRadius(h / 2))
        }
        Row(Modifier.fillMaxWidth()) {
            val shownPos = if (dragFraction != null) (dragFraction!! * duration).toLong() else position
            Text(formatDuration(shownPos), color = colors.secondary, fontSize = 13.sp, fontFamily = InterDisplay)
            Spacer(Modifier.weight(1f))
            Text(
                if (duration > 0) "-" + formatDuration(duration - shownPos) else "--:--",
                color = colors.secondary, fontSize = 13.sp, fontFamily = InterDisplay,
            )
        }
    }
}

@Composable
private fun MusicControls(np: NowPlaying, actions: StandByActions, modifier: Modifier) {
    val colors = LocalStandByColors.current
    val local = np.source == MusicSource.LOCAL
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        if (local) {
            GlyphButton(
                Glyph.SHUFFLE, 40.dp,
                if (np.shuffle) colors.accent else colors.secondary, true, actions::toggleShuffle,
            )
        }
        GlyphButton(Glyph.PREVIOUS, 56.dp, colors.primary, np.canPrevious, actions::previous)
        GlyphButton(if (np.isPlaying) Glyph.PAUSE else Glyph.PLAY, 72.dp, colors.primary, np.canPlayPause, actions::playPause)
        GlyphButton(Glyph.NEXT, 56.dp, colors.primary, np.canNext, actions::next)
        if (local) {
            GlyphButton(
                if (np.repeatMode == Player.REPEAT_MODE_ONE) Glyph.REPEAT_ONE else Glyph.REPEAT, 40.dp,
                if (np.repeatMode != Player.REPEAT_MODE_OFF) colors.accent else colors.secondary, true, actions::cycleRepeat,
            )
        }
    }
}

@Composable
private fun EmptyMusic(
    s: AppSettings,
    externalAccess: Boolean,
    localTrackCount: Int,
    actions: StandByActions,
    modifier: Modifier,
) {
    val colors = LocalStandByColors.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(Modifier.burnIn(86), horizontalAlignment = Alignment.CenterHorizontally) {
            DefaultArtwork(
                Modifier.size(140.dp).clip(RoundedCornerShape(16.dp)),
                night = colors.night,
            )
            Spacer(Modifier.height(18.dp))
            Text("재생 중인 음악 없음", color = colors.primary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            val hint = when {
                !s.musicEnabled -> "설정에서 음악 플레이어를 켜세요"
                s.externalMusicEnabled && !externalAccess ->
                    "Spotify·YouTube Music 등의 곡을 표시하려면\n설정에서 '알림 접근'을 허용하세요"
                else -> "음악 앱에서 재생을 시작하면 여기에 표시됩니다"
            }
            Text(hint, color = colors.secondary, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (localTrackCount > 0) {
                    PillButton("내 음악 재생", colors.accent) { actions.playLocal(0) }
                }
                if (s.externalMusicEnabled && !externalAccess) {
                    PillButton("설정 열기", colors.secondary) { actions.openSettings() }
                }
            }
        }
    }
}

@Composable
fun PillButton(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

fun formatDuration(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
