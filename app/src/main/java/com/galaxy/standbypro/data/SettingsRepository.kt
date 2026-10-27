package com.galaxy.standbypro.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "standby_settings")

/** 모든 설정을 DataStore(Preferences)에 영구 저장/조회한다. */
class SettingsRepository(context: Context, scope: CoroutineScope) {

    private val store = context.applicationContext.settingsStore

    val settingsFlow: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    /** 디스크에서 처음 읽기 전에는 null. 첫 화면 깜빡임(기본값 → 저장값)을 막는 데 사용 */
    val loadedSettings: StateFlow<AppSettings?> =
        settingsFlow.stateIn<AppSettings?>(scope, SharingStarted.Eagerly, null)

    /** 앱 전체에서 공유하는 최신 설정 (처음엔 기본값) */
    val settings: StateFlow<AppSettings> =
        settingsFlow.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val updated = transform(prefs.toSettings())
            updated.writeTo(prefs)
        }
    }

    // ───────────────────────── 키 ─────────────────────────
    private object K {
        val startPage = stringPreferencesKey("start_page")
        val clockStyle = stringPreferencesKey("clock_style")
        val clockColorIndex = intPreferencesKey("clock_color_index")
        val hourFormat = stringPreferencesKey("hour_format")
        val showSeconds = booleanPreferencesKey("show_seconds")
        val showDate = booleanPreferencesKey("show_date")

        val leftStack = stringPreferencesKey("left_stack")
        val rightStack = stringPreferencesKey("right_stack")
        val leftIndex = intPreferencesKey("left_index")
        val rightIndex = intPreferencesKey("right_index")
        val splitRatio = floatPreferencesKey("split_ratio")
        val widgetAccentIndex = intPreferencesKey("widget_accent_index")
        val worldClockZones = stringPreferencesKey("world_clock_zones")
        val weatherCity = stringPreferencesKey("weather_city")
        val weatherLat = doublePreferencesKey("weather_lat")
        val weatherLon = doublePreferencesKey("weather_lon")

        val photoUris = stringPreferencesKey("photo_uris")
        val photoFolderUri = stringPreferencesKey("photo_folder_uri")
        val slideshowSeconds = intPreferencesKey("slideshow_seconds")
        val photoShowClock = booleanPreferencesKey("photo_show_clock")
        val photoShowDate = booleanPreferencesKey("photo_show_date")
        val photoFill = booleanPreferencesKey("photo_fill")
        val photoShuffle = booleanPreferencesKey("photo_shuffle")

        val musicEnabled = booleanPreferencesKey("music_enabled")
        val externalMusicEnabled = booleanPreferencesKey("external_music_enabled")
        val showAlbumArt = booleanPreferencesKey("show_album_art")
        val showMusicInfo = booleanPreferencesKey("show_music_info")
        val autoSwitchToMusic = booleanPreferencesKey("auto_switch_to_music")
        val musicBackground = stringPreferencesKey("music_background")
        val coverScale = floatPreferencesKey("cover_scale")
        val showProgress = booleanPreferencesKey("show_progress")
        val showClockWithMusic = booleanPreferencesKey("show_clock_with_music")
        val hideMusicWhenLocked = booleanPreferencesKey("hide_music_when_locked")

        val burnInEnabled = booleanPreferencesKey("burn_in_enabled")
        val shiftIntervalSec = intPreferencesKey("shift_interval_sec")
        val shiftMaxDp = intPreferencesKey("shift_max_dp")
        val smoothShift = booleanPreferencesKey("smooth_shift")
        val oledProtect = booleanPreferencesKey("oled_protect")
        val coverBrightness = floatPreferencesKey("cover_brightness")
        val coverShrinkAfterMin = intPreferencesKey("cover_shrink_after_min")
        val hideCover = booleanPreferencesKey("hide_cover")
        val musicToClockAfterMin = intPreferencesKey("music_to_clock_after_min")
        val dimAfterMin = intPreferencesKey("dim_after_min")
        val dimLevel = floatPreferencesKey("dim_level")
        val screenOffAfterMin = intPreferencesKey("screen_off_after_min")
        val useCustomBrightness = booleanPreferencesKey("use_custom_brightness")
        val brightness = floatPreferencesKey("brightness")
        val nightBrightness = floatPreferencesKey("night_brightness")

        val nightMode = stringPreferencesKey("night_mode")
        val nightUseLightSensor = booleanPreferencesKey("night_use_light_sensor")
        val nightStartHour = intPreferencesKey("night_start_hour")
        val nightEndHour = intPreferencesKey("night_end_hour")
        val nightLuxThreshold = floatPreferencesKey("night_lux_threshold")
        val nightClockOnly = booleanPreferencesKey("night_clock_only")

        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val exitOnUnplug = booleanPreferencesKey("exit_on_unplug")
        val forceLandscape = booleanPreferencesKey("force_landscape")
        val chargeAutoLaunch = booleanPreferencesKey("charge_auto_launch")
        val autoLaunchNeedsLandscape = booleanPreferencesKey("auto_launch_needs_landscape")
        val showOnLockScreen = booleanPreferencesKey("show_on_lock_screen")
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        if (this == null) default else runCatching { enumValueOf<E>(this) }.getOrDefault(default)

    private fun String?.toWidgetList(default: List<WidgetType>): List<WidgetType> {
        if (this == null) return default
        if (isBlank()) return emptyList()
        return split(",").mapNotNull { name -> runCatching { WidgetType.valueOf(name) }.getOrNull() }
    }

    private fun String?.toLines(default: List<String>): List<String> {
        if (this == null) return default
        return split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            startPage = this[K.startPage].toEnum(d.startPage),
            clockStyle = this[K.clockStyle].toEnum(d.clockStyle),
            clockColorIndex = this[K.clockColorIndex] ?: d.clockColorIndex,
            hourFormat = this[K.hourFormat].toEnum(d.hourFormat),
            showSeconds = this[K.showSeconds] ?: d.showSeconds,
            showDate = this[K.showDate] ?: d.showDate,

            leftStack = this[K.leftStack].toWidgetList(d.leftStack),
            rightStack = this[K.rightStack].toWidgetList(d.rightStack),
            leftIndex = this[K.leftIndex] ?: d.leftIndex,
            rightIndex = this[K.rightIndex] ?: d.rightIndex,
            splitRatio = (this[K.splitRatio] ?: d.splitRatio).coerceIn(0.3f, 0.7f),
            widgetAccentIndex = this[K.widgetAccentIndex] ?: d.widgetAccentIndex,
            worldClockZones = this[K.worldClockZones].toLines(d.worldClockZones),
            weatherCity = this[K.weatherCity] ?: d.weatherCity,
            weatherLat = this[K.weatherLat] ?: d.weatherLat,
            weatherLon = this[K.weatherLon] ?: d.weatherLon,

            photoUris = this[K.photoUris].toLines(d.photoUris),
            photoFolderUri = this[K.photoFolderUri] ?: d.photoFolderUri,
            slideshowSeconds = (this[K.slideshowSeconds] ?: d.slideshowSeconds).coerceIn(3, 3600),
            photoShowClock = this[K.photoShowClock] ?: d.photoShowClock,
            photoShowDate = this[K.photoShowDate] ?: d.photoShowDate,
            photoFill = this[K.photoFill] ?: d.photoFill,
            photoShuffle = this[K.photoShuffle] ?: d.photoShuffle,

            musicEnabled = this[K.musicEnabled] ?: d.musicEnabled,
            externalMusicEnabled = this[K.externalMusicEnabled] ?: d.externalMusicEnabled,
            showAlbumArt = this[K.showAlbumArt] ?: d.showAlbumArt,
            showMusicInfo = this[K.showMusicInfo] ?: d.showMusicInfo,
            autoSwitchToMusic = this[K.autoSwitchToMusic] ?: d.autoSwitchToMusic,
            musicBackground = this[K.musicBackground].toEnum(d.musicBackground),
            coverScale = (this[K.coverScale] ?: d.coverScale).coerceIn(0.5f, 1f),
            showProgress = this[K.showProgress] ?: d.showProgress,
            showClockWithMusic = this[K.showClockWithMusic] ?: d.showClockWithMusic,
            hideMusicWhenLocked = this[K.hideMusicWhenLocked] ?: d.hideMusicWhenLocked,

            burnInEnabled = this[K.burnInEnabled] ?: d.burnInEnabled,
            shiftIntervalSec = (this[K.shiftIntervalSec] ?: d.shiftIntervalSec).coerceIn(20, 900),
            shiftMaxDp = (this[K.shiftMaxDp] ?: d.shiftMaxDp).coerceIn(2, 30),
            smoothShift = this[K.smoothShift] ?: d.smoothShift,
            oledProtect = this[K.oledProtect] ?: d.oledProtect,
            coverBrightness = (this[K.coverBrightness] ?: d.coverBrightness).coerceIn(0.2f, 1f),
            coverShrinkAfterMin = this[K.coverShrinkAfterMin] ?: d.coverShrinkAfterMin,
            hideCover = this[K.hideCover] ?: d.hideCover,
            musicToClockAfterMin = this[K.musicToClockAfterMin] ?: d.musicToClockAfterMin,
            dimAfterMin = this[K.dimAfterMin] ?: d.dimAfterMin,
            dimLevel = (this[K.dimLevel] ?: d.dimLevel).coerceIn(0.1f, 0.9f),
            screenOffAfterMin = this[K.screenOffAfterMin] ?: d.screenOffAfterMin,
            useCustomBrightness = this[K.useCustomBrightness] ?: d.useCustomBrightness,
            brightness = (this[K.brightness] ?: d.brightness).coerceIn(0.01f, 1f),
            nightBrightness = (this[K.nightBrightness] ?: d.nightBrightness).coerceIn(0.01f, 1f),

            nightMode = this[K.nightMode].toEnum(d.nightMode),
            nightUseLightSensor = this[K.nightUseLightSensor] ?: d.nightUseLightSensor,
            nightStartHour = (this[K.nightStartHour] ?: d.nightStartHour).coerceIn(0, 23),
            nightEndHour = (this[K.nightEndHour] ?: d.nightEndHour).coerceIn(0, 23),
            nightLuxThreshold = (this[K.nightLuxThreshold] ?: d.nightLuxThreshold).coerceIn(0.5f, 50f),
            nightClockOnly = this[K.nightClockOnly] ?: d.nightClockOnly,

            keepScreenOn = this[K.keepScreenOn] ?: d.keepScreenOn,
            exitOnUnplug = this[K.exitOnUnplug] ?: d.exitOnUnplug,
            forceLandscape = this[K.forceLandscape] ?: d.forceLandscape,
            chargeAutoLaunch = this[K.chargeAutoLaunch] ?: d.chargeAutoLaunch,
            autoLaunchNeedsLandscape = this[K.autoLaunchNeedsLandscape] ?: d.autoLaunchNeedsLandscape,
            showOnLockScreen = this[K.showOnLockScreen] ?: d.showOnLockScreen,
        )
    }

    private fun AppSettings.writeTo(p: MutablePreferences) {
        p[K.startPage] = startPage.name
        p[K.clockStyle] = clockStyle.name
        p[K.clockColorIndex] = clockColorIndex
        p[K.hourFormat] = hourFormat.name
        p[K.showSeconds] = showSeconds
        p[K.showDate] = showDate

        p[K.leftStack] = leftStack.joinToString(",") { it.name }
        p[K.rightStack] = rightStack.joinToString(",") { it.name }
        p[K.leftIndex] = leftIndex
        p[K.rightIndex] = rightIndex
        p[K.splitRatio] = splitRatio
        p[K.widgetAccentIndex] = widgetAccentIndex
        p[K.worldClockZones] = worldClockZones.joinToString("\n")
        p[K.weatherCity] = weatherCity
        p[K.weatherLat] = weatherLat
        p[K.weatherLon] = weatherLon

        p[K.photoUris] = photoUris.joinToString("\n")
        p[K.photoFolderUri] = photoFolderUri
        p[K.slideshowSeconds] = slideshowSeconds
        p[K.photoShowClock] = photoShowClock
        p[K.photoShowDate] = photoShowDate
        p[K.photoFill] = photoFill
        p[K.photoShuffle] = photoShuffle

        p[K.musicEnabled] = musicEnabled
        p[K.externalMusicEnabled] = externalMusicEnabled
        p[K.showAlbumArt] = showAlbumArt
        p[K.showMusicInfo] = showMusicInfo
        p[K.autoSwitchToMusic] = autoSwitchToMusic
        p[K.musicBackground] = musicBackground.name
        p[K.coverScale] = coverScale
        p[K.showProgress] = showProgress
        p[K.showClockWithMusic] = showClockWithMusic
        p[K.hideMusicWhenLocked] = hideMusicWhenLocked

        p[K.burnInEnabled] = burnInEnabled
        p[K.shiftIntervalSec] = shiftIntervalSec
        p[K.shiftMaxDp] = shiftMaxDp
        p[K.smoothShift] = smoothShift
        p[K.oledProtect] = oledProtect
        p[K.coverBrightness] = coverBrightness
        p[K.coverShrinkAfterMin] = coverShrinkAfterMin
        p[K.hideCover] = hideCover
        p[K.musicToClockAfterMin] = musicToClockAfterMin
        p[K.dimAfterMin] = dimAfterMin
        p[K.dimLevel] = dimLevel
        p[K.screenOffAfterMin] = screenOffAfterMin
        p[K.useCustomBrightness] = useCustomBrightness
        p[K.brightness] = brightness
        p[K.nightBrightness] = nightBrightness

        p[K.nightMode] = nightMode.name
        p[K.nightUseLightSensor] = nightUseLightSensor
        p[K.nightStartHour] = nightStartHour
        p[K.nightEndHour] = nightEndHour
        p[K.nightLuxThreshold] = nightLuxThreshold
        p[K.nightClockOnly] = nightClockOnly

        p[K.keepScreenOn] = keepScreenOn
        p[K.exitOnUnplug] = exitOnUnplug
        p[K.forceLandscape] = forceLandscape
        p[K.chargeAutoLaunch] = chargeAutoLaunch
        p[K.autoLaunchNeedsLandscape] = autoLaunchNeedsLandscape
        p[K.showOnLockScreen] = showOnLockScreen
    }
}
