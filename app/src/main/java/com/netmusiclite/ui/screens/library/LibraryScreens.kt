package com.netmusiclite.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.netmusiclite.data.AlbumItem
import com.netmusiclite.data.DownloadStore
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.PageCache
import com.netmusiclite.data.PlaylistItem
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.SessionStore
import com.netmusiclite.data.Song
import com.netmusiclite.data.SongQuery
import com.netmusiclite.ui.components.songCoverModel
import com.netmusiclite.ui.components.CenterHint
import com.netmusiclite.ui.components.CoverImage
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.SelectionDownloadCircles
import com.netmusiclite.ui.components.StackedCard
import com.netmusiclite.ui.components.StackedCardList
import com.netmusiclite.ui.components.StackedSongCard
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.NavMotionSpec
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.AccentSoft
import com.netmusiclite.ui.theme.Bg
import com.netmusiclite.ui.theme.SurfaceGlass
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.components.ListFilterCard
import com.netmusiclite.ui.components.rememberAliasAwareSongs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 每日推荐：真实接口，点歌即播（歌曲行卡片堆叠）；长按进入多选下载 */
@Composable
fun DailyRecommendScreen(nav: NavHostController) {
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateMapOf<Long, Song>() }
    val selectMode = selected.isNotEmpty()

    LaunchedEffect(Unit) {
        songs = PageCache.dailyRecommend()
        if (songs.isEmpty()) failed = true
    }
    LaunchedEffect(toast) { if (toast != null) { delay(3500); toast = null } }

    fun downloadSelected() {
        val list = selected.values.filter { it.id > 0 && DownloadStore.states[it.id] != 2 }
        if (list.isEmpty()) { toast = "所选歌曲均已下载"; selected.clear(); return }
        toast = "开始下载 ${list.size} 首…"
        DownloadStore.enqueue(list) { okN, failN, reasons ->
            toast = when {
                failN == 0 -> "已下载 $okN 首，到本地音乐查看"
                okN == 0 -> "下载失败：$reasons"
                else -> "成功 $okN 首；失败 $failN 首（$reasons）"
            }
            selected.clear()
        }
    }

    Box(Modifier.fillMaxSize()) {
        val n = songs.size
        var filter by remember { mutableStateOf("") }
        // 跨语言搜索：每日推荐走接口，歌自带译名；缺的按需"查询扩展 + 别名反查"
        val visible = rememberAliasAwareSongs(songs, filter)
        StackedCardList(title = "每日推荐", progressTotal = n + 2, cardHeight = 56.dp, horizontalInsetPx = 10) {
            item(key = "filter") { ListFilterCard(query = filter, onQuery = { filter = it }) }
            if (failed && songs.isEmpty()) {
                item { CenterHint("今日推荐获取失败\n请检查登录态与网络", Modifier.fillMaxWidth().height(240.dp)) }
            } else if (songs.isEmpty()) {
                item { CenterHint("加载中…", Modifier.fillMaxWidth().height(240.dp)) }
            } else {
                itemsIndexed(visible, key = { _, s -> s.id }, contentType = { _, _ -> "song" }) { i, song ->
                    StackedSongCard(
                        song = song,
                        isCurrent = PlayerEngine.current?.id == song.id,
                        aliasHint = SongQuery.matchedAlias(song, filter),
                        selectMode = selectMode,
                        selected = selected.containsKey(song.id),
                        onClick = {
                            if (selectMode) {
                                if (selected.containsKey(song.id)) selected.remove(song.id) else selected[song.id] = song
                            } else {
                                PlayerEngine.playQueue(visible, i)
                                nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill)) // 点歌即播并进入播放页
                            }
                        },
                        onLongClick = {
                            if (selected.containsKey(song.id)) selected.remove(song.id) else selected[song.id] = song
                        },
                    )
                }
            }
        }
        if (selectMode) {
            SelectionDownloadCircles(
                count = selected.size,
                onDownload = { downloadSelected() },
                onCancel = { selected.clear() },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        if (toast != null) {
            Box(Modifier.align(Alignment.Center)) {
                Text(toast!!, fontSize = 11.sp, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC2B2B33))
                        .padding(horizontal = 16.dp, vertical = 7.dp))
            }
        }
    }
}

