package com.galaxy.standbypro.music

import com.galaxy.standbypro.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 외부 음악 앱과 내부 플레이어를 하나로 합친 '지금 재생 중' 정보.
 * 재생 중인 쪽을 우선하고, 둘 다 멈췄으면 마지막으로 활동한 쪽을 보여준다.
 */
class MusicRepository(
    val external: ExternalMediaRepository,
    val local: LocalMusicRepository,
    settingsRepository: SettingsRepository,
    scope: CoroutineScope,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val externalIfEnabled = settingsRepository.settings
        .map { it.musicEnabled && it.externalMusicEnabled }
        .distinctUntilChanged()
        .flatMapLatest { enabled -> if (enabled) external.nowPlaying else flowOf<NowPlaying?>(null) }

    val nowPlaying: StateFlow<NowPlaying?> = combine(
        externalIfEnabled,
        local.nowPlaying,
        settingsRepository.settings.map { it.musicEnabled }.distinctUntilChanged(),
    ) { ext, loc, enabled ->
        if (!enabled) null else choose(ext, loc)
    }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    private fun choose(ext: NowPlaying?, loc: NowPlaying?): NowPlaying? = when {
        loc == null -> ext
        ext == null -> loc
        loc.isPlaying && !ext.isPlaying -> loc
        ext.isPlaying && !loc.isPlaying -> ext
        else -> if (loc.lastActiveAt >= ext.lastActiveAt) loc else ext
    }

    fun playPause() = when (nowPlaying.value?.source) {
        MusicSource.LOCAL -> local.playPause()
        MusicSource.EXTERNAL -> external.playPause()
        null -> Unit
    }

    fun next() = when (nowPlaying.value?.source) {
        MusicSource.LOCAL -> local.next()
        MusicSource.EXTERNAL -> external.next()
        null -> Unit
    }

    fun previous() = when (nowPlaying.value?.source) {
        MusicSource.LOCAL -> local.previous()
        MusicSource.EXTERNAL -> external.previous()
        null -> Unit
    }

    fun seekTo(positionMs: Long) = when (nowPlaying.value?.source) {
        MusicSource.LOCAL -> local.seekTo(positionMs)
        MusicSource.EXTERNAL -> external.seekTo(positionMs)
        null -> Unit
    }
}
