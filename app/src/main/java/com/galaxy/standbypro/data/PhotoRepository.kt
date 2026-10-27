package com.galaxy.standbypro.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * 사진 모드에 표시할 사진 목록.
 * 사용자가 고른 사진(개별 선택)과 폴더(앨범) 안의 사진만 사용하며, 어디에도 업로드하지 않는다.
 * 저장소 전체 권한 대신 Storage Access Framework의 영구 URI 권한만 사용.
 */
class PhotoRepository(context: Context, settingsRepository: SettingsRepository, scope: CoroutineScope) {
    private val appContext = context.applicationContext

    @OptIn(ExperimentalCoroutinesApi::class)
    val photos: StateFlow<List<Uri>> = settingsRepository.settings
        .map { it.photoUris to it.photoFolderUri }
        .distinctUntilChanged()
        .mapLatest { (uris, folder) -> resolve(uris, folder) }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.WhileSubscribed(10_000), emptyList())

    private fun resolve(uris: List<String>, folder: String): List<Uri> {
        val list = LinkedHashSet<Uri>()
        uris.forEach { s -> runCatching { list += Uri.parse(s) } }
        if (folder.isNotBlank()) {
            runCatching {
                val tree = DocumentFile.fromTreeUri(appContext, Uri.parse(folder))
                collectImages(tree, list, depth = 0)
            }
        }
        return list.toList()
    }

    private fun collectImages(dir: DocumentFile?, out: MutableSet<Uri>, depth: Int) {
        if (dir == null || !dir.isDirectory || depth > 2 || out.size >= 1000) return
        val files = dir.listFiles().sortedByDescending { it.lastModified() }
        for (f in files) {
            if (out.size >= 1000) return
            if (f.isDirectory) collectImages(f, out, depth + 1)
            else if (f.type?.startsWith("image/") == true) out += f.uri
        }
    }
}