/** 我喜欢：顶部 = 最新收藏封面渐变头图（列表首项，进入页面从列表最顶部开始），歌曲按收藏时间排列（最新在前）；长按多选下载 */
@Composable
fun LikedSongsScreen(nav: NavHostController) {
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var toast by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateMapOf<Long, Song>() }
    val selectMode = selected.isNotEmpty()
    // 列表进度恢复（免闪烁，2026-09-25）：进播放页前存的视口进度直接作为列表初始位置，
    // 歌曲未加载完前不渲染列表，首次布局即落在原进度，无顶部→跳转的闪现
    val savedHandle = nav.currentBackStackEntry?.savedStateHandle
    val savedIdx = remember { savedHandle?.get<Int>("list_restore_index") }
    val restoring = savedIdx != null
    // ★ 必须 remember：LazyListState(...) 是类构造器（优先于同名 Composable 工厂），
    //   裸调用会每次重组新建实例——选中歌曲/toast/切歌等任何重组都会让列表弹回顶部
    val listState = remember {
        androidx.compose.foundation.lazy.LazyListState(
            savedIdx ?: 0,
            savedHandle?.get<Int>("list_restore_offset") ?: 0,
        )
    }

    LaunchedEffect(Unit) {
        songs = PageCache.likedSongs(SessionStore.uid)
        loading = false
    }
    LaunchedEffect(toast) { if (toast != null) { delay(3500); toast = null } }

    fun downloadSelected() {
        val list = selected.values.filter { it.id > 0 && DownloadStore.states[it.id] != 2 }
        if (list.isEmpty()) { toast = "所选歌曲均已下载"; selected.clear(); return }
        toast = "开始下载 ${list.size} 首…"
        DownloadStore.enqueue(list) { okN, failN, reasons ->
            toast = when {
                failN == 0 -> "已下载 $okN 首，到本地音乐查看"
                okN == 0 -> "下载失败：$reasons"
                else -> "成功 $okN 首；失败 $failN 首（$reasons）"
            }
            selected.clear()
        }
    }

    Box(Modifier.fillMaxSize()) {
        val n = songs.size
        var filter by remember { mutableStateOf("") }
        // 跨语言搜索：我喜欢的音乐歌自带译名（/v3/song/detail 的 tns/alia），缺的按需补齐
        val visible = rememberAliasAwareSongs(songs, filter)
        // 恢复进度等待期不渲染列表：避免先按顶部布局再跳转的闪现
        if (!restoring || songs.isNotEmpty()) {
        StackedCardList(state = listState, title = "我喜欢的音乐", progressTotal = n + 2, cardHeight = 56.dp, horizontalInsetPx = 10) {
            if (loading) {
                item { CenterHint("加载中…", Modifier.fillMaxWidth().height(240.dp)) }
            } else if (songs.isEmpty()) {
                item { CenterHint("还没有红心歌曲\n播放时点亮 ♥ 即可收藏", Modifier.fillMaxWidth().height(240.dp)) }
            } else {
                item(key = "liked_cover_header") { LikedCoverHeader(songs.first(), songs.size) }
                item(key = "filter") { ListFilterCard(query = filter, onQuery = { filter = it }) }
                itemsIndexed(visible, key = { _, s -> s.id }, contentType = { _, _ -> "song" }) { i, song ->
                    StackedSongCard(
                        song = song,
                        isCurrent = PlayerEngine.current?.id == song.id,
                        aliasHint = SongQuery.matchedAlias(song, filter),
                        selectMode = selectMode,
                        selected = selected.containsKey(song.id),
                        onClick = {
                            if (selectMode) {
                                if (selected.containsKey(song.id)) selected.remove(song.id) else selected[song.id] = song
                            } else {
                                PlayerEngine.playQueue(visible, i)
                                // 进播放页前保存列表视口进度，返回时复用（savedStateHandle）
                                nav.currentBackStackEntry?.savedStateHandle?.apply {
                                    set("list_restore_index", listState.firstVisibleItemIndex)
                                    set("list_restore_offset", listState.firstVisibleItemScrollOffset)
                                }
                                nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill)) // 点歌即播并进入播放页
                            }
                        },
                        onLongClick = {
                            if (selected.containsKey(song.id)) selected.remove(song.id) else selected[song.id] = song
                        },
                    )
                }
            }
        }
        }
        if (selectMode) {
            SelectionDownloadCircles(
                count = selected.size,
                onDownload = { downloadSelected() },
                onCancel = { selected.clear() },
                selectAll = { songs.forEach { selected[it.id] = it } }, // 全选
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        if (toast != null) {
            Box(Modifier.align(Alignment.Center)) {
                Text(toast!!, fontSize = 11.sp, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC2B2B33))
                        .padding(horizontal = 16.dp, vertical = 7.dp))
            }
        }
    }
}

