package com.galaxy.standbypro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.galaxy.standbypro.R

/**
 * 시계 숫자용 글꼴: Inter Display (SIL Open Font License 1.1, 앱에 포함 허용).
 * Apple의 SF 글꼴은 사용하지 않는다. 한글은 기기 시스템 글꼴로 자동 대체된다.
 */
val InterDisplay = FontFamily(
    Font(R.font.inter_display_thin, FontWeight.Thin),
    Font(R.font.inter_display_light, FontWeight.Light),
    Font(R.font.inter_display_regular, FontWeight.Normal),
    Font(R.font.inter_display_medium, FontWeight.Medium),
    Font(R.font.inter_display_semibold, FontWeight.SemiBold),
    Font(R.font.inter_display_bold, FontWeight.Bold),
    Font(R.font.inter_display_black, FontWeight.Black),
)

/** 고정폭 숫자(tnum) → 시간이 바뀌어도 숫자가 흔들리지 않음 */
const val TabularNums = "tnum"

data class NamedColor(val name: String, val color: Color)

/** 시계/위젯 강조색. 은은하고 OLED에서 너무 쨍하지 않은 톤 */
val AccentColors = listOf(
    NamedColor("흰색", Color(0xFFF2F2F7)),
    NamedColor("오렌지", Color(0xFFFF9F0A)),
    NamedColor("빨강", Color(0xFFFF453A)),
    NamedColor("핑크", Color(0xFFFF6482)),
    NamedColor("보라", Color(0xFFBF5AF2)),
    NamedColor("파랑", Color(0xFF64A8FF)),
    NamedColor("청록", Color(0xFF5AC8C8)),
    NamedColor("초록", Color(0xFF4CD964)),
    NamedColor("노랑", Color(0xFFFFD60A)),
    NamedColor("골드", Color(0xFFD4B483)),
)

fun accentColor(index: Int): Color = AccentColors.getOrElse(index) { AccentColors[0] }.color

object StandByPalette {
    val Gray1 = Color(0xFF8E8E93)
    val Gray2 = Color(0xFF636366)
    val Gray4 = Color(0xFF3A3A3C)
    val Gray5 = Color(0xFF2C2C2E)
    val Gray6 = Color(0xFF1C1C1E)
    val SecondHand = Color(0xFFFF9F0A)
    val Red = Color(0xFFFF453A)
    val NightRed = Color(0xFFE5261C)
    val NightRedDim = Color(0xFF7D140F)
    val Green = Color(0xFF34C759)
}

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF9F0A),
    onPrimary = Color.Black,
    secondary = Color(0xFF64A8FF),
    background = Color.Black,
    onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF111113),
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFFAEAEB2),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF242426),
    surfaceContainerHighest = Color(0xFF2C2C2E),
    outline = Color(0xFF48484A),
    outlineVariant = Color(0xFF2C2C2E),
)

@Composable
fun StandByMaterialTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
