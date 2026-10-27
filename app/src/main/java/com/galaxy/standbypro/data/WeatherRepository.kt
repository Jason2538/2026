package com.galaxy.standbypro.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class WeatherKind { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, STORM }

data class WeatherInfo(
    val city: String,
    val temperatureC: Double,
    val highC: Double?,
    val lowC: Double?,
    val code: Int,
    val isDay: Boolean,
    val updatedAt: Long,
) {
    val kind: WeatherKind
        get() = when (code) {
            0 -> WeatherKind.CLEAR
            1, 2 -> WeatherKind.PARTLY_CLOUDY
            3 -> WeatherKind.CLOUDY
            45, 48 -> WeatherKind.FOG
            in 51..57 -> WeatherKind.DRIZZLE
            in 61..67, in 80..82 -> WeatherKind.RAIN
            in 71..77, 85, 86 -> WeatherKind.SNOW
            in 95..99 -> WeatherKind.STORM
            else -> WeatherKind.CLOUDY
        }

    val description: String
        get() = when (code) {
            0 -> "맑음"
            1 -> "대체로 맑음"
            2 -> "구름 조금"
            3 -> "흐림"
            45, 48 -> "안개"
            in 51..57 -> "이슬비"
            in 61..65 -> "비"
            66, 67 -> "어는 비"
            in 71..75 -> "눈"
            77 -> "싸락눈"
            in 80..82 -> "소나기"
            85, 86 -> "눈 소나기"
            in 95..99 -> "뇌우"
            else -> "-"
        }
}

sealed interface WeatherState {
    data object NotConfigured : WeatherState
    data object Loading : WeatherState
    data class Ready(val info: WeatherInfo) : WeatherState
    data class Error(val message: String, val last: WeatherInfo?) : WeatherState
}

data class GeoResult(val name: String, val admin: String, val country: String, val lat: Double, val lon: Double)

/**
 * 날씨 위젯 (선택 기능). 키가 필요 없는 Open-Meteo 공개 API를 사용하며,
 * 사용자가 설정에서 지역을 지정했을 때만 네트워크를 사용한다. 위치 권한은 쓰지 않는다.
 */
class WeatherRepository(
    settingsRepository: SettingsRepository,
    scope: CoroutineScope,
) {
    private var lastGood: WeatherInfo? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val weather: StateFlow<WeatherState> = settingsRepository.settings
        .map { Triple(it.weatherCity, it.weatherLat, it.weatherLon) }
        .distinctUntilChanged()
        .flatMapLatest { (city, lat, lon) ->
            if (lat.isNaN() || lon.isNaN()) flowOf<WeatherState>(WeatherState.NotConfigured)
            else flow<WeatherState> {
                if (lastGood == null) emit(WeatherState.Loading) else emit(WeatherState.Ready(lastGood!!))
                while (true) {
                    val result = runCatching { fetch(city, lat, lon) }
                    result.onSuccess {
                        lastGood = it
                        emit(WeatherState.Ready(it))
                    }.onFailure {
                        emit(WeatherState.Error("날씨를 불러오지 못했습니다", lastGood))
                    }
                    // 성공 시 30분, 실패 시 5분 후 재시도
                    delay(if (result.isSuccess) 30 * 60_000L else 5 * 60_000L)
                }
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(10_000), WeatherState.NotConfigured)

    private suspend fun fetch(city: String, lat: Double, lon: Double): WeatherInfo = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
        val json = JSONObject(httpGet(url))
        val current = json.getJSONObject("current")
        val daily = json.optJSONObject("daily")
        WeatherInfo(
            city = city,
            temperatureC = current.getDouble("temperature_2m"),
            highC = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)?.takeIf { !it.isNaN() },
            lowC = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)?.takeIf { !it.isNaN() },
            code = current.optInt("weather_code", 3),
            isDay = current.optInt("is_day", 1) == 1,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** 도시 이름으로 좌표 검색 (설정 화면에서 사용) */
    suspend fun searchCity(query: String): List<GeoResult> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val json = JSONObject(httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=ko&format=json"))
        val arr = json.optJSONArray("results") ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GeoResult(
                name = o.optString("name"),
                admin = o.optString("admin1"),
                country = o.optString("country"),
                lat = o.getDouble("latitude"),
                lon = o.getDouble("longitude"),
            )
        }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
