package com.galaxy.standbypro.dream

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.service.dreams.DreamService
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.galaxy.standbypro.StandByApp
import com.galaxy.standbypro.ui.settings.SettingsActivity
import com.galaxy.standbypro.ui.standby.StandByHost
import com.galaxy.standbypro.ui.standby.StandByRoot
import com.galaxy.standbypro.ui.standby.StandByViewModel

/**
 * 공식 화면 보호기(DreamService). 시스템 설정 > 디스플레이 > 화면 보호기에서 선택하고
 * '충전 중'에 시작하도록 하면, 충전 중 화면이 꺼질 때 자동으로 StandBy가 표시된다.
 *
 * DreamService는 Activity가 아니므로 Compose에 필요한 Lifecycle/SavedState/ViewModelStore를 직접 제공한다.
 */
class StandByDreamService : DreamService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, StandByHost {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true // 터치로 음악 제어·화면 전환 가능
        isFullscreen = true
        val app = StandByApp.from(this)
        val settings = app.settingsRepository.settings.value
        isScreenBright = !settings.useCustomBrightness || settings.brightness > 0.3f

        val vm = ViewModelProvider(this, StandByViewModel.factory(app))[StandByViewModel::class.java]

        // Compose가 창의 최상위 뷰에서 Lifecycle 등을 찾으므로 decorView에도 지정
        window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
        }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@StandByDreamService)
            setViewTreeSavedStateRegistryOwner(this@StandByDreamService)
            setViewTreeViewModelStoreOwner(this@StandByDreamService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { StandByRoot(vm, this@StandByDreamService) }
        }
        setContentView(view)
        if (Build.VERSION.SDK_INT >= 28) {
            window?.let { w ->
                w.attributes = w.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onDreamingStopped() {
        // 화면이 꺼지거나 깨어나면 렌더링/센서/타이머 중지
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        super.onDetachedFromWindow()
    }

    override fun onDestroy() {
        if (lifecycleRegistry.currentState != Lifecycle.State.INITIALIZED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        store.clear()
        super.onDestroy()
    }

    // ───────────── StandByHost ─────────────
    override val isDream: Boolean = true

    override fun setWindowBrightness(value: Float) {
        val w = window ?: return
        w.attributes = w.attributes.apply {
            screenBrightness = if (value < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else value.coerceIn(0f, 1f)
        }
    }

    override fun setKeepScreenOn(on: Boolean) {
        // 화면 보호기는 시스템이 켜짐 상태를 관리
    }

    override fun screenOff() {
        // 화면 보호기를 끝내면 시스템이 화면을 끈다 (충전 중 대기 상태로 돌아감)
        finish()
    }

    override fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (Build.VERSION.SDK_INT >= 29) wakeUp() else finish()
    }

    override fun requestCalendarPermission() = openSettings()

    override fun isDeviceLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    override fun close() {
        if (Build.VERSION.SDK_INT >= 29) wakeUp() else finish()
    }
}
