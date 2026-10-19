# ncm-api-kit 四项能力接入：排行榜 / 搜索翻页 / 搜歌单 / 可播状态提前判定

> 依据：《ncm-api-kit 接入评估》（2026-10-19，docs/updates/2026-10-19-ncm-api-kit接入评估.md）
> 基线：本仓库 `main`，`d483259`（评估文档提交）之后
> 接入日期：2026-10-19

---

## 1. 背景

上一份评估文档对 `ncm-api-kit` 做了逐项对比，结论是「真正值得接的只有 4 项」。
本文档记录这 4 项的**完整实现**，供原作者直接 review / 合并到上游：

| 优先级 | 能力 | 接口 | 本文对应章节 |
|---|---|---|---|
| P0 | 排行榜 | `/api/toplist` + 现有 `playlistDetail` | 第 3 节 |
| P1 | 搜索翻页 | `/cloudsearch/get/web` 的 `offset` + `hasMore` | 第 4 节 |
| P1 | 搜歌单 | `type=1000` | 第 5 节 |
| P2 | 可播状态提前判定 | `privilege.st` / `privilege.pl` | 第 6 节 |

四项全部为**增量**：不删改任何现有接口签名（`search()` 只加带默认值的参数），
UI 全部复用现有组件（`PlaylistCard` / `SongRow` / 歌单详情页），不新增依赖。

---

## 2. 实现原理

### 2.1 为什么榜单能直接复用歌单详情页

`/api/toplist` 返回的每个榜单只带元信息（`id/name/coverImgUrl/trackCount/updateFrequency`），
`tracks` 恒为 `null`。但网易云的榜单在服务端就是以歌单形态存储的 ——
把榜单 id 当歌单 id 调 `/v6/playlist/detail` 就能拿到完整歌曲列表。

所以接入策略是：

```
ToplistScreen（列表，复用 PlaylistCard）
   └─ 点击 → Routes.playlistDetail(toplistId)
              └─ PlaylistDetailScreen（零改动，直接读榜单当歌单）
```

**零新页面、零新组件**，这是四项里性价比最高的一个。

### 2.2 搜索翻页的三个坑

1. **`limit` 上限 100**，传 1000 服务端返回空列表（不是报错，是空列表）；
2. **翻页只取单曲** —— 首屏已经把艺人/专辑拉过了，`offset>0` 时再各发一次
   type=100/type=10 的请求纯属浪费，还拖慢翻页；
3. **`hasMore=false` 必须停** —— 不停会一直收到空列表空转。

UI 上**用显式「加载更多」按钮而非自动加载**：圆屏手表上误触滚动很容易一路滑到底，
自动翻页会连着发好几个请求（本仓库一贯的手表交互取舍，见通知栏 RemoteViews 一节）。

### 2.3 可播状态：为什么用 `st` + `pl` 而不是 `fee`

```kotlin
available = privilege.optInt("st") >= 0 && privilege.optInt("pl") > 0
```

- `st < 0` = 已下架；
- `pl` 是**可播码率**（320000 = 320kbps），不是布尔，`pl == 0` 才是真没得放；
- `fee` 只描述收费类型（0 免费 / 1 VIP / 8 低音质免费），**VIP 歌仍然可播**，
  用 `fee` 判会把一堆能放的歌误置灰。

**关键边界：搜索接口不带 `privilege`**。所以 `Song.available` 是 `Boolean?`：

- `null` = 未知（搜索结果、本地扫描）→ 当正常歌曲展示；
- `false` = 明确不可播 → 置灰 + 副标题标注「不可播」；
- `true` = 可播。

绝不能用「非 true 即不可播」判断，否则搜索列表会大面积误置灰。

---

## 3. P0：官方排行榜

### 3.1 数据模型（`data/Models.kt` 新增）

