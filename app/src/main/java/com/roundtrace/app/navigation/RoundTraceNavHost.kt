package com.roundtrace.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.roundtrace.app.R
import com.roundtrace.app.RoundTraceApplication
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.ui.component.EmptyState
import com.roundtrace.app.core.ui.component.RoundTraceScaffold
import com.roundtrace.app.di.AppContainer
import com.roundtrace.app.di.ViewModelFactories
import com.roundtrace.app.feature.claim.ClaimScreen
import com.roundtrace.app.feature.claim.ClaimViewModel
import com.roundtrace.app.feature.create.CreateMatchScreen
import com.roundtrace.app.feature.create.CreateMatchViewModel
import com.roundtrace.app.feature.detail.MatchDetailScreen
import com.roundtrace.app.feature.detail.MatchDetailViewModel
import com.roundtrace.app.feature.home.HomeScreen
import com.roundtrace.app.feature.home.HomeViewModel
import com.roundtrace.app.feature.record.RecordScreen
import com.roundtrace.app.feature.replay.ReplayScreen
import com.roundtrace.app.feature.replay.ReplayViewModel
import com.roundtrace.app.feature.reveal.RevealScreen
import com.roundtrace.app.feature.reveal.RevealViewModel
import com.roundtrace.app.feature.settings.SettingsScreen
import com.roundtrace.app.feature.settings.SettingsViewModel
import com.roundtrace.app.feature.record.RecordViewModel

/**
 * 应用导航宿主（TASKS.md T09 / T40 / T44）。
 *
 * 三个主 Tab + 两个工作流页面（创建对局、现场记录）。
 * 复盘、揭晓、详情、设置页留到 M4/M5，本轮**不注册占位页**。
 *
 * 系统返回行为：底部 Tab 之间用 `popUpTo(startDestination) + saveState/restoreState`，
 * 从工作流页面返回会回到对应 Tab 而不是退出 App。
 */