/** 封面头图：最新收藏歌曲封面铺满整卡（小尺寸解码，防大图卡顿），向下渐变到背景色，融入下方歌曲列表 */
@Composable
private fun LikedCoverHeader(song: Song, count: Int) {
    val ctx = LocalContext.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(118.dp)
            .clip(RoundedCornerShape(30.dp)),
    ) {
        val fadeOut = com.netmusiclite.data.BackgroundStore.ready
        // 图像层：启用背景图时用 DstIn 沿高度擦除 alpha = 真渐隐
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (fadeOut) {
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Black,
                                0.45f to Color.Black.copy(alpha = 0.7f),
                                
                                1f to Color.Transparent,
                            ),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                    }
                }
        ) {
            // 头图与封面同源：已下载歌曲用落盘封面文件（断网可显示），否则网络 URL
            val model = songCoverModel(song)
            if (model != null) {
                AsyncImage(
                    model = if (model is String) ImageRequest.Builder(ctx).data(model).size(466).build() else model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        if (!fadeOut) {
            // 纯色模式：顶部轻压暗 → 底部融入纯色背景
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0x400A0A0C),
                            0.45f to Color(0x0F0A0A0C),
                            1f to Bg,
                        )
                    )
            )
        }
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(NcmIcons.HeartFill, contentDescription = "最新收藏", tint = Accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("$count 首", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

/**
 * 我的歌单库（2026-10-18：新增收藏歌单 / 推荐歌单）。
 *
 * 顶部两枚胶囊「我的歌单 / 收藏的专辑」：
 *  - 我的歌单页分三段：我创建的歌单、我收藏的歌单（/user/playlist 的 subscribed=true，
 *    含官方动态/年度歌单）、推荐歌单（/personalized/playlist，匿名可拉）。
 *  - 推荐卡片右侧「+」直接收藏，成功后刷新「我收藏的歌单」；右上角刷新键换一批。
 *  - 收藏的专辑仍走原 album/sublist 分栏，逻辑不变。
 */
@Composable
fun MyPlaylistsScreen(nav: NavHostController) {
    var tab by remember { mutableStateOf(0) }          // 0 = 我的歌单；1 = 收藏的专辑
    var created by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var collected by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var recommended by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var albums by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var albumsLoaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var recommendLoading by remember { mutableStateOf(true) }
    var toast by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun loadPlaylists() {
        val all = PageCache.userPlaylists(SessionStore.uid)
        val mine = all.filter { it.isMine && !it.subscribed }
        // 兜底：接口 creator 缺失时，至少不要把收藏项混进创建列表
        created = mine.ifEmpty { all.filter { !it.subscribed } }
        collected = all.filter { it.subscribed }
        // 推荐项的「已收藏」状态以收藏列表为准，避免刷新后按钮又变回 +
        val collectedIds = collected.map { it.id }.toSet()
        recommended = recommended.map { it.copy(subscribed = it.id in collectedIds) }
    }

    LaunchedEffect(Unit) {
        loadPlaylists()
        loading = false
        recommendLoading = true
        val collectedIds = collected.map { it.id }.toSet()
        recommended = PageCache.recommendPlaylists()
            .map { it.copy(subscribed = it.id in collectedIds) }
        recommendLoading = false
    }
    // 收藏专辑按需加载：切到该栏且没拉过才发请求（首屏/歌单一栏零额外开销）
    LaunchedEffect(tab) {
        if (tab == 1 && !albumsLoaded) {
            albums = PageCache.albumSublist(SessionStore.uid)
            albumsLoaded = true
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2200)
            toast = null
        }
    }

    val total = if (tab == 0) created.size + collected.size + recommended.size + 3 else albums.size
    Box(Modifier.fillMaxSize()) {
        StackedCardList(
            modifier = Modifier.fillMaxSize(),
            title = if (tab == 0) "我的歌单" else "收藏的专辑",
            progressTotal = total + 2,
            // 2026-10-01：补上 horizontalInsetPx = 10 —— 此前缺省为 0，卡片比主页宽 20px，
            // 两页并排看宽度对不齐（主页/我喜欢/每日推荐等均为 10）。
            horizontalInsetPx = 10,
        ) {
            item(key = "switch") {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    listOf(0 to "我的歌单", 1 to "收藏的专辑").forEach { (t, label) ->
                        val selected = tab == t
                        Text(
                            label,
                            fontSize = 11.sp,
                            color = if (selected) Accent else TextPrimary,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .padding(horizontal = 6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (selected) AccentSoft else SurfaceGlass)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { tab = t }
                                .padding(horizontal = 13.dp, vertical = 5.dp),
                        )
                    }
                }
            }

            if (tab == 0) {
                item(key = "created_header") {
                    PlaylistSectionHeader("我创建的歌单", created.size)
                }
                if (loading) {
                    item { CenterHint("加载中…", Modifier.fillMaxWidth().height(150.dp)) }
                } else if (created.isEmpty()) {
                    item {
                        CenterHint(
                            "还没有创建歌单\n播放页 · 更多 · 收藏 可新建",
                            Modifier.fillMaxWidth().height(120.dp),
                        )
                    }
                } else {
                    itemsIndexed(created, key = { _, p -> "c_${p.id}" }, contentType = { _, _ -> "playlist" }) { _, pl ->
                        PlaylistCard(pl, subtitle = "${pl.trackCount} 首") {
                            nav.navigateWithMotion(Routes.playlistDetail(pl.id), NavMotionSpec(NavMotionKind.Pill))
                        }
                    }
                }

                item(key = "collected_header") {
                    PlaylistSectionHeader("我收藏的歌单", collected.size)
                }
                if (loading) {
                    item { CenterHint("加载中…", Modifier.fillMaxWidth().height(120.dp)) }
                } else if (collected.isEmpty()) {
                    item {
                        CenterHint(
                            "还没有收藏歌单\n在推荐或歌单详情里点收藏",
                            Modifier.fillMaxWidth().height(120.dp),
                        )
                    }
                } else {
                    itemsIndexed(collected, key = { _, p -> "s_${p.id}" }, contentType = { _, _ -> "playlist" }) { _, pl ->
                        PlaylistCard(
                            pl,
                            subtitle = listOfNotNull(
                                pl.creatorName,
                                "${pl.trackCount} 首",
                            ).joinToString(" · "),
                        ) {
                            nav.navigateWithMotion(Routes.playlistDetail(pl.id), NavMotionSpec(NavMotionKind.Pill))
                        }
                    }
                }

                item(key = "recommend_header") {
                    PlaylistSectionHeader("推荐歌单", recommended.size) {
                        Icon(
                            NcmIcons.Refresh,
                            contentDescription = "换一批",
                            tint = if (recommendLoading) TextSecondary.copy(alpha = 0.4f) else TextSecondary,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(50))
                                .clickable(enabled = !recommendLoading) {
                                    scope.launch {
                                        recommendLoading = true
                                        val ids = collected.map { it.id }.toSet()
                                        val fresh = PageCache.recommendPlaylists(force = true)
                                            .map { it.copy(subscribed = it.id in ids) }
                                        if (fresh.isNotEmpty()) {
                                            recommended = fresh
                                        } else {
                                            toast = "推荐加载失败"
                                        }
                                        recommendLoading = false
                                    }
                                }
                                .padding(6.dp),
                        )
                    }
                }
                if (recommendLoading) {
                    item { CenterHint("正在换一批…", Modifier.fillMaxWidth().height(120.dp)) }
                } else if (recommended.isEmpty()) {
                    item { CenterHint("暂无推荐", Modifier.fillMaxWidth().height(100.dp)) }
                } else {
                    itemsIndexed(recommended, key = { _, p -> "r_${p.id}" }, contentType = { _, _ -> "playlist" }) { _, pl ->
                        PlaylistCard(
                            pl,
                            subtitle = "推荐 · ${pl.trackCount} 首",
                            action = {
                                PlaylistCollectButton(pl.subscribed) {
                                    scope.launch {
                                        val ok = NcmApi.subscribePlaylist(pl.id)
                                        if (ok) {
                                            // 先本地落位，避免等服务端 /user/playlist 回包才显示
                                            collected = listOf(pl.copy(subscribed = true)) +
                                                collected.filterNot { it.id == pl.id }
                                            recommended = recommended.map {
                                                if (it.id == pl.id) it.copy(subscribed = true) else it
                                            }
                                            PageCache.invalidatePlaylists()
                                            // 不立即重新拉 /user/playlist：服务端回包可能滞后，
                                            // 会把刚乐观插入的条目冲掉；下次进页面自然刷新。
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
            } else {
                if (!albumsLoaded) {
                    item { CenterHint("加载中…", Modifier.fillMaxWidth().height(240.dp)) }
                } else if (albums.isEmpty()) {
                    item { CenterHint("还没有收藏专辑\n专辑页点收藏即可加入这里", Modifier.fillMaxWidth().height(240.dp)) }
                } else {
                    itemsIndexed(albums, key = { _, a -> "al_${a.id}" }, contentType = { _, _ -> "album" }) { _, al ->
                        StackedCard(
                            onClick = {
                                nav.navigateWithMotion(Routes.albumDetail(al.id), NavMotionSpec(NavMotionKind.Pill))
                            },
                            navMotionKind = NavMotionKind.Pill,
                        ) {
                            CoverImage(al.coverUrl, 44.dp, shape = RoundedCornerShape(11.dp))
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(al.name, fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(al.artist, if (al.songCount > 0) "${al.songCount} 首" else "")
                                        .filter { it.isNotBlank() }.joinToString(" · "),
                                    fontSize = 9.sp, color = TextSecondary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                NcmIcons.ChevronRight, null,
                                tint = Color.White.copy(alpha = 0.35f),
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
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
                Text(msg, fontSize = 11.sp, color = Color.White)
            }
        }
    }
}

/** 歌单分区标题：标题 + 数量 + 可选右侧动作 */
@Composable
private fun PlaylistSectionHeader(
    title: String,
    count: Int,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
        if (count > 0) {
            Spacer(Modifier.width(5.dp))
            Text("$count", fontSize = 9.sp, color = TextSecondary.copy(alpha = 0.65f))
        }
        Spacer(Modifier.weight(1f))
        action?.invoke()
    }
}

/** 歌单卡：创建/收藏/推荐共用；action 放右侧（推荐项的收藏键），最后统一是箭头 */
@Composable
private fun PlaylistCard(
    pl: PlaylistItem,
    subtitle: String,
    action: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    StackedCard(
        onClick = onClick,
        navMotionKind = NavMotionKind.Pill,
    ) {
        CoverImage(pl.coverUrl, 44.dp, shape = RoundedCornerShape(11.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                pl.name, fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle, fontSize = 9.sp, color = TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        action?.invoke()
        Icon(
            NcmIcons.ChevronRight, null,
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(15.dp),
        )
    }
}

/** 推荐卡右侧收藏键：未收藏显示 +，已收藏显示对勾并禁用 */
@Composable
private fun PlaylistCollectButton(subscribed: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(end = 5.dp)
            .size(30.dp)
            .clip(RoundedCornerShape(50))
            .background(if (subscribed) AccentSoft else SurfaceGlass)
            .clickable(enabled = !subscribed, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (subscribed) NcmIcons.Check else NcmIcons.Plus,
            contentDescription = if (subscribed) "已收藏" else "收藏歌单",
            tint = if (subscribed) Accent else TextPrimary,
            modifier = Modifier.size(14.dp),
        )
    }
}
