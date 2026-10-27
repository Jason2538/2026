package com.galaxy.standbypro.ui.standby

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galaxy.standbypro.data.AppSettings
import com.galaxy.standbypro.data.ClockStyle
import com.galaxy.standbypro.data.HourFormat
import com.galaxy.standbypro.data.MusicBackground
import com.galaxy.standbypro.data.StandByPage
import com.galaxy.standbypro.data.WidgetType
import com.galaxy.standbypro.ui.theme.AccentColors
import kotlin.math.roundToInt

private val PanelBg = Color(0xFF1C1C1E)
private val RowBg = Color(0xFF2C2C2E)
private val Accent = Color(0xFFFF9F0A)

// ═══════════════════════ 탭하면 나타나는 숨은 컨트롤 ═══════════════════════

@Composable
fun ControlsOverlay(
    visible: Boolean,
    pages: List<StandByPage>,
    currentPage: Int,
    onEdit: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RoundIcon(Icons.Filled.Edit, "편집", onEdit)
                RoundIcon(Icons.Filled.Settings, "설정", onSettings)
                RoundIcon(Icons.Filled.Close, "닫기", onClose)
            }
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(pages.getOrNull(currentPage)?.label ?: "", color = Color(0xFF8E8E93), fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pages.indices.forEach { i ->
                        Box(
                            Modifier
                                .size(if (i == currentPage) 8.dp else 6.dp)
                                .background(if (i == currentPage) Color(0xFFE5E5EA) else Color(0xFF48484A), CircleShape),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .background(Color(0xCC2C2C2E), CircleShape),
    ) {
        Icon(icon, contentDescription = label, tint = Color(0xFFE5E5EA))
    }
}

// ═══════════════════════ 길게 눌러 여는 편집 패널 ═══════════════════════

@Composable
fun EditPanel(title: String, onDone: () -> Unit, content: LazyListScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .pointerInput(Unit) { detectTapGestures { onDone() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxHeight(0.92f)
                .fillMaxWidth(0.92f)
                .widthIn(max = 720.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(PanelBg)
                .pointerInput(Unit) { detectTapGestures { /* 패널 안쪽 탭은 닫지 않음 */ } }
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDone) { Text("완료", color = Accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

fun LazyListScope.sectionLabel(text: String) {
    item(key = "label_$text") {
        Text(text, color = Color(0xFF8E8E93), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
    }
}

@Composable
fun PanelSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RowBg)
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = Color(0xFF8E8E93), fontSize = 13.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF34C759), checkedThumbColor = Color.White),
        )
    }
}

/** 드래그 중에는 화면만 바꾸고, 손을 뗄 때 저장 (DataStore 쓰기 최소화) */
@Composable
fun PanelSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RowBg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row {
            Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(format(local), color = Color(0xFF8E8E93), fontSize = 15.sp)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent),
        )
    }
}

@Composable
fun <T> ChipRow(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { option ->
            val isSel = option == selected
            Text(
                label(option),
                color = if (isSel) Color.Black else Color.White,
                fontSize = 15.sp,
                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSel) Color(0xFFF2F2F7) else RowBg)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
fun ColorChips(selectedIndex: Int, onSelect: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(AccentColors.size) { i ->
            val c = AccentColors[i]
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(c.color)
                    .then(if (i == selectedIndex) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                    .clickable { onSelect(i) },
            )
        }
    }
}

// ─────────────────────────── 위젯 편집 ───────────────────────────

fun LazyListScope.widgetEditContent(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    stackEditor("왼쪽 위젯 스택", s.leftStack) { list -> update { it.copy(leftStack = list, leftIndex = 0) } }
    stackEditor("오른쪽 위젯 스택", s.rightStack) { list -> update { it.copy(rightStack = list, rightIndex = 0) } }
    sectionLabel("크기")
    item(key = "split") {
        PanelSlider(
            title = "왼쪽 위젯 너비",
            value = s.splitRatio,
            range = 0.3f..0.7f,
            format = { "${(it * 100).roundToInt()}%" },
            onCommit = { v -> update { it.copy(splitRatio = v) } },
        )
    }
    sectionLabel("강조 색")
    item(key = "widgetColor") { ColorChips(s.widgetAccentIndex) { i -> update { it.copy(widgetAccentIndex = i) } } }
}