```kotlin
/**
 * 官方榜单（`/api/toplist`）。
 *
 * 榜单本身不含歌曲（`tracks` 恒为 null），取歌要用 [id] 当歌单 id 调 playlistDetail。
 * 因此这里不复用 [PlaylistItem]：字段语义不同（榜单没有「收藏」「创建者」，
 * 但有更新频率与更新时间，列表里要显示「每周四更新」这类文案）。
 */
@Immutable
data class ToplistItem(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int,
    val playCount: Long,
    /** 服务端排好的更新频率文案，如「每周四更新」「刚刚更新」；可能为空 */
    val updateFrequency: String? = null,
    /** 更新时间（毫秒），0 表示服务端未给 */
    val updateTime: Long = 0L,
)
```

### 3.2 接口（`data/NcmApi.kt` 新增）

```kotlin
/**
 * 榜单列表。
 *
 * `/api/toplist` 是少数只认明文 GET 的接口（走 weapi 会 404），所以用 [getJson]。
 * 返回的 `tracks` 恒为 null —— 榜单不含歌曲，取歌要用 id 调 [playlistDetail]。
 */
suspend fun toplist(): List<ToplistItem> = withContext(Dispatchers.IO) {
    val j = getJson("https://music.163.com/api/toplist")
    if (j.optInt("code", 0) != 200) return@withContext emptyList()
    val arr = j.optJSONArray("list") ?: return@withContext emptyList()
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optLong("id")
        if (id <= 0L) return@mapNotNull null
        ToplistItem(
            id = id,
            name = o.optString("name"),
            coverUrl = o.optString("coverImgUrl").takeIf { it.isNotEmpty() },
            trackCount = o.optInt("trackCount"),
            playCount = o.optLong("playCount"),
            updateFrequency = o.optString("updateFrequency").takeIf { it.isNotBlank() },
            updateTime = o.optLong("updateTime"),
        )
    }
}
```

### 3.3 新页面（`ui/screens/toplist/ToplistScreen.kt`，新增文件）

```kotlin
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
```

### 3.4 路由与入口

`ui/nav/Routes.kt`：

```kotlin
/** 官方排行榜（/api/toplist，64 个榜单；点进去复用歌单详情页） */
const val TOPLIST = "toplist"
```

`ui/nav/AppNav.kt`（import 区加一行、composable 区加一行）：

```kotlin
import com.netmusiclite.ui.screens.toplist.ToplistScreen
// ...
composable(Routes.TOPLIST, content = backable { ToplistScreen(nav) })
```

`ui/screens/home/HomeScreen.kt`（`homeItems` 加一项，放在「推荐」之后）：

```kotlin
HomeItem("排行榜", NcmIcons.Toplist, Routes.TOPLIST),
```

配套图标 `ui/components/NcmIcons.kt`（奖杯造型，与「听歌排行」的柱状图图标区分）：

```kotlin
/** 官方排行榜（奖杯）：杯身 + 双耳 + 底座，与「听歌排行」的柱状图区分 */
val Toplist: ImageVector by lazy { icon("Toplist") {
    s(1.9f) {
        moveTo(8.2f, 4.2f); lineTo(15.8f, 4.2f); lineTo(15f, 10.6f)
        curveTo(14.8f, 13.2f, 13.6f, 14.8f, 12f, 14.8f)
        curveTo(10.4f, 14.8f, 9.2f, 13.2f, 9f, 10.6f); close()
    }
    s(1.9f) {
        moveTo(8.2f, 5.6f); curveTo(5.6f, 5.6f, 5.4f, 10.2f, 8.8f, 10.4f)
    }
    s(1.9f) {
        moveTo(15.8f, 5.6f); curveTo(18.4f, 5.6f, 18.6f, 10.2f, 15.2f, 10.4f)
    }
    s(1.9f) { moveTo(12f, 14.8f); lineTo(12f, 18f) }
    s(1.9f) { moveTo(8.4f, 19.8f); lineTo(15.6f, 19.8f) }
} }
```

---

## 4. P1：搜索翻页

### 4.1 接口层（`data/NcmApi.kt`）

