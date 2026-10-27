package com.galaxy.standbypro.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class PlugType(val label: String) { NONE("배터리 사용"), AC("유선 충전"), USB("USB 충전"), WIRELESS("무선 충전"), DOCK("독 충전") }

data class BatteryInfo(
    val levelPercent: Int = 0,
    val plug: PlugType = PlugType.NONE,
    val isCharging: Boolean = false,
    val isFull: Boolean = false,
    val voltageMv: Int = 0,
    val temperatureC: Float = 0f,
    /** 양수 = 충전 전류(mA). 기기가 지원하지 않으면 null */
    val currentMa: Int? = null,
    /** 완충까지 남은 시간(ms). 알 수 없으면 null */
    val timeToFullMs: Long? = null,
) {
    val isPluggedIn: Boolean get() = plug != PlugType.NONE

    /** 추정 충전 전력(W). 알 수 없으면 null */
    val watts: Float?
        get() {
            val ma = currentMa ?: return null
            if (voltageMv <= 0 || ma <= 0) return null
            return voltageMv / 1000f * ma / 1000f
        }

    val speedLabel: String
        get() {
            if (!isPluggedIn) return "충전 안 함"
            if (isFull) return "완충됨"
            val w = watts ?: return if (isCharging) "충전 중" else "연결됨"
            return when {
                w >= 20f -> "초고속 충전"
                w >= 12f -> "고속 충전"
                w >= 4f -> "일반 충전"
                else -> "저속 충전"
            }
        }
}

/**
 * 배터리 상태(레벨, 충전 방식, 전류·전압·온도)를 실시간으로 제공.
 * 구독자가 있을 때만 리시버를 등록하고 5초마다 전류를 다시 읽는다.
 */
class BatteryRepository(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)

    val battery: StateFlow<BatteryInfo> = callbackFlow {
        var lastIntent: Intent? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                lastIntent = intent
                trySend(parse(intent))
            }
        }
        val sticky = ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (sticky != null) {
            lastIntent = sticky
            trySend(parse(sticky))
        }
        val poller = launch {
            while (true) {
                delay(5_000)
                lastIntent?.let { trySend(parse(it)) }
            }
        }
        awaitClose {
            poller.cancel()
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), currentSnapshot())

    /** 리시버 등록 없이 지금 상태를 한 번 읽는다 */
    fun currentSnapshot(): BatteryInfo {
        // receiver 없이 등록하면 마지막 sticky 인텐트만 돌려준다 (시스템 보호 브로드캐스트)
        val intent = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        return intent?.let { parse(it) } ?: BatteryInfo()
    }

    private fun parse(intent: Intent): BatteryInfo {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val percent = if (level >= 0) (level * 100 / scale) else 0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val plug = when {
            plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> PlugType.WIRELESS
            plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> PlugType.AC
            plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> PlugType.USB
            Build.VERSION.SDK_INT >= 33 && plugged and BatteryManager.BATTERY_PLUGGED_DOCK != 0 -> PlugType.DOCK
            plugged != 0 -> PlugType.AC
            else -> PlugType.NONE
        }
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f

        val currentMa = readCurrentMa()
        val timeToFull = if (Build.VERSION.SDK_INT >= 28) {
            runCatching { batteryManager?.computeChargeTimeRemaining() }.getOrNull()?.takeIf { it > 0 }
        } else null

        return BatteryInfo(
            levelPercent = percent.coerceIn(0, 100),
            plug = plug,
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING,
            isFull = status == BatteryManager.BATTERY_STATUS_FULL || (plug != PlugType.NONE && percent >= 100),
            voltageMv = voltage,
            temperatureC = temp,
            currentMa = currentMa,
            timeToFullMs = timeToFull,
        )
    }

    /**
     * BATTERY_PROPERTY_CURRENT_NOW 는 표준이 μA 이지만 일부 삼성 기기는 mA 단위로 보고한다.
     * 값의 크기로 단위를 추정한다.
     */
    private fun readCurrentMa(): Int? {
        val raw = runCatching {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrNull() ?: return null
        if (raw == 0 || raw == Int.MIN_VALUE) return null
        return if (abs(raw) > 20_000) raw / 1000 else raw
    }
}
