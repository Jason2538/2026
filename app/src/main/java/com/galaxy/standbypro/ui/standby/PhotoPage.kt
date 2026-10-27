package com.galaxy.standbypro.ui.standby

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.galaxy.standbypro.data.AppSettings
import com.galaxy.standbypro.ui.theme.InterDisplay
import com.galaxy.standbypro.ui.theme.TabularNums
import kotlinx.coroutines.delay

/** 모드 C: 사진 모드. 기기에 있는 사진만 사용하며 업로드하지 않는다. */
@Composable
fun PhotoPage(
    photos: List<Uri>,
    s: AppSettings,
    actions: StandByActions,
    modifier: Modifier = Modifier,
) {
    val colors = LocalStandByColors.current
    val context = LocalContext.current

    if (photos.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.burnIn(70)) {
                CenterNote("사진", "설정에서 표시할 사진이나 앨범(폴더)을 선택하세요")
                Text(
                    "설정 열기",
                    color = colors.widgetAccent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { actions.openSettings() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        return
    }

    val order = remember(photos, s.photoShuffle) { if (s.photoShuffle) photos.shuffled() else photos }
    var index by remember(order) { mutableIntStateOf(0) }

    LaunchedEffect(order, s.slideshowSeconds, index) {
        delay(s.slideshowSeconds * 1000L)
        index = (index + 1) % order.size
    }

    // 정지 화면이 오래 머물지 않도록 아주 느린 확대/축소 (번인 방지 켜졌을 때)
    val drift by animateFloatAsState(
        targetValue = if (s.burnInEnabled && index % 2 == 1) 1.04f else 1f,
        animationSpec = tween(durationMillis = s.slideshowSeconds * 1000, easing = LinearEasing),
        label = "photoDrift",
    )

    Box(
        modifier
            .background(Color.Black)
            .pointerInput(order) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total < -80f) index = (index + 1) % order.size
                        else if (total > 80f) index = (index - 1 + order.size) % order.size
                    },
                ) { _, dy -> total += dy }
            },
    ) {
        Crossfade(
            targetState = order[index % order.size],
            animationSpec = tween(1_200),
            label = "photoCrossfade",
        ) { uri ->
            AsyncImage(
                model = ImageRequest.Builder(context).data(uri).crossfade(false).build(),
                contentDescription = "사진",
                contentScale = if (s.photoFill) ContentScale.Crop else ContentScale.Fit,
                colorFilter = if (colors.night) RedMonochromeFilter else null,
                alpha = if (colors.night) 0.55f else 1f,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = drift
                        scaleY = drift
                    },
            )
        }

        if (s.photoShowClock || s.photoShowDate) {
            PhotoClockOverlay(s, Modifier.align(Alignment.BottomStart).padding(28.dp).burnIn(71))
        }
    }
}

@Composable
private fun PhotoClockOverlay(s: AppSettings, modifier: Modifier) {
    val colors = LocalStandByColors.current
    val context = LocalContext.current
    val now by rememberNow(Tick.MINUTE)
    val t = zoned(now)
    val shadow = Shadow(Color.Black.copy(alpha = 0.65f), blurRadius = 18f)
    val textColor = if (colors.night) colors.primary else Color.White
    Column(modifier) {
        if (s.photoShowDate) {
            Text(
                dateFullText(t),
                style = TextStyle(color = textColor.copy(alpha = 0.9f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, shadow = shadow),
            )
            Spacer(Modifier.height(2.dp))
        }
        if (s.photoShowClock) {
            Text(
                timeText(t, use24Hour(context, s.hourFormat)),
                style = TextStyle(
                    color = textColor,
                    fontSize = 84.sp,
                    lineHeight = 84.sp,
                    fontFamily = InterDisplay,
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = TabularNums,
                    shadow = shadow,
                ),
            )
        }
    }
}
