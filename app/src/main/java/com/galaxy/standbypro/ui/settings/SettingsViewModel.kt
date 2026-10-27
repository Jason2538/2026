package com.galaxy.standbypro.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.galaxy.standbypro.StandByApp
import com.galaxy.standbypro.data.AppSettings
import com.galaxy.standbypro.data.GeoResult
import com.galaxy.standbypro.data.WeatherState
import com.galaxy.standbypro.music.LocalTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(private val app: StandByApp) : ViewModel() {

    val settings: StateFlow<AppSettings> = app.settingsRepository.settings
    val playlist: StateFlow<List<LocalTrack>> = app.localMusicRepository.playlist
    val weather: StateFlow<WeatherState> = app.weatherRepository.weather

    private val _busy = MutableStateFlow<String?>(null)
    /** 파일을 읽는 중 등 진행 상황 메시지 */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _cityResults = MutableStateFlow<List<GeoResult>>(emptyList())
    val cityResults: StateFlow<List<GeoResult>> = _cityResults.asStateFlow()
    private val _citySearchError = MutableStateFlow<String?>(null)
    val citySearchError: StateFlow<String?> = _citySearchError.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { app.settingsRepository.update(transform) }
    }

    // ───────────── 사진 ─────────────
    fun addPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val resolver = app.contentResolver
        uris.forEach { runCatching { resolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        update { s -> s.copy(photoUris = (s.photoUris + uris.map { it.toString() }).distinct().take(500)) }
    }

    fun setPhotoFolder(uri: Uri?) {
        if (uri == null) return
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        update { it.copy(photoFolderUri = uri.toString()) }
    }

    fun clearPhotos() {
        val s = settings.value
        (s.photoUris + listOfNotNull(s.photoFolderUri.takeIf { it.isNotBlank() })).forEach { str ->
            runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(str), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        update { it.copy(photoUris = emptyList(), photoFolderUri = "") }
    }

    // ───────────── 로컬 음악 ─────────────
    fun addMusicFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _busy.value = "음악 파일 정보를 읽는 중…"
            runCatching { app.localMusicRepository.addFiles(uris) }
            _busy.value = null
        }
    }

    fun addMusicFolder(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _busy.value = "폴더의 음악을 읽는 중…"
            runCatching { app.localMusicRepository.addFolder(uri) }
            _busy.value = null
        }
    }

    fun removeTrack(index: Int) = viewModelScope.launch { app.localMusicRepository.removeAt(index) }
    fun moveTrack(from: Int, to: Int) = viewModelScope.launch { app.localMusicRepository.move(from, to) }
    fun clearPlaylist() = viewModelScope.launch { app.localMusicRepository.clear() }
    fun playTrack(index: Int) = app.localMusicRepository.playAt(index)

    // ───────────── 날씨 지역 ─────────────
    fun searchCity(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _citySearchError.value = null
            val result = runCatching { app.weatherRepository.searchCity(query) }
            result.onSuccess {
                _cityResults.value = it
                if (it.isEmpty()) _citySearchError.value = "검색 결과가 없습니다"
            }.onFailure {
                _cityResults.value = emptyList()
                _citySearchError.value = "검색하지 못했습니다. 인터넷 연결을 확인하세요"
            }
        }
    }

    fun selectCity(g: GeoResult) {
        update { it.copy(weatherCity = g.name, weatherLat = g.lat, weatherLon = g.lon) }
        _cityResults.value = emptyList()
    }

    fun clearCity() = update { it.copy(weatherCity = "", weatherLat = Double.NaN, weatherLon = Double.NaN) }

    fun refreshMusicAccess() = app.externalMediaRepository.refreshAccess()

    companion object {
        fun factory(app: StandByApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(app) }
        }
    }
}