`SearchResult` 增加两个返回值，`search()` 增加 `offset` 参数：

```kotlin
data class SearchResult(
    val songs: List<Song>,
    val artists: List<ArtistItem>,
    val albums: List<AlbumItem>,
    /**
     * 是否还有下一页。
     *
     * **不能只信服务端的 `result.hasMore`**：cloudsearch 并不稳定下发该字段
     * （2026-10-19 真机复现：按钮不出现，响应里没有 hasMore）。
     * 第三方实现（folia-major、Mineradio）都是自行按 `songCount` 计算的：
     * `offset + 本页条数 < total`。这里的优先级：
     * 1. 服务端显式给了 `hasMore` 就用它；
     * 2. 否则有 `songCount` 就自己算；
     * 3. 两者都没有（total=0）才按「满页」保守猜 —— 最多多发一次空请求，
     *    不会漏掉后面的结果。
     */
    val hasMore: Boolean = false,
    /** 命中总数（服务端 `result.songCount`）；0 表示服务端未返回 */
    val total: Int = 0,
)

/**
 * 综合搜索（单曲 + 艺人 + 专辑）。
 *
 * [offset] 用于翻页：服务端 `limit` 上限 100（传 1000 会返回空列表），
 * 翻到 `hasMore=false` 就必须停，否则会一直发空请求。
 */
suspend fun search(kw: String, limit: Int = 20, offset: Int = 0): SearchResult =
    withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("s", kw).put("limit", limit).put("offset", offset)
            .put("type", 1).put("csrf_token", "").put("hlpretag", "").put("hlposttag", "")
        val songsJ = we("/cloudsearch/get/web", body)
        val songsRes = songsJ.optJSONObject("result")
        val songList = songs(songsRes?.optJSONArray("songs"))
        // 翻页（offset>0）只取单曲：艺人与专辑在首屏已经有了，重复拉两趟纯浪费
        val artists = if (offset == 0) {
            val artistsJ = we("/cloudsearch/get/web", JSONObject(body.toString()).put("type", 100))
            artistsJ.optJSONObject("result")?.optJSONArray("artists")?.let { arr ->
                (0 until arr.length()).mapNotNull { o ->
                    (arr.optJSONObject(o) ?: return@mapNotNull null).let {
                        ArtistItem(it.optLong("id"), it.optString("name"),
                            it.optString("img1v1Url").ifEmpty { it.optString("picUrl") })
                    }
                }
            } ?: emptyList()
        } else emptyList()
        val albums = if (offset == 0) {
            val albumsJ = we("/cloudsearch/get/web", JSONObject(body.toString()).put("type", 10))
            albumsJ.optJSONObject("result")?.optJSONArray("albums")?.let { arr ->
                (0 until arr.length()).mapNotNull { o ->
                    (arr.optJSONObject(o) ?: return@mapNotNull null).let {
                        AlbumItem(it.optLong("id"), it.optString("name"), it.optString("picUrl"),
                            it.optJSONObject("artist")?.optString("name") ?: "",
                            it.optLong("publishTime"), it.optInt("size"))
                    }
                }
            } ?: emptyList()
        } else emptyList()
        val total = songsRes?.optInt("songCount", 0) ?: 0
        SearchResult(
            songList, artists, albums,
            // hasMore 三级兜底，详见 SearchResult 注释：服务端字段不可靠，按 songCount 自算
            hasMore = songsRes?.optBoolean("hasMore", false) == true ||
                if (total > 0) offset + songList.size < total
                else songList.size >= limit,
            total = total,
        )
    }
```

> 与上游兼容性：`limit`/`offset` 都有默认值，老的 `search(kw)` 调用点**一行不用改**。

### 4.2 UI 层（`ui/screens/search/SearchScreen.kt`）

新增状态与翻页函数（`val songs` 从 `result?.songs` 派生改为可累积的 state）：

