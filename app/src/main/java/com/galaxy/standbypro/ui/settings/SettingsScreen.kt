package com.galaxy.standbypro.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.standbypro.BuildConfig
import com.galaxy.standbypro.data.ClockStyle
import com.galaxy.standbypro.data.HourFormat
import com.galaxy.standbypro.data.MusicBackground
import com.galaxy.standbypro.data.NightModeSetting
import com.galaxy.standbypro.data.StandByPage
import com.galaxy.standbypro.data.WeatherState
import com.galaxy.standbypro.music.MediaNotificationListener
import com.galaxy.standbypro.system.ChargingWatchService
import com.galaxy.standbypro.ui.standby.CityNames
import com.galaxy.standbypro.ui.standby.ColorChips
import com.galaxy.standbypro.ui.standby.EditPanel
import com.galaxy.standbypro.ui.standby.StandByActivity
import com.galaxy.standbypro.ui.standby.cityName
import com.galaxy.standbypro.ui.standby.formatDuration
import com.galaxy.standbypro.ui.standby.widgetEditContent
import kotlin.math.roundToInt

private enum class SettingsDialog { STACKS, WORLD, CITY, PLAYLIST }

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    // 다른 화면(시스템 설정)에서 돌아오면 권한 상태 다시 확인
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refresh++
        vm.refreshMusicAccess()
    }
    val notifAccess = remember(refresh) { MediaNotificationListener.isGranted(context) }
    val overlayOk = remember(refresh) { Settings.canDrawOverlays(context) }
    val calendarOk = remember(refresh) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }
    val postNotifOk = remember(refresh) {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val hasLightSensor = remember {
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_LIGHT) != null
    }

    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

    // ── 파일 선택기 (Storage Access Framework: 고른 파일만 접근) ──
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addPhotos(it) }
    val pickPhotoFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.setPhotoFolder(it) }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addMusicFiles(it) }
    val pickMusicFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.addMusicFolder(it) }
    val calendarPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    val update: ((com.galaxy.standbypro.data.AppSettings) -> com.galaxy.standbypro.data.AppSettings) -> Unit = { vm.update(it) }

    Scaffold(containerColor = Color.Black) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp)) {

            // ═══════════ 머리말 + 시작 버튼 ═══════════
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp)) {
                    Text("Galaxy StandBy Pro", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("충전 중 가로로 놓고 쓰는 탁상 시계·위젯·음악 화면", color = SubText, fontSize = 15.sp)
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            context.startActivity(Intent(context, StandByActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.Black),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Text("StandBy 시작", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    if (busy != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(busy ?: "", color = Orange, fontSize = 14.sp)
                    }
                }
            }

            // ═══════════ 준비 상태 ═══════════
            item {
                SettingsGroup("준비 상태", footer = "Android 13 이상에서 직접 설치한 앱은 '알림 접근'이 회색으로 막혀 있을 수 있습니다. 이때는 '앱 정보 열기' → 오른쪽 위 ⋮ → '제한된 설정 허용'을 누른 뒤 다시 시도하세요.") {
                    StatusItem(
                        "화면 보호기로 선택", ok = false, okText = "", needText = "설정 열기",
                        subtitle = "충전 중 자동 표시의 핵심. 디스플레이 > 화면 보호기에서 이 앱 선택",
                        onClick = { openDreamSettings(context) },
                    )
                    ItemDivider()
                    StatusItem(
                        "음악 정보 (알림 접근)", ok = notifAccess, okText = "허용됨", needText = "허용 필요",
                        subtitle = "Spotify·YouTube Music·Samsung Music 등의 곡/커버/제어",
                        onClick = { openNotificationAccess(context) },
                    )
                    ItemDivider()
                    NavItem("앱 정보 열기 (제한된 설정 허용)", onClick = { openAppDetails(context) })
                    if (Build.VERSION.SDK_INT >= 33) {
                        ItemDivider()
                        StatusItem(
                            "알림 표시", ok = postNotifOk, okText = "허용됨", needText = "허용",
                            subtitle = "음악 재생 알림·충전 감지 안내에 사용",
                            onClick = { if (!postNotifOk) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else openAppDetails(context) },
                        )
                    }
                }
            }

            // ═══════════ 화면 ═══════════
            item {
                SettingsGroup("화면") {
                    ChoiceItem("시작 화면", StandByPage.entries, s.startPage, { it.label }, { p -> update { it.copy(startPage = p) } })
                    ItemDivider()
                    ChoiceItem("시계 스타일", ClockStyle.entries, s.clockStyle, { it.label }, { c -> update { it.copy(clockStyle = c) } })
                    ItemDivider()
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("시계 색상", color = Color.White, fontSize = 16.sp)
                        Spacer(Modifier.height(8.dp))
                        ColorChips(s.clockColorIndex) { i -> update { it.copy(clockColorIndex = i) } }
                    }
                    ItemDivider()
                    ChoiceItem("시간 표시", HourFormat.entries, s.hourFormat, { it.label }, { f -> update { it.copy(hourFormat = f) } })
                    ItemDivider()
                    SwitchItem("초침 표시", s.showSeconds, { v -> update { it.copy(showSeconds = v) } })
                    ItemDivider()
                    SwitchItem("날짜 표시", s.showDate, { v -> update { it.copy(showDate = v) } })
                }
            }

            // ═══════════ 위젯 ═══════════
            item {
                SettingsGroup("위젯", footer = "StandBy 화면을 길게 눌러도 위젯을 편집할 수 있습니다. 각 스택은 위아래로 밀어 바꿉니다.") {
                    NavItem(
                        "위젯 배치 편집",
                        onClick = { dialog = SettingsDialog.STACKS },
                        value = "${s.leftStack.size} + ${s.rightStack.size}개",
                        subtitle = "순서·종류·크기·강조 색",
                    )
                    ItemDivider()
                    NavItem(
                        "세계 시계 도시",
                        onClick = { dialog = SettingsDialog.WORLD },
                        value = s.worldClockZones.joinToString(", ") { cityName(it) },
                    )
                    ItemDivider()
                    NavItem(
                        "날씨 지역",
                        onClick = { dialog = SettingsDialog.CITY },
                        value = if (s.hasWeatherLocation) s.weatherCity else "선택 안 함",
                        subtitle = when (val w = weather) {
                            is WeatherState.Ready -> "현재 ${Math.round(w.info.temperatureC)}° ${w.info.description}"
                            is WeatherState.Error -> w.message
                            else -> "인터넷 연결 시 Open-Meteo에서 받아옵니다 (위치 권한 사용 안 함)"
                        },
                    )
                    ItemDivider()
                    StatusItem(
                        "일정 위젯 (캘린더 권한)", ok = calendarOk, okText = "허용됨", needText = "허용",
                        onClick = { if (!calendarOk) calendarPerm.launch(Manifest.permission.READ_CALENDAR) else openAppDetails(context) },
                    )
                }
            }

            // ═══════════ 사진 ═══════════
            item {
                SettingsGroup("사진 모드", footer = "선택한 사진은 기기 안에서만 사용하며 어디에도 업로드하지 않습니다.") {
                    NavItem("사진 선택", onClick = { pickPhotos.launch(arrayOf("image/*")) }, value = "${s.photoUris.size}장")
                    ItemDivider()
                    NavItem(
                        "앨범(폴더) 선택",
                        onClick = { pickPhotoFolder.launch(null) },
                        value = if (s.photoFolderUri.isBlank()) "선택 안 함" else (Uri.parse(s.photoFolderUri).lastPathSegment?.substringAfterLast(':') ?: "선택됨"),
                    )
                    ItemDivider()
                    NavItem("선택한 사진 모두 지우기", onClick = { vm.clearPhotos() }, titleColor = RedWarn)
                    ItemDivider()
                    ChoiceItem(
                        "슬라이드 간격", listOf(5, 10, 15, 30, 60, 120, 300, 600), s.slideshowSeconds,
                        { if (it < 60) "${it}초" else "${it / 60}분" }, { v -> update { it.copy(slideshowSeconds = v) } },
                    )
                    ItemDivider()
                    SwitchItem("사진 위 시계 표시", s.photoShowClock, { v -> update { it.copy(photoShowClock = v) } })
                    ItemDivider()
                    SwitchItem("날짜 표시", s.photoShowDate, { v -> update { it.copy(photoShowDate = v) } })
                    ItemDivider()
                    SwitchItem("화면 가득 채우기", s.photoFill, { v -> update { it.copy(photoFill = v) } }, "끄면 사진 전체가 보이게 맞춤")
                    ItemDivider()
                    SwitchItem("무작위 순서", s.photoShuffle, { v -> update { it.copy(photoShuffle = v) } })
                }
            }

            // ═══════════ 음악 ═══════════
            item {
                SettingsGroup(
                    "음악",
                    footer = "다른 앱 연동은 Android 공식 MediaSession을 사용합니다. 앱이 허용한 명령(재생/일시정지/이전/다음/탐색)만 버튼이 활성화됩니다. 앨범 커버는 음악 앱이 제공한 이미지만 사용합니다.",
                ) {
                    SwitchItem("음악 플레이어 사용", s.musicEnabled, { v -> update { it.copy(musicEnabled = v) } })
                    ItemDivider()
                    SwitchItem(
                        "외부 음악 앱 연동", s.externalMusicEnabled, { v -> update { it.copy(externalMusicEnabled = v) } },
                        subtitle = "Spotify, YouTube Music, Samsung Music, Apple Music 등", enabled = s.musicEnabled,
                    )
                    ItemDivider()
                    StatusItem(
                        "알림 접근 권한", ok = notifAccess, okText = "허용됨", needText = "허용하기",
                        subtitle = "다른 앱의 재생 정보를 읽는 데만 사용하며 알림 내용은 저장하지 않음",
                        onClick = { openNotificationAccess(context) },
                    )
                    ItemDivider()
                    SwitchItem("앨범 커버 표시", s.showAlbumArt, { v -> update { it.copy(showAlbumArt = v) } }, enabled = s.musicEnabled)
                    ItemDivider()
                    SwitchItem("음악 정보 표시", s.showMusicInfo, { v -> update { it.copy(showMusicInfo = v) } }, enabled = s.musicEnabled)
                    ItemDivider()
                    SwitchItem("재생 시 음악 화면으로 자동 전환", s.autoSwitchToMusic, { v -> update { it.copy(autoSwitchToMusic = v) } }, enabled = s.musicEnabled)
                    ItemDivider()
                    ChoiceItem("배경 효과", MusicBackground.entries, s.musicBackground, { it.label }, { b -> update { it.copy(musicBackground = b) } }, enabled = s.musicEnabled)
                    ItemDivider()
                    SliderItem("커버 크기", s.coverScale, 0.5f..1f, { "${(it * 100).roundToInt()}%" }, { v -> update { it.copy(coverScale = v) } })
                    ItemDivider()
                    SwitchItem("진행 바 표시", s.showProgress, { v -> update { it.copy(showProgress = v) } })
                    ItemDivider()
                    SwitchItem("시계와 음악 함께 표시", s.showClockWithMusic, { v -> update { it.copy(showClockWithMusic = v) } })
                    ItemDivider()
                    SwitchItem(
                        "잠금 화면에서 곡 정보 숨기기", s.hideMusicWhenLocked, { v -> update { it.copy(hideMusicWhenLocked = v) } },
                        subtitle = "잠겨 있을 때 제목·가수·커버 대신 '음악 재생 중'만 표시",
                    )
                }
            }
            item {
                SettingsGroup("내 음악 (앱 자체 플레이어)", footer = "선택한 파일·폴더에만 접근합니다. 전체 저장소 권한은 사용하지 않습니다.") {
                    NavItem("음악 파일 추가", onClick = { pickMusic.launch(arrayOf("audio/*")) })
                    ItemDivider()
                    NavItem("음악 폴더 추가", onClick = { pickMusicFolder.launch(null) })
                    ItemDivider()
                    NavItem("재생목록 관리", onClick = { dialog = SettingsDialog.PLAYLIST }, value = "${playlist.size}곡")
                }
            }

            // ═══════════ 번인 방지 ═══════════
            item {
                SettingsGroup(
                    "번인 방지 (OLED 보호)",
                    footer = "이 기능들은 번인 가능성을 줄여 주지만 번인을 완전히 막는다고 보장할 수는 없습니다. 밝기 조절은 이 앱 화면에만 적용되며 휴대폰의 밝기 설정은 바꾸지 않습니다.",
                ) {
                    SwitchItem("번인 방지 사용", s.burnInEnabled, { v -> update { it.copy(burnInEnabled = v) } }, "시계·위젯·커버·버튼을 주기적으로 조금씩 이동")
                    ItemDivider()
                    ChoiceItem(
                        "픽셀 이동 간격", listOf(30, 60, 90, 120, 180, 300, 600), s.shiftIntervalSec,
                        { if (it < 60) "${it}초" else if (it % 60 == 0) "${it / 60}분" else "${it / 60}분 ${it % 60}초" },
                        { v -> update { it.copy(shiftIntervalSec = v) } }, enabled = s.burnInEnabled,
                    )
                    ItemDivider()
                    SliderItem("최대 이동 거리", s.shiftMaxDp.toFloat(), 2f..24f, { "${it.roundToInt()}dp" }, { v -> update { it.copy(shiftMaxDp = v.roundToInt()) } }, enabled = s.burnInEnabled)
                    ItemDivider()
                    SwitchItem("부드럽게 이동", s.smoothShift, { v -> update { it.copy(smoothShift = v) } }, "끄면 즉시 이동", enabled = s.burnInEnabled)
                    ItemDivider()
                    SwitchItem("OLED 보호 모드", s.oledProtect, { v -> update { it.copy(oledProtect = v) } }, "흰 요소 밝기 감소·그라데이션 약하게")
                    ItemDivider()
                    SliderItem("앨범 커버 밝기", s.coverBrightness, 0.2f..1f, { "${(it * 100).roundToInt()}%" }, { v -> update { it.copy(coverBrightness = v) } })
                    ItemDivider()
                    ChoiceItem("커버 자동 축소", minuteOptions, s.coverShrinkAfterMin, { if (it == 0) "끄기" else minuteLabel(it) + " 후" }, { v -> update { it.copy(coverShrinkAfterMin = v) } })
                    ItemDivider()
                    SwitchItem("앨범 커버 숨기기", s.hideCover, { v -> update { it.copy(hideCover = v) } })
                    ItemDivider()
                    ChoiceItem("음악 화면 → 시계 화면 전환", minuteOptions, s.musicToClockAfterMin, { if (it == 0) "끄기" else minuteLabel(it) + " 후" }, { v -> update { it.copy(musicToClockAfterMin = v) } })
                    ItemDivider()
                    ChoiceItem("화면 어둡게", minuteOptions, s.dimAfterMin, { if (it == 0) "끄기" else minuteLabel(it) + " 후" }, { v -> update { it.copy(dimAfterMin = v) } }, subtitle = "조작이 없을 때")
                    ItemDivider()
                    SliderItem("어둡게 정도", s.dimLevel, 0.1f..0.9f, { "${(it * 100).roundToInt()}%" }, { v -> update { it.copy(dimLevel = v) } }, enabled = s.dimAfterMin > 0)
                    ItemDivider()
                    ChoiceItem("자동 화면 끄기", minuteOptions, s.screenOffAfterMin, { if (it == 0) "끄기" else minuteLabel(it) + " 후" }, { v -> update { it.copy(screenOffAfterMin = v) } }, subtitle = "조작이 없을 때 검정 화면으로 전환")
                    ItemDivider()
                    SwitchItem("화면 밝기 직접 지정", s.useCustomBrightness, { v -> update { it.copy(useCustomBrightness = v) } })
                    ItemDivider()
                    SliderItem("StandBy 밝기", s.brightness, 0.01f..1f, { "${(it * 100).roundToInt()}%" }, { v -> update { it.copy(brightness = v) } }, enabled = s.useCustomBrightness)
                    ItemDivider()
                    SliderItem("야간 밝기", s.nightBrightness, 0.01f..0.6f, { "${(it * 100).roundToInt()}%" }, { v -> update { it.copy(nightBrightness = v) } })
                }
            }

            // ═══════════ 야간 모드 ═══════════
            item {
                SettingsGroup("야간 모드", footer = "야간에는 모든 요소가 붉은 단색으로 바뀌고 밝기와 애니메이션이 줄어듭니다.") {
                    ChoiceItem("야간 모드", NightModeSetting.entries, s.nightMode, { it.label }, { m -> update { it.copy(nightMode = m) } })
                    ItemDivider()
                    SwitchItem(
                        "조도 센서로 감지", s.nightUseLightSensor, { v -> update { it.copy(nightUseLightSensor = v) } },
                        subtitle = if (hasLightSensor) "주변이 어두우면 자동 전환" else "이 기기에서 조도 센서를 찾지 못해 시간 기준으로 전환합니다",
                        enabled = s.nightMode == NightModeSetting.AUTO && hasLightSensor,
                    )
                    ItemDivider()
                    SliderItem(
                        "조도 기준", s.nightLuxThreshold, 1f..30f, { "${it.roundToInt()} lux 이하" },
                        { v -> update { it.copy(nightLuxThreshold = v) } },
                        enabled = s.nightMode == NightModeSetting.AUTO && s.nightUseLightSensor && hasLightSensor,
                    )
                    ItemDivider()
                    ChoiceItem("시작 시각 (시간 기준)", (0..23).toList(), s.nightStartHour, { "${it}시" }, { h -> update { it.copy(nightStartHour = h) } }, enabled = s.nightMode == NightModeSetting.AUTO)
                    ItemDivider()
                    ChoiceItem("종료 시각 (시간 기준)", (0..23).toList(), s.nightEndHour, { "${it}시" }, { h -> update { it.copy(nightEndHour = h) } }, enabled = s.nightMode == NightModeSetting.AUTO)
                    ItemDivider()
                    SwitchItem("야간에는 붉은 시계만 표시", s.nightClockOnly, { v -> update { it.copy(nightClockOnly = v) } })
                }
            }

            // ═══════════ 자동 실행 ═══════════
            item {
                SettingsGroup("자동 실행") {
                    NavItem("화면 보호기 설정 열기", onClick = { openDreamSettings(context) }, subtitle = "'Galaxy StandBy Pro' 선택 → 시작 시점 '충전 중'")
                    ItemDivider()
                    InfoText(
                        "충전 중 자동으로 띄우는 방법\n" +
                            "① 가장 확실한 방법 (공식 화면 보호기)\n" +
                            "   휴대폰 설정 > 디스플레이 > 화면 보호기 > 'Galaxy StandBy Pro' 선택 > '충전 중'에 시작. " +
                            "충전 중 화면이 자동으로 꺼질 때 StandBy가 나타납니다.\n" +
                            "② 삼성 '모드 및 루틴'\n" +
                            "   조건: '충전 상태'(충전 중) + 필요하면 '화면 방향'(가로) → 동작: '앱 열기' > 'StandBy 시작'.\n" +
                            "③ 아래 '충전 연결 시 자동 실행' (실험 기능)\n\n" +
                            "Android 보안 정책상 일반 앱이 충전기 연결만으로 잠금 화면 위에 항상 자동 실행된다고 보장할 수는 없습니다. " +
                            "이 앱은 권한을 우회하거나 접근성 권한을 쓰지 않습니다.",
                    )
                    ItemDivider()
                    NavItem("모드 및 루틴 열기", onClick = { openRoutines(context) })
                    ItemDivider()
                    SwitchItem(
                        "충전 연결 시 자동 실행 (실험)", s.chargeAutoLaunch,
                        { v -> update { it.copy(chargeAutoLaunch = v) }; ChargingWatchService.sync(context, v) },
                        subtitle = "작은 상시 알림과 함께 충전 연결을 감지합니다",
                    )
                    if (s.chargeAutoLaunch) {
                        ItemDivider()
                        StatusItem(
                            "다른 앱 위에 표시", ok = overlayOk, okText = "허용됨", needText = "허용 필요",
                            subtitle = "Android 10+에서 백그라운드 자동 실행에 필요한 공식 권한. 없으면 알림으로만 안내",
                            onClick = { openOverlaySettings(context) },
                        )
                        ItemDivider()
                        SwitchItem("가로로 놓았을 때만 실행", s.autoLaunchNeedsLandscape, { v -> update { it.copy(autoLaunchNeedsLandscape = v) } }, "가속도 센서로 세워진 가로 방향 감지")
                    }
                    ItemDivider()
                    SwitchItem("충전 해제 시 종료", s.exitOnUnplug, { v -> update { it.copy(exitOnUnplug = v) } })
                    ItemDivider()
                    SwitchItem("화면 켜짐 유지", s.keepScreenOn, { v -> update { it.copy(keepScreenOn = v) } }, "StandBy가 열려 있는 동안")
                    ItemDivider()
                    SwitchItem("가로 모드 고정", s.forceLandscape, { v -> update { it.copy(forceLandscape = v) } })
                    ItemDivider()
                    SwitchItem("잠금 화면 위에 표시", s.showOnLockScreen, { v -> update { it.copy(showOnLockScreen = v) } }, "잠금을 풀지 않고도 StandBy 보기")
                }
            }

            // ═══════════ 정보 ═══════════
            item {
                SettingsGroup("정보") {
                    NavItem("버전", onClick = {}, value = BuildConfig.VERSION_NAME)
                    ItemDivider()
                    InfoText(
                        "시계 글꼴: Inter Display (SIL Open Font License 1.1)\n" +
                            "아이폰 StandBy의 화면 구성을 참고했으며 Apple의 그래픽·글꼴·로고는 포함하지 않습니다.\n" +
                            "날씨: Open-Meteo (선택 기능)",
                    )
                }
            }
        }
    }

    // ═══════════ 대화상자 ═══════════
    when (dialog) {
        SettingsDialog.STACKS -> Dialog(
            onDismissRequest = { dialog = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            EditPanel("위젯 배치 편집", { dialog = null }) { widgetEditContent(s, update) }
        }
        SettingsDialog.WORLD -> WorldClockDialog(
            zones = s.worldClockZones,
            onChange = { z -> update { it.copy(worldClockZones = z) } },
            onDismiss = { dialog = null },
        )
        SettingsDialog.CITY -> CityDialog(vm, s.weatherCity, s.hasWeatherLocation) { dialog = null }
        SettingsDialog.PLAYLIST -> PlaylistDialog(vm) { dialog = null }
        null -> Unit
    }
}

