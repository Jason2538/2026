package com.galaxy.standbypro.ui.standby

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.galaxy.standbypro.StandByApp
import com.galaxy.standbypro.data.AppSettings
import com.galaxy.standbypro.data.StandByPage
import com.galaxy.standbypro.ui.settings.SettingsActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * StandBy 전체 화면 (수동 실행 / 충전 감지 실행 / 삼성 루틴 실행).
 * 가로 고정, 시스템 바 숨김, 화면 켜짐 유지, 잠금 화면 위 표시(설정 시), 충전 해제 시 종료(설정 시).
 */
class StandByActivity : ComponentActivity(), StandByHost {

    private val app by lazy { StandByApp.from(this) }
    private val vm: StandByViewModel by viewModels { StandByViewModel.factory(app) }
    private var fromCharger = false

    private val calendarPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fromCharger = intent?.getBooleanExtra(EXTRA_FROM_CHARGER, false) == true
        handlePageExtra(intent)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()
        // 처음 그리기 전에 저장된 설정을 즉시 반영 (기본값: 가로 + 화면 유지)
        applyWindowSettings(app.settingsRepository.settings.value)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.settingsRepository.loadedSettings.filterNotNull().collect { applyWindowSettings(it) }
            }
        }
        // 충전을 해제하면 종료 (설정 시)
        lifecycleScope.launch {
            var wasPlugged = app.batteryRepository.currentSnapshot().isPluggedIn
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.batteryRepository.battery
                    .combine(app.settingsRepository.settings) { b, s -> b.isPluggedIn to s.exitOnUnplug }
                    .collect { (plugged, exitOnUnplug) ->
                        if (wasPlugged && !plugged && exitOnUnplug) finish()
                        wasPlugged = plugged
                    }
            }
        }

        setContent { StandByRoot(vm, this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FROM_CHARGER, false)) fromCharger = true
        handlePageExtra(intent)
    }

    private fun handlePageExtra(intent: Intent?) {
        val name = intent?.getStringExtra(EXTRA_PAGE) ?: return
        runCatching { StandByPage.valueOf(name) }.getOrNull()?.let { vm.requestPage(it) }
    }

    override fun onStart() {
        super.onStart()
        isVisible = true
    }

    override fun onStop() {
        isVisible = false
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun applyWindowSettings(s: AppSettings) {
        val desired = if (s.forceLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        if (requestedOrientation != desired) requestedOrientation = desired

        val showOverLock = s.showOnLockScreen || fromCharger
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(showOverLock)
            setTurnScreenOn(fromCharger)
        } else {
            legacyShowWhenLocked(showOverLock)
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyShowWhenLocked(show: Boolean) {
        if (show) window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
    }

    // ───────────── StandByHost ─────────────
    override val isDream: Boolean = false

    override fun setWindowBrightness(value: Float) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (value < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else value.coerceIn(0f, 1f)
        }
    }

    override fun setKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun screenOff() {
        // 앱이 직접 화면을 끌 수는 없으므로: 검정 화면 + 최저 밝기 + 화면 유지 해제 → 시스템 화면 꺼짐 시간에 맞춰 꺼짐
        setKeepScreenOn(false)
        setWindowBrightness(0f)
    }

    override fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun requestCalendarPermission() {
        calendarPermission.launch(Manifest.permission.READ_CALENDAR)
    }

    override fun isDeviceLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    override fun close() = finish()

    companion object {
        const val EXTRA_PAGE = "com.galaxy.standbypro.extra.PAGE"
        const val EXTRA_FROM_CHARGER = "com.galaxy.standbypro.extra.FROM_CHARGER"

        /** 충전 감지 서비스가 중복 실행하지 않도록 확인 */
        @Volatile
        var isVisible: Boolean = false
            private set
    }
}