@Composable
fun RoundTraceNavHost(
    navController: NavHostController = rememberNavController()
) {
    val container = rememberContainer()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.HOME

    val navigateToTab: (String) -> Unit = { route ->
        if (route != currentRoute) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(Routes.SPLASH) {
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1500)
                navController.navigate(Routes.HOME) {
                    popUpTo(Routes.SPLASH) { inclusive = true }
                }
            }
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color(0xFFFBF6EF)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.splash_duck),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
        }
        composable(Routes.HOME) {
            val homeViewModel: HomeViewModel = viewModel(
                factory = ViewModelFactories.home(container)
            )
            val state by homeViewModel.uiState.collectAsStateWithLifecycle()

            RoundTraceScaffold(
                title = stringResource(R.string.home_title),
                selectedRoute = Routes.HOME,
                onSelectDestination = navigateToTab,
                actions = {
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.settings_open_action)
                        )
                    }
                }
            ) { contentPadding ->
                HomeScreen(
                    state = state,
                    onCreateMatch = { navController.navigate(Routes.CREATE_MATCH) },
                    onOpenMatch = { matchId -> navController.navigate(Routes.record(matchId)) },
                    onFilterChange = homeViewModel::onFilterChange,
                    onRetry = homeViewModel::retry,
                    contentPadding = contentPadding,
                    onRenameMatch = homeViewModel::rename,
                    onDeleteMatch = homeViewModel::delete,
                    onOpenDetail = { matchId -> navController.navigate(Routes.detail(matchId)) }
                )
            }
        }

        composable(Routes.RECORDS) {
            RoundTraceScaffold(
                title = stringResource(R.string.records_title),
                selectedRoute = Routes.RECORDS,
                onSelectDestination = navigateToTab
            ) { contentPadding ->
                // 记录 Tab 在没有选中对局时展示空态：真实录入入口是「首页 → 某局 → 记录」
                EmptyState(
                    icon = Icons.Outlined.EditNote,
                    title = stringResource(R.string.records_empty_title),
                    description = stringResource(R.string.records_empty_desc),
                    actionLabel = stringResource(R.string.records_empty_action),
                    onAction = { navigateToTab(Routes.HOME) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                )
            }
        }

        composable(Routes.REVIEW) {
            RoundTraceScaffold(
                title = stringResource(R.string.review_title),
                selectedRoute = Routes.REVIEW,
                onSelectDestination = navigateToTab
            ) { contentPadding ->
                EmptyState(
                    icon = Icons.Outlined.Insights,
                    title = stringResource(R.string.review_empty_title),
                    description = stringResource(R.string.review_empty_desc),
                    actionLabel = stringResource(R.string.review_empty_action),
                    onAction = { navigateToTab(Routes.RECORDS) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                )
            }
        }

        composable(Routes.CREATE_MATCH) {
            val createViewModel: CreateMatchViewModel = viewModel(
                factory = ViewModelFactories.create(container)
            )
            val state by createViewModel.state.collectAsStateWithLifecycle()

            RoundTraceScaffold(
                title = stringResource(R.string.create_title),
                selectedRoute = null,
                onSelectDestination = navigateToTab,
                showBottomBar = false
            ) { contentPadding ->
                CreateMatchScreen(
                    state = state,
                    onPlayerCountChange = createViewModel::onPlayerCountChange,
                    onRoleCountChange = createViewModel::onRoleCountChange,
                    onMySeatChange = createViewModel::onMySeatChange,
                    onLeaderChange = createViewModel::onLeaderChange,
                    onTitleChange = createViewModel::onTitleChange,
                    onSeatNameChange = createViewModel::onSeatNameChange,
                    onSeatPositionChange = createViewModel::onSeatPositionChange,
                    onSeatAvatarChange = createViewModel::onSeatAvatarChange,
                    onNext = createViewModel::onNext,
                    onBack = createViewModel::onBack,
                    onConfirm = createViewModel::confirm,
                    onCreated = { matchId ->
                        navController.navigate(Routes.record(matchId.raw)) {
                            popUpTo(Routes.HOME)
                        }
                    },
                    contentPadding = contentPadding
                )
            }
        }

        composable(
            route = Routes.DETAIL_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_MATCH_ID) { type = NavType.StringType })
        ) { entry ->
            val matchIdRaw = entry.arguments?.getString(Routes.ARG_MATCH_ID).orEmpty()
            val detailViewModel: MatchDetailViewModel = viewModel(
                factory = ViewModelFactories.matchDetail(container, MatchId(matchIdRaw))
            )
            val state by detailViewModel.state.collectAsStateWithLifecycle()

            RoundTraceScaffold(
                title = stringResource(R.string.detail_title),
                selectedRoute = null,
                onSelectDestination = navigateToTab,
                showBottomBar = false
            ) { contentPadding ->
                MatchDetailScreen(
                    state = state,
                    onAction = detailViewModel::onAction,
                    onDeleted = { navController.popBackStack(Routes.HOME, inclusive = false) },
                    contentPadding = contentPadding
                )
            }
        }

        composable(Routes.SETTINGS) {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = ViewModelFactories.settings(container)
            )
            val state by settingsViewModel.state.collectAsStateWithLifecycle()

            RoundTraceScaffold(
                title = stringResource(R.string.settings_title),
                selectedRoute = null,
                onSelectDestination = navigateToTab,
                showBottomBar = false
            ) { contentPadding ->
                SettingsScreen(
                    state = state,
                    onAction = settingsViewModel::onAction,
                    contentPadding = contentPadding
                )
            }
        }

        composable(
            route = Routes.CLAIM_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_MATCH_ID) { type = NavType.StringType })
        ) { entry ->
            val matchIdRaw = entry.arguments?.getString(Routes.ARG_MATCH_ID).orEmpty()
            val claimViewModel: ClaimViewModel = viewModel(
                factory = ViewModelFactories.claim(container, MatchId(matchIdRaw))
            )
            val state by claimViewModel.state.collectAsStateWithLifecycle()

            RoundTraceScaffold(
                title = stringResource(R.string.claim_title),
                selectedRoute = null,
                onSelectDestination = navigateToTab,
                showBottomBar = false
            ) { contentPadding ->
                ClaimScreen(
                    state = state,
                    onAction = claimViewModel::onAction,
                    contentPadding = contentPadding
                )
            }
        }

        composable(
            route = Routes.REVEAL_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_MATCH_ID) { type = NavType.StringType })
        ) { entry ->
            val matchIdRaw = entry.arguments?.getString(Routes.ARG_MATCH_ID).orEmpty()
            val revealViewModel: RevealViewModel = viewModel(
                factory = ViewModelFactories.reveal(container, MatchId(matchIdRaw))
            )
            val state by revealViewModel.state.collectAsStateWithLifecycle()

            RoundTraceScaffold(
                title = stringResource(R.string.reveal_title),
                selectedRoute = null,
                onSelectDestination = navigateToTab,
                showBottomBar = false
            ) { contentPadding ->
                RevealScreen(
                    state = state,
                    onAction = revealViewModel::onAction,
                    contentPadding = contentPadding
                )
            }
        }

        composable(
            route = Routes.REPLAY_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_MATCH_ID) { type = NavType.StringType })
        ) { entry ->
            val matchIdRaw = entry.arguments?.getString(Routes.ARG_MATCH_ID).orEmpty()
            val replayViewModel: ReplayViewModel = viewModel(
                factory = ViewModelFactories.replay(container, MatchId(matchIdRaw))
            )
            val state by replayViewModel.uiState.collectAsStateWithLifecycle()

            RoundTraceScaffold(
                title = stringResource(R.string.replay_title),
                selectedRoute = null,
                onSelectDestination = navigateToTab,
                showBottomBar = false
            ) { contentPadding ->
                ReplayScreen(
                    state = state,
                    onAction = replayViewModel::onAction,
                    onBackToRecord = { navController.navigate(Routes.record(matchIdRaw)) },
                    contentPadding = contentPadding
                )
            }
        }

        composable(
            route = Routes.RECORD_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_MATCH_ID) { type = NavType.StringType })
        ) { entry ->
            val matchIdRaw = entry.arguments?.getString(Routes.ARG_MATCH_ID).orEmpty()
            val recordViewModel: RecordViewModel = viewModel(
                factory = ViewModelFactories.record(container, MatchId(matchIdRaw))
            )
            val state by recordViewModel.uiState.collectAsStateWithLifecycle()

            RoundTraceScaffold(
                title = state.match?.title ?: stringResource(R.string.record_title),
                selectedRoute = null,
                onSelectDestination = navigateToTab,
                showBottomBar = false
            ) { contentPadding ->
                RecordScreen(
                    state = state,
                    onAction = recordViewModel::onAction,
                    contentPadding = contentPadding,
                    onOpenClaim = { navController.navigate(Routes.claim(matchIdRaw)) },
                    onOpenReveal = { navController.navigate(Routes.reveal(matchIdRaw)) },
                    onOpenReplay = { navController.navigate(Routes.replay(matchIdRaw)) }
                )
            }
        }
    }
}

/** 取得进程级 DI 容器（`RoundTraceApplication` 是唯一持有者）。 */
@Composable
private fun rememberContainer(): AppContainer {
    val context = LocalContext.current
    return remember(context) {
        (context.applicationContext as RoundTraceApplication).container
    }
}