private fun LazyListScope.stackEditor(title: String, stack: List<WidgetType>, onChange: (List<WidgetType>) -> Unit) {
    sectionLabel(title)
    stack.forEachIndexed { index, type ->
        item(key = "$title-$index-${type.name}") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(RowBg)
                    .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(type.label, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { if (index > 0) onChange(stack.toMutableList().apply { add(index - 1, removeAt(index)) }) }, enabled = index > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, "위로", tint = if (index > 0) Color.White else Color.DarkGray)
                }
                IconButton(onClick = { if (index < stack.lastIndex) onChange(stack.toMutableList().apply { add(index + 1, removeAt(index)) }) }, enabled = index < stack.lastIndex) {
                    Icon(Icons.Filled.KeyboardArrowDown, "아래로", tint = if (index < stack.lastIndex) Color.White else Color.DarkGray)
                }
                IconButton(onClick = { onChange(stack.toMutableList().apply { removeAt(index) }) }) {
                    Icon(Icons.Filled.Close, "빼기", tint = Color(0xFFFF453A))
                }
            }
        }
    }
    val addable = WidgetType.entries.filter { it !in stack }
    if (addable.isNotEmpty()) {
        item(key = "$title-add") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, null, tint = Accent, modifier = Modifier.padding(end = 6.dp))
                ChipRow(addable, null, { it.label }) { t -> onChange(stack + t) }
            }
        }
    }
}

// ─────────────────────────── 시계 편집 ───────────────────────────

fun LazyListScope.clockEditContent(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    sectionLabel("시계 스타일 (화면을 위아래로 밀어도 바뀝니다)")
    item(key = "style") { ChipRow(ClockStyle.entries, s.clockStyle, { it.label }) { st -> update { it.copy(clockStyle = st) } } }
    sectionLabel("시계 색상")
    item(key = "color") { ColorChips(s.clockColorIndex) { i -> update { it.copy(clockColorIndex = i) } } }
    sectionLabel("시간 표시")
    item(key = "hour") { ChipRow(HourFormat.entries, s.hourFormat, { it.label }) { f -> update { it.copy(hourFormat = f) } } }
    item(key = "seconds") { PanelSwitch("초침 표시", s.showSeconds, { v -> update { it.copy(showSeconds = v) } }) }
    item(key = "date") { PanelSwitch("날짜 표시", s.showDate, { v -> update { it.copy(showDate = v) } }) }
}

// ─────────────────────────── 음악 화면 편집 ───────────────────────────

fun LazyListScope.musicEditContent(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    sectionLabel("배경")
    item(key = "bg") { ChipRow(MusicBackground.entries, s.musicBackground, { it.label }) { b -> update { it.copy(musicBackground = b) } } }
    item(key = "cover") {
        PanelSlider("커버 크기", s.coverScale, 0.5f..1f, format = { "${(it * 100).roundToInt()}%" }) { v -> update { it.copy(coverScale = v) } }
    }
    item(key = "coverBright") {
        PanelSlider("커버 밝기 (번인 방지)", s.coverBrightness, 0.2f..1f, format = { "${(it * 100).roundToInt()}%" }) { v ->
            update { it.copy(coverBrightness = v) }
        }
    }
    item(key = "showArt") { PanelSwitch("앨범 커버 표시", s.showAlbumArt, { v -> update { it.copy(showAlbumArt = v) } }) }
    item(key = "hideCover") { PanelSwitch("커버 숨기기", s.hideCover, { v -> update { it.copy(hideCover = v) } }, "번인이 걱정되면 커버 없이 정보만 표시") }
    item(key = "info") { PanelSwitch("곡 정보 표시", s.showMusicInfo, { v -> update { it.copy(showMusicInfo = v) } }) }
    item(key = "progress") { PanelSwitch("진행 바 표시", s.showProgress, { v -> update { it.copy(showProgress = v) } }) }
    item(key = "clock") { PanelSwitch("시계 함께 표시", s.showClockWithMusic, { v -> update { it.copy(showClockWithMusic = v) } }) }
}

// ─────────────────────────── 사진 편집 ───────────────────────────

fun LazyListScope.photoEditContent(
    s: AppSettings,
    update: ((AppSettings) -> AppSettings) -> Unit,
    openSettings: () -> Unit,
) {
    item(key = "pick") {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(RowBg)
                .clickable { openSettings() }
                .padding(16.dp),
        ) {
            Text("사진·앨범 선택 (설정 열기)", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    sectionLabel("슬라이드 간격")
    item(key = "interval") {
        ChipRow(listOf(5, 10, 15, 30, 60, 300), s.slideshowSeconds, { if (it < 60) "${it}초" else "${it / 60}분" }) { v ->
            update { it.copy(slideshowSeconds = v) }
        }
    }
    item(key = "clock") { PanelSwitch("사진 위 시계 표시", s.photoShowClock, { v -> update { it.copy(photoShowClock = v) } }) }
    item(key = "date") { PanelSwitch("날짜 표시", s.photoShowDate, { v -> update { it.copy(photoShowDate = v) } }) }
    item(key = "fill") { PanelSwitch("화면 가득 채우기", s.photoFill, { v -> update { it.copy(photoFill = v) } }, "끄면 사진 전체가 보이도록 맞춤(검정 여백)") }
    item(key = "shuffle") { PanelSwitch("무작위 순서", s.photoShuffle, { v -> update { it.copy(photoShuffle = v) } }) }
}

/** 공용 간격 도우미 */
@Composable
fun HSpace(w: Int) = Spacer(Modifier.width(w.dp))
