package com.galaxy.standbypro.ui.standby

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.galaxy.standbypro.StandByApp
import com.galaxy.standbypro.data.AppSettings
import com.galaxy.standbypro.data.BatteryInfo
import com.galaxy.standbypro.data.EventsState
import com.galaxy.standbypro.data.StandByPage
import com.galaxy.standbypro.data.WeatherState
import com.galaxy.standbypro.music.NowPlaying
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** StandBy 화면의 ViewModel (MVVM + StateFlow). Activity와 DreamService가 함께 사용 */
class StandByViewModel(private val app: StandByApp) : ViewModel() {

    val settings: StateFlow<AppSettings?> = app.settingsRepository.loadedSettings
    val battery: StateFlow<BatteryInfo> = app.batteryRepository.battery
    val weather: StateFlow<WeatherState> = app.weatherRepository.weather
    val events: StateFlow<EventsState> = app.calendarRepository.events
    val photos: StateFlow<List<Uri>> = app.photoRepository.photos
    val nowPlaying: StateFlow<NowPlaying?> = app.musicRepository.nowPlaying
    val externalAccess: StateFlow<Boolean> = app.externalMediaRepository.accessGranted
    val localTrackCount: StateFlow<Int> = app.localMusicRepository.playlist
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 알림·인텐트로 요청된 화면. 화면이 준비되기 전에 와도 사라지지 않도록 상태로 보관 */
    private val _pendingPage = MutableStateFlow<StandByPage?>(null)
    val pendingPage: StateFlow<StandByPage?> = _pendingPage.asStateFlow()

    init {
        app.externalMediaRepository.refreshAccess()
        // 내부 재생목록이 있으면 플레이어 서비스에 연결해 진행 중인 재생 상태를 표시
        viewModelScope.launch {
            app.localMusicRepository.playlist.first { it.isNotEmpty() }
            app.localMusicRepository.connect()
        }
    }

    fun requestPage(page: StandByPage) {
        _pendingPage.value = page
    }

    fun consumePageRequest() {
        _pendingPage.value = null
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { app.settingsRepository.update(transform) }
    }

    fun refreshAccess() = app.externalMediaRepository.refreshAccess()

    // ── 음악 제어 ──
    fun playPause() = app.musicRepository.playPause()
    fun next() = app.musicRepository.next()
    fun previous() = app.musicRepository.previous()
    fun seekTo(ms: Long) = app.musicRepository.seekTo(ms)
    fun playLocal(index: Int) = app.localMusicRepository.playAt(index)
    fun toggleShuffle() = app.localMusicRepository.toggleShuffle()
    fun cycleRepeat() = app.localMusicRepository.cycleRepeat()

    companion object {
        fun factory(app: StandByApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { StandByViewModel(app) }
        }
    }
}
