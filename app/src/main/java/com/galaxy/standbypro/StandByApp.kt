package com.galaxy.standbypro

import android.app.Application
import android.content.Context
import com.galaxy.standbypro.data.BatteryRepository
import com.galaxy.standbypro.data.CalendarRepository
import com.galaxy.standbypro.data.PhotoRepository
import com.galaxy.standbypro.data.SettingsRepository
import com.galaxy.standbypro.data.WeatherRepository
import com.galaxy.standbypro.music.ArtworkLoader
import com.galaxy.standbypro.music.ExternalMediaRepository
import com.galaxy.standbypro.music.LocalMusicRepository
import com.galaxy.standbypro.music.MusicRepository
import com.galaxy.standbypro.system.ChargingWatchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 앱 전체에서 공유하는 저장소들 (간단한 수동 DI 컨테이너) */
class StandByApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsRepository by lazy { SettingsRepository(this, appScope) }
    val batteryRepository by lazy { BatteryRepository(this, appScope) }
    val weatherRepository by lazy { WeatherRepository(settingsRepository, appScope) }
    val calendarRepository by lazy { CalendarRepository(this, appScope) }
    val photoRepository by lazy { PhotoRepository(this, settingsRepository, appScope) }
    val artworkLoader by lazy { ArtworkLoader(this) }
    val externalMediaRepository by lazy { ExternalMediaRepository(this, artworkLoader, appScope) }
    val localMusicRepository by lazy { LocalMusicRepository(this, artworkLoader, appScope) }
    val musicRepository by lazy {
        MusicRepository(externalMediaRepository, localMusicRepository, settingsRepository, appScope)
    }

    override fun onCreate() {
        super.onCreate()
        // '충전 시 자동 실행' 설정에 맞춰 감지 서비스를 켜고 끈다
        appScope.launch {
            settingsRepository.loadedSettings
                .filterNotNull()
                .map { it.chargeAutoLaunch }
                .distinctUntilChanged()
                .collect { enabled -> ChargingWatchService.sync(this@StandByApp, enabled) }
        }
    }

    companion object {
        fun from(context: Context): StandByApp = context.applicationContext as StandByApp
    }
}
