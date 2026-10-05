package com.aliya.hy_vq.anime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「说明」Tab 的 Compose 实现 —— 本项目**首个 Compose 页面**。
 *
 * 为什么用它作首屏：内容为静态信息（功能/数据源/安全/反馈），
 * 无状态耦合、失败面最小，能先验证 **ComposeView 内嵌进 XML View 体系** 这条链路，
 * 后续再迁复杂页面。旧页面仍是 XML View —— 按「新页面用 Compose」的约定渐进迁移。
 */
@Composable
fun AnimeInfoScreen(
    moduleCount: Int,
    sourceCount: Int,
    danmakuDefaultOn: Boolean,
    cacheCount: Int,
    onFeedback: (String) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            SectionCard(title = "状态概览") {
                StatRow("已导入源", "$sourceCount 个")
                StatRow("弹幕默认", if (danmakuDefaultOn) "开启" else "关闭")
                StatRow("已缓存视频", "$cacheCount 个")
                StatRow("内置模块", "$moduleCount 个")
            }

            SectionCard(title = "两段式搜索") {
                Para("第一步只查元数据库（B站 / AniList / Bangumi 镜像），秒回并带封面评分；")
                Para("选中某部番后，第二步才去查该番的播放源。")
            }

            SectionCard(title = "自动选源与线路") {
                Para("线路按 tier 升序排列，未评测排后；点线路胶囊即可播放。")
                Para("解析失败会自动改用内置浏览器（WebView）解析 —— 该方式会执行第三方页面脚本，请知悉。")
            }

            SectionCard(title = "弹幕与离线缓存") {
                StatRow("弹幕来源", "B站番剧弹幕")
                StatRow("离线缓存", "直链 / HLS 分段拼接 .ts")
                Para("缓存存于应用专属目录，卸载即清，无需存储权限。")
            }

            SectionCard(title = "数据来源") {
                Para("· 元数据：B站番剧索引、AniList（仅标题封面评分，不涉播放）")
                Para("· 播放源：你自行导入的第三方订阅，本软件不提供、不存储内容")
                Para("· 源由第三方维护，随时可能失效（属此类工具的固有问题）")
            }

            SectionCard(title = "反馈渠道") {
                FeedbackRow("邮箱", "3077094639@qq.com", onFeedback)
                FeedbackRow("QQ 群", "119544089", onFeedback)
                FeedbackRow("QQ 私聊", "3077094639", onFeedback)
            }

            Text(
                text = "详见「关于」页的致谢与首次启动协议",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        }
    }
}

/** 深浅色均由 Material 主题驱动 —— 暗色主题接入后自动生效 */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(18.dp))
            // 与底栏/整体统一的浅色渐变（原为灰色，风格割裂）
            .background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    // 主调：浅蓝 → 浅紫（与全模块卡片统一）
                    listOf(
                        Color(0xFFE7F1FF),
                        Color(0xFFF1E9FF),
                    )
                )
            )
            .padding(16.dp),
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF6C63E8),   // 浅紫系强调（与浅蓝→浅紫卡片同族）
            modifier = Modifier.padding(bottom = 10.dp),
        )
        content()
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF6C63E8),
        )
    }
}

@Composable
private fun Para(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun FeedbackRow(label: String, value: String, onClick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFFFFFF))
            .clickable { onClick(value) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = "$value  复制",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF6C63E8),
        )
    }
}

/**
 * 供 Java 调用的门面。
 *
 * 为什么需要：{@code @Composable} 函数由 Compose 编译器改写签名，**无法从 Java 直调**。
 * 这里暴露一个普通静态方法，内部构建 [androidx.compose.ui.platform.ComposeView]
 * 并装填 Compose 内容，Java 侧只拿到一个普通 `View`。
 */
object AnimeInfoHost {
    @JvmStatic
    fun create(
        context: android.content.Context,
        moduleCount: Int,
        sourceCount: Int,
        danmakuOn: Boolean,
        cacheCount: Int,
    ): android.view.View {
        return androidx.compose.ui.platform.ComposeView(context).apply {
            setContent {
                AnimeInfoScreen(
                    moduleCount = moduleCount,
                    sourceCount = sourceCount,
                    danmakuDefaultOn = danmakuOn,
                    cacheCount = cacheCount,
                    onFeedback = { value -> copyToClipboard(context, value) },
                )
            }
        }
    }

    private fun copyToClipboard(context: android.content.Context, value: String) {
        try {
            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
            cm?.setPrimaryClip(
                android.content.ClipData.newPlainText("HY_VQ", value))
            android.widget.Toast.makeText(context, "已复制：$value",
                android.widget.Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            android.widget.Toast.makeText(context, "复制失败：$value",
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