```kotlin
// 分页累积（2026-10-19）：服务端每页 20 条，翻页只追加歌曲，艺人/专辑沿用首屏
var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
var hasMore by remember { mutableStateOf(false) }
var loadingMore by remember { mutableStateOf(false) }
// 下一页的请求 offset：**不能拿 songs.size 当 offset** —— 相邻页重叠的曲目会被
// 去重掉，size 会落后于服务端真实 offset，继续翻会漏歌。必须独立计数。
var nextOffset by remember { mutableStateOf(0) }
// 相关歌单（type=1000）：放在歌曲列表之后，晚到不影响首屏阅读
var playlists by remember { mutableStateOf<List<com.netmusiclite.data.PlaylistItem>>(emptyList()) }
```

搜索入口处重置 `nextOffset`（换关键词时从 0 开始）：

```kotlin
if (kw.isBlank()) {
    result = null
    songs = emptyList()
    playlists = emptyList()
    hasMore = false
    nextOffset = 0
    return@LaunchedEffect
}
delay(350)
loading = true
val r = NcmApi.search(kw.trim())
result = r
songs = r.songs
hasMore = r.hasMore
nextOffset = r.songs.size
loading = false
```

翻页函数：

```kotlin
/** 翻页：只追加，按 id 去重（服务端相邻页偶有重叠）；offset 独立计数防漏歌 */
fun loadMore() {
    if (!hasMore || loadingMore || loading) return
    loadingMore = true
    scope.launch {
        val r = runCatching { NcmApi.search(kw.trim(), limit = 20, offset = nextOffset) }
            .getOrDefault(NcmApi.SearchResult(emptyList(), emptyList(), emptyList()))
        val known = songs.mapTo(HashSet()) { it.id }
        songs = songs + r.songs.filter { known.add(it.id) }
        // offset 按服务端口径推进：本页实际拿到多少条就走多少，
        // 与去重后的列表长度脱钩，避免重叠页导致 offset 滞后漏歌
        nextOffset += if (r.songs.isNotEmpty()) r.songs.size else 20
        hasMore = r.hasMore
        loadingMore = false
    }
}
```

列表尾部的「加载更多」按钮与「相关歌单」分段：

```kotlin
// 分页（2026-10-19）：显式按钮而非自动加载 —— 圆屏上误触滚动很容易
// 一路滑到底，自动翻页会连着发好几个请求
if (hasMore) {
    item(key = "load_more") {
        Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
            Text(
                if (loadingMore) {
                    "加载中…"
                } else if (total > 0) {
                    "加载更多（${songs.size}/$total）"
                } else {
                    "加载更多"
                },
                fontSize = 10.sp,
                color = if (loadingMore) TextSecondary.copy(alpha = 0.5f) else Accent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(SurfaceGlass)
                    .clickable(enabled = !loadingMore) { loadMore() }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

// 相关歌单（type=1000）：放在歌曲之后，避免抢歌曲结果的位置
if (playlists.isNotEmpty()) {
    item(key = "playlist_header") {
        PlaylistSectionHeader("相关歌单", playlists.size)
    }
    itemsIndexed(
        playlists,
        key = { _, p -> "search_pl_${p.id}" },
        contentType = { _, _ -> "playlist" },
    ) { _, pl ->
        PlaylistCard(
            pl = pl,
            subtitle = buildString {
                pl.creatorName?.takeIf { it.isNotBlank() }?.let { append("$it · ") }
                append("${pl.trackCount} 首")
            },
        ) {
            nav.navigateWithMotion(
                Routes.playlistDetail(pl.id),
                NavMotionSpec(NavMotionKind.Pill),
            )
        }
    }
}
```

`listItemCount`（侧缘进度环估算）同步 +2 行余量：

```kotlin
result != null -> songs.size + (if (artists.isNotEmpty()) 1 else 0) + 1 +
    (if (playlists.isNotEmpty()) 1 + playlists.size else 0)
```

### 4.3 修复记录：首版翻页按钮不显示（2026-10-19 当晚）