@Composable
private fun WorldClockDialog(zones: List<String>, onChange: (List<String>) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("완료") } },
        title = { Text("세계 시계 도시 (최대 4곳)") },
        containerColor = CardBg,
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item { Text("표시 중", color = SubText, fontSize = 13.sp) }
                itemsIndexed(zones) { i, id ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(cityName(id), fontSize = 16.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onChange(zones.toMutableList().apply { removeAt(i) }) }) {
                            Icon(Icons.Filled.Close, "삭제", tint = RedWarn)
                        }
                    }
                }
                if (zones.size < 4) {
                    item { Spacer(Modifier.height(8.dp)); Text("추가할 도시", color = SubText, fontSize = 13.sp) }
                    items(CityNames.keys.filter { it !in zones }) { id ->
                        Text(
                            cityName(id),
                            fontSize = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChange(zones + id) }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun CityDialog(vm: SettingsViewModel, current: String, hasLocation: Boolean, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf(current) }
    val results by vm.cityResults.collectAsStateWithLifecycle()
    val error by vm.citySearchError.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        dismissButton = {
            if (hasLocation) TextButton(onClick = { vm.clearCity(); onDismiss() }) { Text("지역 삭제", color = RedWarn) }
        },
        title = { Text("날씨 지역") },
        containerColor = CardBg,
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        label = { Text("도시 이름 (예: 서울, 부산)") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { vm.searchCity(query) }) { Text("검색") }
                }
                if (error != null) Text(error ?: "", color = RedWarn, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(results) { g ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.selectCity(g); onDismiss() }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(g.name, fontSize = 16.sp)
                            Text(listOf(g.admin, g.country).filter { it.isNotBlank() }.joinToString(", "), color = SubText, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun PlaylistDialog(vm: SettingsViewModel, onDismiss: () -> Unit) {
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(CardBg)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("재생목록 (${playlist.size}곡)", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("완료", color = Orange) }
            }
            if (playlist.isEmpty()) {
                Text("아직 음악이 없습니다. '음악 파일 추가' 또는 '음악 폴더 추가'를 사용하세요.", color = SubText, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
            } else {
                Box(Modifier.weight(1f, fill = false)) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        itemsIndexed(playlist, key = { i, t -> "$i-${t.uri}" }) { i, t ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                IconButton(onClick = { vm.playTrack(i) }) { Icon(Icons.Filled.PlayArrow, "재생", tint = Orange) }
                                Column(Modifier.weight(1f)) {
                                    Text(t.title, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOf(t.artist, if (t.durationMs > 0) formatDuration(t.durationMs) else "").filter { it.isNotBlank() }.joinToString(" · "),
                                        color = SubText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = { vm.moveTrack(i, i - 1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "위로", tint = Color.White) }
                                IconButton(onClick = { vm.moveTrack(i, i + 1) }, enabled = i < playlist.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, "아래로", tint = Color.White) }
                                IconButton(onClick = { vm.removeTrack(i) }) { Icon(Icons.Filled.Close, "삭제", tint = RedWarn) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                ButtonRow {
                    SmallAction("모두 지우기", RedWarn) { vm.clearPlaylist() }
                }
            }
        }
    }
}

// ═══════════ 시스템 설정 화면 열기 ═══════════

private fun Context.safeStart(vararg intents: Intent) {
    for (i in intents) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            // 다음 후보로
        } catch (e: SecurityException) {
            // 다음 후보로
        }
    }
}

private fun openDreamSettings(context: Context) = context.safeStart(
    Intent(Settings.ACTION_DREAM_SETTINGS),
    Intent(Settings.ACTION_DISPLAY_SETTINGS),
)

private fun openNotificationAccess(context: Context) {
    val component = MediaNotificationListener.component(context)
    val detail = if (Build.VERSION.SDK_INT >= 30) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
    } else null
    context.safeStart(
        *listOfNotNull(detail, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)).toTypedArray(),
    )
}

private fun openAppDetails(context: Context) = context.safeStart(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
)

private fun openOverlaySettings(context: Context) = context.safeStart(
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
)

private fun openRoutines(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage("com.samsung.android.app.routines")
    if (launch != null) context.safeStart(launch) else context.safeStart(Intent(Settings.ACTION_SETTINGS))
}
