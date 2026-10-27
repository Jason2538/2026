package com.galaxy.standbypro.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.galaxy.standbypro.StandByApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 재부팅/업데이트 후 '충전 시 자동 실행'이 켜져 있으면 감지 서비스를 다시 시작 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val app = StandByApp.from(context)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = app.settingsRepository.settingsFlow.first()
                if (settings.chargeAutoLaunch) ChargingWatchService.sync(app, true)
            } finally {
                pending.finish()
            }
        }
    }
}
