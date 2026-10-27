package com.galaxy.standbypro.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Context.musicStore: DataStore<Preferences> by preferencesDataStore(name = "local_music")

data class LocalTrack(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

/**
 * 내부 음악 플레이어의 재생목록과 상태.
 * 재생목록은 사용자가 Storage Access Framework로 고른 파일만 담고(전체 저장소 권한 없음),
 * DataStore에 저장해 앱을 다시 열어도 유지된다.
 */
class LocalMusicRepository(
    context: Context,
    private val artworkLoader: ArtworkLoader,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val playlistKey = stringPreferencesKey("playlist_json")

    val playlist: StateFlow<List<LocalTrack>> = appContext.musicStore.data
        .map { prefs -> parse(prefs[playlistKey]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()

    private var artMediaId: String? = null
    private var artwork: Artwork? = null
    private var artJob: Job? = null
    private var lastActiveAt = 0L

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            ) {
                lastActiveAt = SystemClock.elapsedRealtime()
            }
            publish()
        }
    }

    /** 플레이어 서비스에 연결 (이미 연결되어 있으면 무시) */
    fun connect() {
        if (controller != null || controllerFuture != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull()
            if (c == null) {
                controllerFuture = null
                return@addListener
            }
            controller = c
            c.addListener(listener)
            val actions = pending.toList()
            pending.clear()
            actions.forEach { runCatching { it(c) } }
            publish()
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun release() {
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        _nowPlaying.value = null
    }

    private fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) action(c) else {
            pending += action
            connect()
        }
    }

    // ───────────── 재생목록 관리 ─────────────

    suspend fun addFiles(uris: List<Uri>) {
        val tracks = withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                runCatching {
                    appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                readTrack(uri, null)
            }
        }
        appendTracks(tracks)
    }

    suspend fun addFolder(treeUri: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val tracks = withContext(Dispatchers.IO) {
            val files = ArrayList<DocumentFile>()
            collectAudio(DocumentFile.fromTreeUri(appContext, treeUri), files, 0)
            files.sortedBy { it.name?.lowercase() ?: "" }.mapNotNull { f -> readTrack(f.uri, f.name) }
        }
        appendTracks(tracks)
    }

    private fun collectAudio(dir: DocumentFile?, out: MutableList<DocumentFile>, depth: Int) {
        if (dir == null || !dir.isDirectory || depth > 3 || out.size >= 2000) return
        for (f in dir.listFiles()) {
            if (out.size >= 2000) return
            if (f.isDirectory) collectAudio(f, out, depth + 1)
            else if (f.type?.startsWith("audio/") == true) out += f
        }
    }

    private suspend fun appendTracks(tracks: List<LocalTrack>) {
        if (tracks.isEmpty()) return
        val current = playlist.value
        val existing = current.map { it.uri }.toSet()
        val fresh = tracks.filter { it.uri !in existing }
        if (fresh.isEmpty()) return
        save(current + fresh)
        withController { c ->
            if (isSynced(c, current)) c.addMediaItems(fresh.map { it.toMediaItem() })
        }
    }

    suspend fun removeAt(index: Int) {
        val current = playlist.value
        if (index !in current.indices) return
        save(current.toMutableList().also { it.removeAt(index) })
        controller?.let { c -> if (isSynced(c, current)) c.removeMediaItem(index) }
    }

    suspend fun move(from: Int, to: Int) {
        val current = playlist.value
        if (from !in current.indices || to !in current.indices || from == to) return
        val list = current.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        save(list)
        controller?.let { c -> if (isSynced(c, current)) c.moveMediaItem(from, to) }
    }

    suspend fun clear() {
        save(emptyList())
        controller?.let { c ->
            c.stop()
            c.clearMediaItems()
        }
        publish()
    }

    // ───────────── 재생 제어 ─────────────

    fun playAt(index: Int) {
        val list = playlist.value
        if (index !in list.indices) return
        withController { c ->
            if (!isSynced(c, list)) {
                c.setMediaItems(list.map { it.toMediaItem() }, index, 0L)
            } else {
                c.seekTo(index, 0L)
            }
            c.prepare()
            c.play()
        }
    }

    fun playPause() {
        withController { c ->
            if (c.mediaItemCount == 0) {
                if (playlist.value.isNotEmpty()) playAt(0)
                return@withController
            }
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.isPlaying) c.pause() else c.play()
        }
    }

    fun next() = withController { if (it.hasNextMediaItem()) it.seekToNextMediaItem() else it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // ───────────── 상태 → NowPlaying ─────────────

    private fun publish() {
        val c = controller
        val item = c?.currentMediaItem
        if (c == null || item == null || c.mediaItemCount == 0) {
            _nowPlaying.value = null
            return
        }
        val mediaId = item.mediaId
        if (mediaId != artMediaId) {
            artMediaId = mediaId
            artJob?.cancel()
            val uri = Uri.parse(mediaId)
            artJob = scope.launch {
                val loaded = artworkLoader.fromAudioFile(uri, "local:$mediaId")
                if (artMediaId == mediaId) {
                    artwork = loaded
                    publish()
                }
            }
        }
        val md = item.mediaMetadata
        val playerMd = c.mediaMetadata
        val track = playlist.value.firstOrNull { it.uri == mediaId }
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (track?.durationMs ?: 0L)
        _nowPlaying.value = NowPlaying(
            source = MusicSource.LOCAL,
            packageName = appContext.packageName,
            appLabel = "내 음악",
            title = (playerMd.title ?: md.title)?.toString() ?: track?.title ?: "알 수 없는 곡",
            artist = (playerMd.artist ?: md.artist)?.toString() ?: track?.artist ?: "",
            album = (playerMd.albumTitle ?: md.albumTitle)?.toString() ?: track?.album ?: "",
            artwork = if (artMediaId == mediaId) artwork else null,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition,
            positionUpdatedAt = SystemClock.elapsedRealtime(),
            speed = c.playbackParameters.speed,
            durationMs = duration,
            canPlayPause = c.isCommandAvailable(Player.COMMAND_PLAY_PAUSE),
            canNext = c.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT),
            canPrevious = c.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS),
            canSeek = c.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
            lastActiveAt = lastActiveAt,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
        )
    }

    // ───────────── 내부 도우미 ─────────────

    private fun isSynced(c: MediaController, list: List<LocalTrack>): Boolean {
        if (c.mediaItemCount != list.size || list.isEmpty()) return false
        for (i in list.indices) if (c.getMediaItemAt(i).mediaId != list[i].uri) return false
        return true
    }

    private fun LocalTrack.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(uri)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist.ifBlank { null })
                .setAlbumTitle(album.ifBlank { null })
                .build(),
        )
        .build()

    /** MediaMetadataRetriever로 제목/가수/앨범/길이 읽기 */
    private fun readTrack(uri: Uri, fallbackName: String?): LocalTrack? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val name = fallbackName ?: DocumentFile.fromSingleUri(appContext, uri)?.name ?: "알 수 없는 곡"
            LocalTrack(
                uri = uri.toString(),
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                    ?: name.substringBeforeLast('.'),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "",
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "",
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
            )
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private suspend fun save(list: List<LocalTrack>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(
                JSONObject()
                    .put("uri", t.uri)
                    .put("title", t.title)
                    .put("artist", t.artist)
                    .put("album", t.album)
                    .put("duration", t.durationMs),
            )
        }
        appContext.musicStore.edit { it[playlistKey] = arr.toString() }
        // StateFlow가 갱신될 때까지 기다려 이후 연산이 최신 목록을 보도록 함
        playlist.first { it.size == list.size }
    }

    private fun parse(json: String?): List<LocalTrack> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LocalTrack(
                    uri = o.getString("uri"),
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    album = o.optString("album"),
                    durationMs = o.optLong("duration"),
                )
            }
        }.getOrDefault(emptyList())
    }
}
