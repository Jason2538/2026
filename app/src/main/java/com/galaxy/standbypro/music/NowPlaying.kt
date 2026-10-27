package com.galaxy.standbypro.music

import android.graphics.Bitmap
import android.os.SystemClock

enum class MusicSource { EXTERNAL, LOCAL }

/** 커버 이미지 + 커버에서 뽑은 색 */
class Artwork(
    val bitmap: Bitmap,
    /** 검정 배경 위에서 잘 보이는 강조색 (ARGB) */
    val accent: Int,
    /** 그라데이션용 어두운 색 (ARGB) */
    val dark: Int,
    /** 같은 이미지인지 판별하는 키 (크로스페이드 기준) */
    val key: String,
)

/** 현재 재생 중인 곡 정보 (외부 앱 또는 내부 플레이어) */
data class NowPlaying(
    val source: MusicSource,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val artist: String,
    val album: String,
    val artwork: Artwork?,
    val isPlaying: Boolean,
    val positionMs: Long,
    /** positionMs 가 측정된 시점 (SystemClock.elapsedRealtime) */
    val positionUpdatedAt: Long,
    val speed: Float,
    val durationMs: Long,
    val canPlayPause: Boolean,
    val canNext: Boolean,
    val canPrevious: Boolean,
    val canSeek: Boolean,
    /** 마지막으로 재생 상태가 바뀐 시각 (두 소스 중 무엇을 보여줄지 고를 때 사용) */
    val lastActiveAt: Long,
    /** 내부 플레이어 전용 */
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
) {
    val trackKey: String get() = "$packageName|$title|$artist|$album"

    fun currentPosition(now: Long = SystemClock.elapsedRealtime()): Long {
        if (!isPlaying || positionUpdatedAt <= 0L) return positionMs.coerceAtLeast(0L)
        val p = positionMs + ((now - positionUpdatedAt) * speed).toLong()
        return if (durationMs > 0) p.coerceIn(0L, durationMs) else p.coerceAtLeast(0L)
    }
}

/** 잘 알려진 음악 앱 이름 (패키지 정보를 못 읽는 경우 대비) */
internal fun knownAppLabel(pkg: String): String? = when (pkg) {
    "com.spotify.music" -> "Spotify"
    "com.google.android.apps.youtube.music" -> "YouTube Music"
    "com.google.android.youtube" -> "YouTube"
    "com.sec.android.app.music" -> "Samsung Music"
    "com.apple.android.music" -> "Apple Music"
    "com.iloen.melon" -> "멜론"
    "com.ktmusic.geniemusic" -> "지니"
    "com.naver.vibe" -> "VIBE"
    "skplanet.musicmate" -> "FLO"
    "com.soundcloud.android" -> "SoundCloud"
    else -> null
}
