package com.roundtrace.app.core.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.roundtrace.app.R
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.navigation.Routes

/**
 * 三个主 Tab 的元数据（图标 / 文案 / 无障碍描述），集中定义避免散落。
 */
enum class TopLevelTab(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int,
    val descriptionRes: Int
) {
    HOME(
        route = Routes.HOME,
        icon = Icons.Outlined.Home,
        labelRes = R.string.tab_home,
        descriptionRes = R.string.tab_home_desc
    ),
    RECORDS(
        route = Routes.RECORDS,
        icon = Icons.Outlined.EditNote,
        labelRes = R.string.tab_record,
        descriptionRes = R.string.tab_record_desc
    ),
    REVIEW(
        route = Routes.REVIEW,
        icon = Icons.Outlined.Insights,
        labelRes = R.string.tab_review,
        descriptionRes = R.string.tab_review_desc
    );

    companion object {
        fun fromRoute(route: String?): TopLevelTab =
            entries.firstOrNull { it.route == route } ?: HOME
    }
}

/**
 * 应用主框架：TopAppBar + 底部 3 Tab + 内容槽（DESIGN_SPEC §4.1）。
 *
 * 尺寸约束：底部 Tab 高度 ≥ 64dp（NavigationBar 默认 80dp，此处再显式声明下限），
 * 图标 24dp + 文字 12sp（`labelMedium`）。每个 Tab 都有 `contentDescription`。
 *
 * @param title TopBar 标题
 * @param selectedRoute 当前选中的 Tab 路由
 * @param onSelectDestination Tab 点击回调（路由字符串）
 * @param actions TopBar 右侧操作区
 * @param showBottomBar 工作流页面可关闭底部栏
 * @param content 内容槽，接收 Scaffold 给的 `PaddingValues`（调用方必须消费它）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoundTraceScaffold(
    title: String,
    selectedRoute: String?,
    onSelectDestination: (String) -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    showBottomBar: Boolean = true,
    content: @Composable (PaddingValues) -> Unit
) {
    val selectedTab = TopLevelTab.fromRoute(selectedRoute)

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.TabHeight),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = Dimens.CardElevation
                ) {
                    TopLevelTab.entries.forEach { tab ->
                        val label = stringResource(tab.labelRes)
                        val description = stringResource(tab.descriptionRes)
                        val selected = tab == selectedTab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { onSelectDestination(tab.route) },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = description,
                                    modifier = Modifier
                                        .size(Dimens.TabIconSize)
                                        .semantics { contentDescription = description }
                                )
                            },
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            alwaysShowLabel = true,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        },
        content = { padding ->
            content(padding)
        }
    )
}
