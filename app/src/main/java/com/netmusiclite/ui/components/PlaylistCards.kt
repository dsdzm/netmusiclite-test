package com.netmusiclite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netmusiclite.data.PlaylistItem
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.AccentSoft
import com.netmusiclite.ui.theme.SurfaceGlass
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary

/**
 * 歌单分区标题：标题 + 数量 + 可选右侧动作。
 * 「我的歌单」「收藏的专辑和歌单」「推荐」三个页面共用。
 */
@Composable
fun PlaylistSectionHeader(
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
fun PlaylistCard(
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
fun PlaylistCollectButton(subscribed: Boolean, onClick: () -> Unit) {
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