**现象**（真机反馈）：搜索页单曲列表滚到底后，没有「加载更多」按钮，直接就是「相关歌单」分段。

**根因**：首版 `hasMore` 只读了服务端的 `result.hasMore`：

```kotlin
hasMore = songsRes?.optBoolean("hasMore", false) == true   // ❌ 恒为 false
```

`/cloudsearch/get/web` **并不稳定下发 `hasMore` 字段**（真机响应里没有它），
`optBoolean(..., false)` 于是恒为 false → `if (hasMore)` 不成立 → 按钮 item 不渲染。
评估文档里实测翻页用的是明文 `/api/search/get`（该接口有 `hasMore`），
而实现接在 weapi cloudsearch 上，两个接口的响应结构不同 —— 这是本次踩坑的来源。

**佐证**：GitHub 上真实消费 cloudsearch 的项目都不信这个字段，均自行按 `songCount` 计算：

| 项目 | 算法 |
|---|---|
| folia-major（neteaseProvider.ts） | `hasMore = offset + items.length < total`，total 取 `result.songCount` |
| Mineradio（server.js） | `hasMore = total ? nextOffset < total : items.length >= limit` |

**修复**：三级兜底（见 4.1 代码），任一路径可自愈：
服务端 `hasMore` → 有则用；`songCount` → 自算 `offset + 本页条数 < total`；
都没有 → 按「满页」保守猜（最多多发一次空请求，不会漏结果）。
矛盾时（服务端 false 但数学算出来还有）以数学为准 —— 与第三方实现共识一致。

**连带修复**：`loadMore` 原来用 `songs.size` 当请求 offset。相邻页重叠曲目被去重后
`songs.size` 会小于服务端真实 offset，继续翻会**漏歌**。改为独立的 `nextOffset` 计数器，
按本页实际返回条数推进。

---

## 5. P1：搜歌单（type=1000）

```kotlin
/**
 * 搜歌单（`type=1000`）。
 *
 * 返回结构与 `/user/playlist` 基本一致（id/name/coverImgUrl/trackCount/playCount），
 * 所以直接复用 [PlaylistItem]；`subscribed` 留空，由收藏栏自行判断。
 */
suspend fun searchPlaylists(kw: String, limit: Int = 6, offset: Int = 0): List<PlaylistItem> =
    withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("s", kw).put("limit", limit).put("offset", offset)
            .put("type", 1000).put("csrf_token", "").put("hlpretag", "").put("hlposttag", "")
        val j = we("/cloudsearch/get/web", body)
        val arr = j.optJSONObject("result")?.optJSONArray("playlists")
            ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optLong("id")
            if (id <= 0L) return@mapNotNull null
            PlaylistItem(
                id = id,
                name = o.optString("name"),
                coverUrl = o.optString("coverImgUrl").takeIf { it.isNotEmpty() },
                trackCount = o.optInt("trackCount"),
                playCount = o.optLong("playCount"),
                isMine = false,
                creatorName = o.optJSONObject("creator")?.optString("nickname")
                    ?.takeIf { it.isNotBlank() },
            )
        }
    }
```

搜索流程里单独发这一次请求，**失败静默**（歌曲结果优先，歌单是补充信息）：

```kotlin
// 歌单段单独一次请求：失败不影响歌曲结果
playlists = runCatching { NcmApi.searchPlaylists(kw.trim()) }.getOrDefault(emptyList())
```

---

## 6. P2：可播状态提前判定

### 6.1 模型（`data/Models.kt`）

```kotlin
/**
 * 版权可播状态（来自详情/歌单/专辑接口的 `privilege`）：
 * `st >= 0 && pl > 0` 为可播，`pl == 0` 或无版权为不可播。
 *
 * `null` 表示**未知** —— 搜索结果不带 `privilege`，不能把未知当成不可播，
 * 否则会把正常歌曲显示成置灰。UI 只在 `== false` 时做降级展示。
 */
val available: Boolean? = null,
```

