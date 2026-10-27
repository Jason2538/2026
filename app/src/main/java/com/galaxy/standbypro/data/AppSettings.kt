package com.galaxy.standbypro.data

/** StandBy의 네 가지 메인 화면 (좌우 스와이프 순서) */
enum class StandByPage(val label: String) {
    WIDGETS("위젯"),
    CLOCK("시계"),
    PHOTOS("사진"),
    MUSIC("음악"),
}

/** 전체 화면 시계 스타일 (위아래 스와이프 순서) */
enum class ClockStyle(val label: String) {
    BIG_DIGITAL("거대한 디지털"),
    MINIMAL_ANALOG("미니멀 아날로그"),
    CLASSIC_ANALOG("클래식 아날로그"),
    COLOR_DIGITAL("컬러 디지털"),
    WORLD("세계 시계"),
    OLED_SIMPLE("OLED 미니멀"),
}

/** 위젯 화면에서 쓸 수 있는 위젯 */
enum class WidgetType(val label: String) {
    ANALOG_CLOCK("아날로그 시계"),
    DIGITAL_CLOCK("디지털 시계"),
    CALENDAR("달력"),
    TODAY("오늘 날짜"),
    WORLD_CLOCK("세계 시계"),
    BATTERY("배터리"),
    CHARGING("충전 상태"),
    WEATHER("날씨"),
    NOW_PLAYING("재생 중인 음악"),
    EVENTS("일정"),
}

enum class NightModeSetting(val label: String) {
    OFF("끄기"),
    AUTO("자동"),
    ALWAYS("항상 켜기"),
}

enum class MusicBackground(val label: String) {
    BLACK("완전 검정"),
    GRADIENT("앨범 색 그라데이션"),
}

enum class HourFormat(val label: String) {
    SYSTEM("시스템 설정 따름"),
    H12("12시간"),
    H24("24시간"),
}

/**
 * 앱 전체 설정. DataStore에 영구 저장된다 ([SettingsRepository]).
 * 모든 값은 기본값을 가지므로 처음 설치해도 바로 동작한다.
 */
data class AppSettings(
    // ── 화면 ──
    val startPage: StandByPage = StandByPage.WIDGETS,
    val clockStyle: ClockStyle = ClockStyle.BIG_DIGITAL,
    val clockColorIndex: Int = 0,
    val hourFormat: HourFormat = HourFormat.SYSTEM,
    val showSeconds: Boolean = true,
    val showDate: Boolean = true,

    // ── 위젯 ──
    val leftStack: List<WidgetType> = listOf(
        WidgetType.ANALOG_CLOCK, WidgetType.DIGITAL_CLOCK, WidgetType.WORLD_CLOCK, WidgetType.BATTERY,
    ),
    val rightStack: List<WidgetType> = listOf(
        WidgetType.CALENDAR, WidgetType.TODAY, WidgetType.WEATHER,
        WidgetType.NOW_PLAYING, WidgetType.EVENTS, WidgetType.CHARGING,
    ),
    val leftIndex: Int = 0,
    val rightIndex: Int = 0,
    val splitRatio: Float = 0.5f,
    val widgetAccentIndex: Int = 2,
    val worldClockZones: List<String> = listOf(
        "Asia/Seoul", "America/New_York", "Europe/London", "America/Los_Angeles",
    ),
    val weatherCity: String = "",
    val weatherLat: Double = Double.NaN,
    val weatherLon: Double = Double.NaN,

    // ── 사진 ──
    val photoUris: List<String> = emptyList(),
    val photoFolderUri: String = "",
    val slideshowSeconds: Int = 15,
    val photoShowClock: Boolean = true,
    val photoShowDate: Boolean = true,
    val photoFill: Boolean = true,
    val photoShuffle: Boolean = false,

    // ── 음악 ──
    val musicEnabled: Boolean = true,
    val externalMusicEnabled: Boolean = true,
    val showAlbumArt: Boolean = true,
    val showMusicInfo: Boolean = true,
    val autoSwitchToMusic: Boolean = true,
    val musicBackground: MusicBackground = MusicBackground.GRADIENT,
    val coverScale: Float = 1.0f,
    val showProgress: Boolean = true,
    val showClockWithMusic: Boolean = true,
    val hideMusicWhenLocked: Boolean = false,

    // ── 번인 방지 ──
    val burnInEnabled: Boolean = true,
    val shiftIntervalSec: Int = 120,
    val shiftMaxDp: Int = 10,
    val smoothShift: Boolean = true,
    val oledProtect: Boolean = true,
    val coverBrightness: Float = 0.85f,
    val coverShrinkAfterMin: Int = 15,
    val hideCover: Boolean = false,
    val musicToClockAfterMin: Int = 0,
    val dimAfterMin: Int = 10,
    val dimLevel: Float = 0.45f,
    val screenOffAfterMin: Int = 0,
    val useCustomBrightness: Boolean = false,
    val brightness: Float = 0.6f,
    val nightBrightness: Float = 0.08f,

    // ── 야간 모드 ──
    val nightMode: NightModeSetting = NightModeSetting.AUTO,
    val nightUseLightSensor: Boolean = true,
    val nightStartHour: Int = 22,
    val nightEndHour: Int = 7,
    val nightLuxThreshold: Float = 4f,
    val nightClockOnly: Boolean = false,

    // ── 자동 실행 ──
    val keepScreenOn: Boolean = true,
    val exitOnUnplug: Boolean = true,
    val forceLandscape: Boolean = true,
    val chargeAutoLaunch: Boolean = false,
    val autoLaunchNeedsLandscape: Boolean = true,
    val showOnLockScreen: Boolean = true,
) {
    val hasWeatherLocation: Boolean get() = !weatherLat.isNaN() && !weatherLon.isNaN()
}
