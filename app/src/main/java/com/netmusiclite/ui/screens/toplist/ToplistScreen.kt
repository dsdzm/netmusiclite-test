package com.netmusiclite.ui.screens.toplist

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.PlaylistItem
import com.netmusiclite.data.ToplistItem
import com.netmusiclite.ui.components.CenterHint
import com.netmusiclite.ui.components.PlaylistCard
import com.netmusiclite.ui.components.PlaylistSectionHeader
import com.netmusiclite.ui.components.StackedCardList
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.NavMotionSpec
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.navigateWithMotion

/**
 * 官方排行榜（2026-10-19）：`/api/toplist` 返回 64 个榜单的元信息。
 *
 * 榜单本身不带歌（`tracks` 恒为 null），点进去用榜单 id 当歌单 id 走
 * [Routes.PLAYLIST_DETAIL]，复用现成的歌单详情页 —— 所以这里只需要一个列表。
 */
@Composable
fun ToplistScreen(nav: NavHostController) {
    var items by remember { mutableStateOf<List<ToplistItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val list = runCatching { NcmApi.toplist() }.getOrDefault(emptyList())
        items = list
        failed = list.isEmpty()
        loading = false
    }

    StackedCardList(
        modifier = Modifier.fillMaxSize(),
        title = "排行榜",
        // +1 给标题行，避免进度环在最后一屏提前走满
        progressTotal = items.size + 2,
        horizontalInsetPx = 10,
    ) {
        item(key = "toplist_header") {
            PlaylistSectionHeader("官方榜单", items.size)
        }
        when {
            loading -> item(key = "toplist_loading") {
                CenterHint("加载中…", Modifier.fillMaxWidth().height(120.dp))
            }

            failed -> item(key = "toplist_empty") {
                CenterHint("榜单加载失败，稍后重试", Modifier.fillMaxWidth().height(120.dp))
            }

            else -> itemsIndexed(
                items,
                key = { _, t -> "top_${t.id}" },
                contentType = { _, _ -> "playlist" },
            ) { _, t ->
                PlaylistCard(
                    pl = t.asPlaylistItem(),
                    subtitle = buildString {
                        append(t.updateFrequency ?: "榜单")
                        if (t.trackCount > 0) append(" · ${t.trackCount} 首")
                    },
                ) {
                    nav.navigateWithMotion(
                        Routes.playlistDetail(t.id),
                        NavMotionSpec(NavMotionKind.Pill),
                    )
                }
            }
        }
    }
}

/** 榜单与歌单同构：把元信息转成卡片组件认得的模型，避免为榜单再写一套卡片 */
private fun ToplistItem.asPlaylistItem() = PlaylistItem(
    id = id,
    name = name,
    coverUrl = coverUrl,
    trackCount = trackCount,
    playCount = playCount,
    isMine = false,
)
