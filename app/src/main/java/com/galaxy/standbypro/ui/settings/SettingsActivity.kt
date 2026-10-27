package com.galaxy.standbypro.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.galaxy.standbypro.StandByApp
import com.galaxy.standbypro.ui.theme.StandByMaterialTheme

/** 앱 첫 화면: StandBy 시작 버튼 + 권한 상태 + 모든 설정 (한국어) */
class SettingsActivity : ComponentActivity() {
    private val vm: SettingsViewModel by viewModels { SettingsViewModel.factory(StandByApp.from(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            StandByMaterialTheme {
                SettingsScreen(vm)
            }
        }
    }
}
