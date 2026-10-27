package com.galaxy.standbypro.music

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 다른 음악 앱의 재생 정보를 공식 MediaSessionManager + MediaController로 읽고 제어한다.
 *
 * - 사용자가 '알림 접근'을 허용해야 동작 (MEDIA_CONTENT_CONTROL 같은 시스템 권한은 요구하지 않음)
 * - 권한이 없거나 재생 중인 앱이 없어도 null을 내보낼 뿐 오류를 일으키지 않음
 * - 재생/일시정지/이전/다음/탐색은 해당 세션이 PlaybackState.actions 로 허용한 경우에만 활성화
 */
class ExternalMediaRepository(
    context: Context,
    private val artworkLoader: ArtworkLoader,
    scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val sessionManager: MediaSessionManager? = appContext.getSystemService(MediaSessionManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val labelCache = HashMap<String, String>()

    @Volatile
    private var activeController: MediaController? = null

    private val _accessGranted = MutableStateFlow(MediaNotificationListener.isGranted(appContext))
    val accessGranted: StateFlow<Boolean> = _accessGranted.asStateFlow()

    fun refreshAccess() {
        _accessGranted.value = MediaNotificationListener.isGranted(appContext)
    }

    val nowPlaying: StateFlow<NowPlaying?> = callbackFlow<NowPlaying?> {
        val component = MediaNotificationListener.component(appContext)
        val registered = LinkedHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
        val lastChange = HashMap<MediaSession.Token, Long>()
        var current: MediaController? = null
        var listenerAdded = false

        var artKey: String? = null
        var art: Artwork? = null
        var artJob: Job? = null

        fun pickController(): MediaController? {
            val controllers = registered.values.map { it.first }
            if (controllers.isEmpty()) return null
            val playing = controllers.filter { it.playbackState.isActive() }
            if (playing.isNotEmpty()) {
                val cur = current
                if (cur != null && playing.any { it.sessionToken == cur.sessionToken }) return cur
                return playing.maxByOrNull { lastChange[it.sessionToken] ?: 0L }
            }
            val cur = current
            if (cur != null && registered.containsKey(cur.sessionToken)) return cur
            return controllers.firstOrNull { it.metadata != null }
        }

        fun publish() {
            val c = pickController()
            current = c
            activeController = c
            if (c == null) {
                artJob?.cancel()
                artKey = null
                art = null
                trySend(null)
                return
            }
            val md = c.metadata
            val pkg = c.packageName
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
            val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
            val trackKey = "$pkg|$title|$artist|$album"

            // ── 앨범 커버: 공식 메타데이터 키 우선순위대로 ──
            val bmp: Bitmap? = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            val uri: String? = md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            val wantedKey = when {
                bmp != null -> "b:$trackKey:${bmp.width}x${bmp.height}"
                !uri.isNullOrBlank() -> "u:$uri"
                else -> "none:$trackKey"
            }
            if (wantedKey != artKey) {
                artKey = wantedKey
                artJob?.cancel()
                // 로딩 동안은 이전 커버를 유지해 깜빡임을 막고, 준비되면 크로스페이드로 교체
                artJob = launch {
                    val loaded = when {
                        bmp != null -> artworkLoader.fromBitmap(bmp, wantedKey)
                        !uri.isNullOrBlank() -> artworkLoader.fromUri(uri, wantedKey)
                        else -> null
                    }
                    if (artKey == wantedKey) {
                        art = loaded
                        publish()
                    }
                }
            }

            val ps = c.playbackState
            val actions = ps?.actions ?: 0L
            val playing = ps.isActive()
            val canPlayPause = (actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L ||
                if (playing) (actions and PlaybackState.ACTION_PAUSE) != 0L
                else (actions and PlaybackState.ACTION_PLAY) != 0L

            trySend(
                NowPlaying(
                    source = MusicSource.EXTERNAL,
                    packageName = pkg,
                    appLabel = labelFor(pkg),
                    title = title,
                    artist = artist,
                    album = album,
                    artwork = art,
                    isPlaying = playing,
                    positionMs = ps?.position ?: 0L,
                    positionUpdatedAt = ps?.lastPositionUpdateTime ?: 0L,
                    speed = (ps?.playbackSpeed ?: 1f).let { if (it == 0f) 1f else it },
                    durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L,
                    canPlayPause = canPlayPause,
                    canNext = (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L,
                    canPrevious = (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
                    canSeek = (actions and PlaybackState.ACTION_SEEK_TO) != 0L,
                    lastActiveAt = lastChange[c.sessionToken] ?: (ps?.lastPositionUpdateTime ?: 0L),
                ),
            )
        }

        fun setSessions(list: List<MediaController>?) {
            val filtered = list.orEmpty().filter { it.packageName != appContext.packageName }
            val tokens = filtered.map { it.sessionToken }.toSet()
            val iterator = registered.entries.iterator()
            while (iterator.hasNext()) {
                val (token, pair) = iterator.next()
                if (token !in tokens) {
                    runCatching { pair.first.unregisterCallback(pair.second) }
                    iterator.remove()
                    lastChange.remove(token)
                }
            }
            for (controller in filtered) {
                val token = controller.sessionToken
                if (registered.containsKey(token)) continue
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        val wasActive = registered[token]?.first?.let { current?.sessionToken == token } == true
                        if (state.isActive() || wasActive) lastChange[token] = SystemClock.elapsedRealtime()
                        publish()
                    }

                    override fun onMetadataChanged(metadata: MediaMetadata?) = publish()

                    override fun onSessionDestroyed() {
                        registered.remove(token)?.let { runCatching { it.first.unregisterCallback(it.second) } }
                        publish()
                    }
                }
                runCatching { controller.registerCallback(callback, mainHandler) }
                registered[token] = controller to callback
                lastChange[token] = SystemClock.elapsedRealtime()
            }
            publish()
        }

        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers -> setSessions(controllers) }

        fun tryAttach(): Boolean {
            val manager = sessionManager ?: return false
            return try {
                manager.addOnActiveSessionsChangedListener(listener, component, mainHandler)
                listenerAdded = true
                _accessGranted.value = true
                setSessions(manager.getActiveSessions(component))
                true
            } catch (se: SecurityException) {
                // 알림 접근 권한이 아직 없음 → 시계 등 다른 기능은 그대로 동작
                _accessGranted.value = false
                trySend(null)
                false
            }
        }

        val retry = launch {
            while (!tryAttach()) delay(3_000)
        }

        awaitClose {
            retry.cancel()
            artJob?.cancel()
            if (listenerAdded) runCatching { sessionManager?.removeOnActiveSessionsChangedListener(listener) }
            registered.values.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
            registered.clear()
            activeController = null
        }
    }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    // ───────────── 제어 (세션이 허용한 명령만 UI에서 활성화됨) ─────────────
    fun playPause() {
        val c = activeController ?: return
        runCatching {
            if (c.playbackState.isActive()) c.transportControls.pause() else c.transportControls.play()
        }
    }

    fun next() {
        runCatching { activeController?.transportControls?.skipToNext() }
    }

    fun previous() {
        runCatching { activeController?.transportControls?.skipToPrevious() }
    }

    fun seekTo(positionMs: Long) {
        runCatching { activeController?.transportControls?.seekTo(positionMs) }
    }

    private fun labelFor(pkg: String): String = labelCache.getOrPut(pkg) {
        knownAppLabel(pkg) ?: runCatching {
            val pm = appContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)).toString()
        }.getOrElse { pkg }
    }
}

internal fun PlaybackState?.isActive(): Boolean = when (this?.state) {
    PlaybackState.STATE_PLAYING,
    PlaybackState.STATE_BUFFERING,
    PlaybackState.STATE_FAST_FORWARDING,
    PlaybackState.STATE_REWINDING,
    PlaybackState.STATE_SKIPPING_TO_NEXT,
    PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
    PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
    PlaybackState.STATE_CONNECTING,
    -> true
    else -> false
}
