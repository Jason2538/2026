package com.galaxy.standbypro.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.galaxy.standbypro.R
import com.galaxy.standbypro.StandByApp
import com.galaxy.standbypro.ui.settings.SettingsActivity
import com.galaxy.standbypro.ui.standby.StandByActivity
import kotlin.math.abs

/**
 * 선택 기능: '충전 연결 + 가로로 놓기'를 감지해 StandBy를 띄운다.
 *
 * Android 10 이상은 백그라운드 앱이 화면을 마음대로 띄우는 것을 막는다.
 * 사용자가 '다른 앱 위에 표시' 권한을 직접 허용한 경우에만 공식 예외로 실행할 수 있고,
 * 허용하지 않았다면 알림으로만 안내한다. (접근성 권한 등 우회 수단은 사용하지 않음)
 * 충전 중일 때만 가속도 센서를 낮은 빈도로 읽어 배터리 영향을 최소화한다.
 */
class ChargingWatchService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var listening = false

    private var landscapeSince = 0L
    private var notLandscapeSince = 0L
    private var armed = true

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    armed = true
                    startOrientationWatch()
                }
                Intent.ACTION_POWER_DISCONNECTED -> stopOrientationWatch()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        sensorManager = getSystemService(SensorManager::class.java)
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, powerReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (isPluggedIn()) startOrientationWatch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService가 여러 번 호출돼도 매번 포그라운드 상태를 확인 (5초 제한 대응)
        startAsForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        stopOrientationWatch()
        runCatching { unregisterReceiver(powerReceiver) }
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.charging_channel_name), NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_standby)
            .setContentTitle(getString(R.string.charging_notification_title))
            .setContentText(getString(R.string.charging_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun isPluggedIn(): Boolean {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun startOrientationWatch() {
        val settings = StandByApp.from(this).settingsRepository.settings.value
        if (!settings.autoLaunchNeedsLandscape) {
            // 가로 조건 없이 충전 연결만으로 실행
            launchStandBy()
            return
        }
        if (listening) return
        val sensor = accelerometer
        if (sensor == null) {
            launchStandBy()
            return
        }
        landscapeSince = 0L
        notLandscapeSince = 0L
        listening = sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 500_000) == true
    }

    private fun stopOrientationWatch() {
        if (listening) sensorManager?.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        // 휴대폰이 옆으로 세워진 상태: 중력이 주로 x축, 바닥에 눕혀진(z축) 상태는 제외
        val landscape = abs(x) > 6.0f && abs(y) < 5.0f && abs(z) < 7.5f
        val now = SystemClock.elapsedRealtime()
        if (landscape) {
            notLandscapeSince = 0L
            if (landscapeSince == 0L) landscapeSince = now
            if (armed && now - landscapeSince > 1_500 && !StandByActivity.isVisible) {
                armed = false
                launchStandBy()
            }
        } else {
            landscapeSince = 0L
            if (notLandscapeSince == 0L) notLandscapeSince = now
            // 가로 상태를 벗어난 뒤 다시 가로로 놓으면 재실행 허용
            if (now - notLandscapeSince > 3_000 && !StandByActivity.isVisible) armed = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun launchStandBy() {
        if (StandByActivity.isVisible) return
        val intent = Intent(this, StandByActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(StandByActivity.EXTRA_FROM_CHARGER, true)
        if (Settings.canDrawOverlays(this)) {
            runCatching { startActivity(intent) }
        } else {
            // 권한이 없으면 화면을 강제로 띄우지 않고 알림으로 안내
            val pi = PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_standby)
                .setContentTitle("StandBy 열기")
                .setContentText("탭하면 StandBy를 표시합니다. 자동 표시는 '다른 앱 위에 표시' 허용이 필요합니다.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            runCatching { getSystemService(NotificationManager::class.java).notify(PROMPT_ID, n) }
        }
    }

    companion object {
        private const val CHANNEL_ID = "charging_watch"
        private const val NOTIFICATION_ID = 41
        private const val PROMPT_ID = 42

        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, ChargingWatchService::class.java)
            if (enabled) {
                // 백그라운드에서 시작이 막힌 경우(Android 12+)에는 조용히 무시 → 다음에 앱을 열거나 재부팅 시 시작
                runCatching { ContextCompat.startForegroundService(context, intent) }
            } else {
                runCatching { context.stopService(intent) }
            }
        }
    }
}