### 6.2 解析与贴状态（`data/NcmApi.kt`）

```kotlin
/**
 * 版权状态：`privileges[]` → 「歌曲 id → 是否可播」。
 *
 * 判定用 `st` 与 `pl`，**不能用 `fee`**：
 *  - `st < 0` 表示已下架；
 *  - `pl` 是**可播码率**（320000 = 320kbps），不是布尔，`pl == 0` 才是真的没得放；
 *  - `fee` 只描述收费类型（0 免费 / 1 VIP / 8 低音质免费），VIP 歌仍然可播。
 */
private fun privilegeMap(arr: JSONArray?): Map<Long, Boolean> {
    arr ?: return emptyMap()
    val m = HashMap<Long, Boolean>(arr.length() * 2)
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val id = o.optLong("id")
        if (id <= 0L) continue
        m[id] = o.optInt("st", 0) >= 0 && o.optInt("pl", 0) > 0
    }
    return m
}

/** 把 privileges 贴到歌曲上；没有 privileges 的接口保持 null（未知），不要猜 */
private fun List<Song>.withPrivilege(arr: JSONArray?): List<Song> {
    val m = privilegeMap(arr)
    if (m.isEmpty()) return this
    return map { s -> m[s.id]?.let { s.copy(available = it) } ?: s }
}
```

三个接入点（都是加一行）：

```kotlin
// songDetail()：privileges 与 songs 一一对应，列表里能提前置灰不可播曲目
val byId = songs(j?.optJSONArray("songs"))
    .withPrivilege(j?.optJSONArray("privileges"))
    .associateBy { it.id }

// playlistDetail()：privileges 与 trackIds 等长，覆盖整个歌单
// （含只出现在 trackIds 里的曲目）
val priv = j.optJSONArray("privileges") ?: p.optJSONArray("privileges")
item to tracks.withPrivilege(priv)

// albumDetail()
songs(j.optJSONArray("songs")).withPrivilege(j.optJSONArray("privileges"))
```

### 6.3 UI 降级展示（`ui/components/SongRow.kt`）

```kotlin
/**
 * 版权受限（`privilege.st < 0 || pl == 0`）时置灰并标注「不可播」。
 * 只在**明确知道**不可播时传 true —— 搜索结果不带 privilege，状态未知，别传。
 */
unavailable: Boolean = false,
```

标题色与副标题标注：

```kotlin
Text(
    song.title, fontSize = 12.sp,
    color = when {
        isCurrent -> Accent
        unavailable -> TextTertiary
        else -> TextPrimary
    },
    // ...
)
Text(
    songSubtitle(song, aliasHint, unavailable),
    fontSize = 9.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
)
```

```kotlin
internal fun songSubtitle(
    song: Song,
    aliasHint: String?,
    unavailable: Boolean = false,
): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        append(song.artist)
        if (unavailable) {
            // 明确不可播：用第三级文字色标注，避免整行看不出为什么点了没反应
            withStyle(androidx.compose.ui.text.SpanStyle(color = TextTertiary)) {
                append(" · 不可播")
            }
        } else if (!aliasHint.isNullOrBlank()) {
            withStyle(androidx.compose.ui.text.SpanStyle(color = Accent)) {
                append(" · ")
                append(aliasHint)
            }
        }
    }
```

两个详情页传入判定（`ui/screens/detail/DetailPages.kt`，歌单与专辑各一处）：

```kotlin
// 歌单/专辑详情带 privileges：明确不可播的曲目置灰，避免点下去才失败
unavailable = song.available == false,
```

搜索页同样传，但因为云搜索无 `privilege`，`available` 基本为 null，实际不会置灰：

```kotlin
// 云搜索不带 privilege，available 多为 null（未知），只有明确 false 才置灰
unavailable = song.available == false,
```

---

## 7. 文件清单

