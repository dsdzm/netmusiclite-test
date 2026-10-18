package com.netmusiclite.ui.screens.recommend

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.PageCache
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.PlaylistItem
import com.netmusiclite.data.Song
import com.netmusiclite.ui.components.CenterHint
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.PlaylistCard
import com.netmusiclite.ui.components.PlaylistCollectButton
import com.netmusiclite.ui.components.PlaylistSectionHeader
import com.netmusiclite.ui.components.StackedCardList
import com.netmusiclite.ui.components.StackedSongCard
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.NavMotionSpec
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 个性化推荐页（2026-10-18）：
 *  - 推荐歌单：/personalized/playlist，可直接收藏；
 *  - 推荐新歌：/personalized/newsong?type=recommend；
 *  - 两段都支持「换一批」，结果走 PageCache 会话缓存。
 *
 * 歌单推荐从「我的歌单」页移到这里，主页新增「推荐」入口。
 */
@Composable
fun RecommendScreen(nav: NavHostController) {
    var playlists by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var playlistsLoading by remember { mutableStateOf(true) }
    var songsLoading by remember { mutableStateOf(true) }
    var toast by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        playlists = PageCache.recommendPlaylists()
        playlistsLoading = false
        songs = PageCache.recommendNewSongs()
        songsLoading = false
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2200)
            toast = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        StackedCardList(
            modifier = Modifier.fillMaxSize(),
            title = "推荐",
            progressTotal = playlists.size + songs.size + 2,
            horizontalInsetPx = 10,
        ) {
            item(key = "playlist_header") {
                PlaylistSectionHeader("推荐歌单", playlists.size) {
                    Icon(
                        NcmIcons.Refresh,
                        contentDescription = "换一批歌单",
                        tint = if (playlistsLoading) TextSecondary.copy(alpha = 0.4f) else TextSecondary,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable(enabled = !playlistsLoading) {
                                scope.launch {
                                    playlistsLoading = true
                                    val fresh = PageCache.recommendPlaylists(force = true)
                                    if (fresh.isNotEmpty()) playlists = fresh else toast = "推荐歌单加载失败"
                                    playlistsLoading = false
                                }
                            }
                            .padding(6.dp),
                    )
                }
            }
            if (playlistsLoading) {
                item { CenterHint("加载中…", Modifier.fillMaxWidth().height(120.dp)) }
            } else if (playlists.isEmpty()) {
                item { CenterHint("暂无推荐歌单", Modifier.fillMaxWidth().height(100.dp)) }
            } else {
                itemsIndexed(playlists, key = { _, p -> "rp_${p.id}" }, contentType = { _, _ -> "playlist" }) { _, pl ->
                    PlaylistCard(
                        pl,
                        subtitle = "推荐 · ${pl.trackCount} 首",
                        action = {
                            PlaylistCollectButton(pl.subscribed) {
                                scope.launch {
                                    val ok = NcmApi.subscribePlaylist(pl.id)
                                    if (ok) {
                                        playlists = playlists.map {
                                            if (it.id == pl.id) it.copy(subscribed = true) else it
                                        }
                                        PageCache.invalidatePlaylists()
                                        toast = "已收藏「${pl.name}」"
                                    } else {
                                        toast = "收藏失败"
                                    }
                                }
                            }
                        },
                    ) {
                        nav.navigateWithMotion(Routes.playlistDetail(pl.id), NavMotionSpec(NavMotionKind.Pill))
                    }
                }
            }

            item(key = "song_header") {
                PlaylistSectionHeader("推荐新歌", songs.size) {
                    Icon(
                        NcmIcons.Refresh,
                        contentDescription = "换一批歌曲",
                        tint = if (songsLoading) TextSecondary.copy(alpha = 0.4f) else TextSecondary,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable(enabled = !songsLoading) {
                                scope.launch {
                                    songsLoading = true
                                    val fresh = PageCache.recommendNewSongs(force = true)
                                    if (fresh.isNotEmpty()) songs = fresh else toast = "推荐歌曲加载失败"
                                    songsLoading = false
                                }
                            }
                            .padding(6.dp),
                    )
                }
            }
            if (songsLoading) {
                item { CenterHint("加载中…", Modifier.fillMaxWidth().height(120.dp)) }
            } else if (songs.isEmpty()) {
                item { CenterHint("暂无推荐歌曲", Modifier.fillMaxWidth().height(100.dp)) }
            } else {
                itemsIndexed(songs, key = { _, s -> "rs_${s.id}" }, contentType = { _, _ -> "song" }) { index, song ->
                    StackedSongCard(
                        song = song,
                        isCurrent = PlayerEngine.current?.id == song.id,
                        onClick = {
                            PlayerEngine.playQueue(songs, index)
                            nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill))
                        },
                    )
                }
            }
        }

        toast?.let { msg ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC2B2B33))
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            ) {
                Text(msg, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium)
            }
        }
    }
}
