package com.galaxy.standbypro.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val CardBg = Color(0xFF1C1C1E)
val SubText = Color(0xFF8E8E93)
val Orange = Color(0xFFFF9F0A)
val GreenOk = Color(0xFF34C759)
val RedWarn = Color(0xFFFF453A)

@Composable
fun SettingsGroup(
    title: String?,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (title != null) {
            Text(
                title,
                color = SubText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 14.dp, bottom = 6.dp, top = 10.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardBg),
            content = content,
        )
        if (footer != null) {
            Text(footer, color = SubText, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp))
        }
    }
}

@Composable
fun ItemDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = Color(0xFF2C2C2E), thickness = 0.6.dp)

@Composable
fun SwitchItem(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Color.White else SubText, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = SubText, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = GreenOk, checkedThumbColor = Color.White),
        )
    }
}

@Composable
fun NavItem(
    title: String,
    onClick: () -> Unit,
    value: String? = null,
    subtitle: String? = null,
    titleColor: Color = Color.White,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = SubText, fontSize = 13.sp, lineHeight = 17.sp)
        }
        if (value != null) {
            Text(value, color = SubText, fontSize = 15.sp, maxLines = 1)
        }
        Text("  ›", color = Color(0xFF48484A), fontSize = 20.sp)
    }
}

@Composable
fun StatusItem(title: String, ok: Boolean, okText: String, needText: String, onClick: () -> Unit, subtitle: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(if (ok) GreenOk else Orange, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = SubText, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Text(if (ok) okText else needText, color = if (ok) GreenOk else Orange, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SliderItem(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row {
            Text(title, color = if (enabled) Color.White else SubText, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(format(local), color = SubText, fontSize = 15.sp)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}

/** 탭하면 선택 대화상자가 열리는 항목 */
@Composable
fun <T> ChoiceItem(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clickable(enabled = enabled) { open = true }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Color.White else SubText, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = SubText, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Text(label(selected), color = if (enabled) Orange else SubText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text("닫기") } },
            title = { Text(title) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(options) { _, option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = option == selected, role = Role.RadioButton) {
                                    onSelect(option)
                                    open = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == selected, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(label(option), fontSize = 16.sp)
                        }
                    }
                }
            },
            containerColor = CardBg,
        )
    }
}

@Composable
fun InfoText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color(0xFFC7C7CC),
        fontSize = 14.sp,
        lineHeight = 20.sp,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
fun ButtonRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
fun SmallAction(text: String, color: Color = Orange, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

val minuteOptions = listOf(0, 1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 90, 120)
fun minuteLabel(m: Int): String = when {
    m == 0 -> "끄기"
    m < 60 -> "${m}분"
    m % 60 == 0 -> "${m / 60}시간"
    else -> "${m / 60}시간 ${m % 60}분"
}

@Composable
fun headlineStyle() = MaterialTheme.typography.headlineMedium