| 文件 | 改动 |
|---|---|
| `data/Models.kt` | `Song.available: Boolean?`；新增 `ToplistItem` |
| `data/NcmApi.kt` | `privilegeMap`/`withPrivilege`；`search()` 加 offset/hasMore/total；新增 `searchPlaylists()`、`toplist()`；`songDetail`/`playlistDetail`/`albumDetail` 贴 privileges |
| `ui/screens/toplist/ToplistScreen.kt` | **新增**，榜单列表页 |
| `ui/nav/Routes.kt` | 新增 `TOPLIST` 路由 |
| `ui/nav/AppNav.kt` | 注册 `ToplistScreen` |
| `ui/screens/home/HomeScreen.kt` | 主菜单加「排行榜」入口 |
| `ui/components/NcmIcons.kt` | 新增 `Toplist`（奖杯）图标 |
| `ui/screens/search/SearchScreen.kt` | 分页累积 + 加载更多按钮 + 相关歌单分段 |
| `ui/components/SongRow.kt` | `unavailable` 参数、置灰渲染、`songSubtitle` 签名扩展 |
| `ui/screens/detail/DetailPages.kt` | 歌单/专辑详情传入 `unavailable` |

无新增依赖，无 `AndroidManifest` 变更，无资源文件新增（图标是矢量路径）。

---

## 8. 移植步骤（给原作者）

1. 拷入 `ToplistItem`（Models.kt）与 `Song.available`（Models.kt）；
2. 拷入 `NcmApi.kt` 的四个函数：`privilegeMap`、`withPrivilege`、`searchPlaylists`、`toplist`；
   把 `search()` 整体替换为带 offset 的版本（签名向后兼容）；
   在 `songDetail`/`playlistDetail`/`albumDetail` 三个解析点各加一行 `withPrivilege`；
3. 拷入 `ToplistScreen.kt`，注册路由，`homeItems` 加一行；
4. 拷入 `NcmIcons.Toplist`；
5. 把 `SongRow`/`songSubtitle` 替换为新版（`unavailable` 有默认值，旧调用点不受影响）；
   两个详情页各加一行 `unavailable = song.available == false`；
6. 搜索页改动最大（分页 state + 歌单分段），建议整段对照合并。

---

## 9. 测试清单

- [x] `assembleDebug` 编译通过（无 error，仅一条既有的 nullable 警告，已顺手修掉）
- [ ] 主页出现「排行榜」入口，图标为奖杯
- [ ] 排行榜列表加载出 64 个榜单，副标题显示「每周四更新 · 100 首」这类文案
- [ ] 点「飙升榜」进入歌单详情，能正常拉到 100 首歌并播放
- [ ] 搜索「海阔天空」：首屏 20 条，底部出现「加载更多（20/306）」；连点几次总数增长；
      翻到底 `hasMore=false` 后按钮消失，不再发请求（曾因 cloudsearch 不下发 hasMore 导致按钮不显示，已按 songCount 自算修复，见 4.3）
- [ ] 搜索「华语」：歌曲列表后出现「相关歌单」分段，点击进歌单详情
- [ ] 打开一个含灰色歌曲的歌单/专辑：不可播曲目置灰、副标题有「· 不可播」，点击仍可点但不报错
- [ ] 搜索结果（无 privilege）不出现误置灰
- [ ] 弱网/未登录：榜单页显示「榜单加载失败，稍后重试」，不崩溃

---

## 10. 已知限制与后续

- **搜索建议（type=suggest）未接**：评估里列为 P2，属于体验优化而非功能缺口，本次未做；
- **MV / 电台未接**：P3，圆屏手表展示空间有限；
- **评估第 5 节两个「值得抄的实现细节」未做**，留作后续：
  1. 空响应显式识别（`post()` 里 `text.isBlank()` 时返回 `code=-1` + 「空响应（参数或风控）」），
     避免把 `Unterminated string` 当普通网络错误；
  2. 错误码映射（301 登录失效不重试 / 404 内容不存在 / -460 风控退避），
     目前只有 `lastError` 字符串，UI 靠文案判断。
