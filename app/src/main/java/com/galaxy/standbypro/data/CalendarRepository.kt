package com.galaxy.standbypro.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class CalendarEvent(
    val id: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
)

sealed interface EventsState {
    data object NoPermission : EventsState
    data class Ready(val events: List<CalendarEvent>) : EventsState
}

/** 기기 캘린더(삼성 캘린더/구글 캘린더)의 오늘·내일 일정. READ_CALENDAR 권한이 있을 때만 읽는다. */
class CalendarRepository(context: Context, scope: CoroutineScope) {
    private val appContext = context.applicationContext

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    val events: StateFlow<EventsState> = flow<EventsState> {
        while (true) {
            if (!hasPermission()) {
                emit(EventsState.NoPermission)
                delay(30_000)
            } else {
                emit(EventsState.Ready(runCatching { query() }.getOrDefault(emptyList())))
                delay(5 * 60_000L)
            }
        }
    }.flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), EventsState.NoPermission)

    private fun query(): List<CalendarEvent> {
        val now = System.currentTimeMillis()
        val start = now - 60 * 60_000L
        val end = now + 48 * 60 * 60_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
        )
        val result = mutableListOf<CalendarEvent>()
        appContext.contentResolver.query(
            uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val e = CalendarEvent(
                    id = c.getLong(0),
                    title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(제목 없음)",
                    begin = c.getLong(2),
                    end = c.getLong(3),
                    allDay = c.getInt(4) == 1,
                    color = c.getInt(5),
                )
                if (e.end >= now || e.allDay) result += e
                if (result.size >= 12) break
            }
        }
        return result
    }
}
