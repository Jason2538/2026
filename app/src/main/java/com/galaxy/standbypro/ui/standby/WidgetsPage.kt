package com.galaxy.standbypro.ui.standby

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.galaxy.standbypro.data.WidgetType

/** 모드 A: 아이폰 StandBy처럼 좌우 2분할 위젯 스택. 각 스택은 위아래로 스와이프 */
@Composable
fun WidgetsPage(
    data: WidgetData,
    actions: StandByActions,
    onStackIndexChange: (left: Boolean, index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = data.settings
    val ratio = s.splitRatio.coerceIn(0.3f, 0.7f)
    BoxWithConstraints(modifier) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                WidgetStack(s.leftStack, s.leftIndex, data, actions, { onStackIndexChange(true, it) }, 100,
                    Modifier.weight(ratio).fillMaxHeight())
                WidgetStack(s.rightStack, s.rightIndex, data, actions, { onStackIndexChange(false, it) }, 200,
                    Modifier.weight(1f - ratio).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                WidgetStack(s.leftStack, s.leftIndex, data, actions, { onStackIndexChange(true, it) }, 100,
                    Modifier.weight(ratio).fillMaxWidth())
                WidgetStack(s.rightStack, s.rightIndex, data, actions, { onStackIndexChange(false, it) }, 200,
                    Modifier.weight(1f - ratio).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun WidgetStack(
    types: List<WidgetType>,
    index: Int,
    data: WidgetData,
    actions: StandByActions,
    onIndexChange: (Int) -> Unit,
    seed: Int,
    modifier: Modifier,
) {
    if (types.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            CenterNote("위젯 없음", "화면을 길게 눌러 위젯을 추가하세요")
        }
        return
    }
    val pager = rememberPagerState(initialPage = index.coerceIn(0, types.size - 1)) { types.size }
    LaunchedEffect(pager.settledPage) {
        if (pager.settledPage != index) onIndexChange(pager.settledPage)
    }
    Box(modifier) {
        VerticalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            key = { page -> types.getOrNull(page)?.name ?: page },
        ) { page ->
            val type = types.getOrNull(page) ?: return@VerticalPager
            WidgetContent(
                type = type,
                data = data,
                actions = actions,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
                    .burnIn(seed + page),
            )
        }
        PagerDots(
            count = types.size,
            current = pager.currentPage,
            visible = pager.isScrollInProgress,
            vertical = true,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp),
        )
    }
}
