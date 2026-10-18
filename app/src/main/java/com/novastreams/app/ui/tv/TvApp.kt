package com.novastreams.app.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import com.novastreams.app.appContainer
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.novastreams.app.core.video.VideoKind
import com.novastreams.app.feature.auth.LoginScreen
import com.novastreams.app.ui.components.LocalChannelPageOpener
import com.novastreams.app.ui.components.StreamerScreen
import com.novastreams.app.ui.components.VideoScreen
import com.novastreams.app.ui.Routes
import com.novastreams.app.ui.components.novaClickable
import com.novastreams.app.ui.theme.LocalNovaColors
import com.novastreams.app.ui.theme.NovaColors

private data class RailItem(val route: String, val label: String, val icon: ImageVector)

private val railItems = listOf(
    RailItem(Routes.HOME, "Главная", Icons.Rounded.Home),
    RailItem(Routes.ACTIVE, "Активные сейчас", Icons.Rounded.LiveTv),
    RailItem(Routes.CATEGORIES, "Категории", Icons.Rounded.Category),
    RailItem(Routes.SEARCH, "Поиск", Icons.Rounded.Search),
    RailItem(Routes.LIBRARY, "Каналы", Icons.Rounded.Tv),
    RailItem(Routes.PROFILE, "Настройки", Icons.Rounded.Settings),
)

@Composable
fun TvApp() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val topLevel = railItems.map { it.route }
    val isTopLevel = route in topLevel

    // Back на верхнем уровне (кроме «Главной») возвращает на «Главную»; на «Главной» приложение закрывается.
    BackHandler(enabled = isTopLevel && route != Routes.HOME) {
        nav.navigateTop(Routes.HOME)
    }

    val context = LocalContext.current
    val refreshable = isTopLevel || route == Routes.CATEGORY
    // Обновить данные текущего экрана и показать короткое подтверждение.
    val refreshCurrent: (String?) -> Unit = { r ->
        if (r != null) {
            context.appContainer().refreshBus.request(r)
            android.widget.Toast.makeText(context, "Обновляем…", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    CompositionLocalProvider(LocalChannelPageOpener provides { id -> nav.navigate(Routes.streamer(id)) }) {
    Box(Modifier.fillMaxSize()) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(NovaColors.Background)
            // «Меню» (или F5) на пульте — обновить текущий экран (главная, категории, каналы…).
            .onPreviewKeyEvent { event ->
                if (refreshable && event.type == KeyEventType.KeyDown && (event.key == Key.Menu || event.key == Key.F5)) {
                    refreshCurrent(route)
                    true
                } else {
                    false
                }
            }
    ) {
        if (isTopLevel) {
            TvRail(
                currentRoute = route,
                // Повторное нажатие или долгое удержание OK на текущем пункте меню — обновить этот раздел.
                onRefresh = { refreshCurrent(it) },
            ) { nav.navigateTop(it) }
        }
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(120)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { fadeOut(tween(120)) },
        ) {
            composable(Routes.HOME) {
                TvHomeScreen(
                    // Живой эфир — сразу плеер; записи/клипы — кнопка «Открыть канал» в плеере.
                    onOpenChannel = { nav.navigate(Routes.player(it)) },
                    onOpenCategory = { nav.navigate(Routes.category(it)) },
                )
            }
            composable(Routes.ACTIVE) {
                TvActiveScreen(onOpenChannel = { nav.navigate(Routes.player(it)) })
            }
            composable(Routes.CATEGORIES) {
                TvCategoriesScreen(
                    onOpenCategory = { nav.navigate(Routes.category(it)) },
                )
            }
            composable(Routes.SEARCH) {
                TvSearchScreen(
                    // Канал из поиска открывается страницей стримера: эфир, записи, клипы.
                    onOpenChannel = { nav.navigate(Routes.streamer(it)) },
                    onOpenCategory = { nav.navigate(Routes.category(it)) },
                )
            }
            composable(Routes.LIBRARY) {
                TvLibraryScreen(
                    onOpenChannel = { nav.navigate(Routes.streamer(it)) },
                    // Канал в эфире — сразу плеер; не в эфире — страница стримера (записи и клипы).
                    onOpenLive = { nav.navigate(Routes.player(it)) },
                )
            }
            composable(Routes.PROFILE) {
                TvSettingsScreen(
                    onOpenChannel = { nav.navigate(Routes.channel(it)) },
                    onLogin = { nav.navigate(Routes.LOGIN) },
                )
            }
            composable(Routes.LOGIN) {
                LoginScreen(onClose = { nav.popBackStack() })
            }
            composable(
                Routes.CHANNEL,
                arguments = listOf(navArgument(Routes.CHANNEL_ARG) { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString(Routes.CHANNEL_ARG).orEmpty()
                TvChannelScreen(
                    channelId = id,
                    onBack = { nav.popBackStack() },
                    onOpenPlayer = { nav.navigate(Routes.player(id)) },
                    onOpenStreamer = { nav.navigate(Routes.streamer(id)) },
                )
            }
            composable(
                Routes.STREAMER,
                arguments = listOf(navArgument(Routes.CHANNEL_ARG) { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString(Routes.CHANNEL_ARG).orEmpty()
                StreamerScreen(
                    channelId = id,
                    onBack = { nav.popBackStack() },
                    onWatchLive = { nav.navigate(Routes.player(id)) },
                    onOpenVideo = { kind, vid -> nav.navigate(Routes.video(kind.routeName, vid)) },
                    showBackButton = true,
                )
            }
            composable(
                Routes.VIDEO,
                arguments = listOf(
                    navArgument(Routes.VIDEO_KIND_ARG) { type = NavType.StringType },
                    navArgument(Routes.VIDEO_ID_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                VideoScreen(
                    kind = VideoKind.fromRoute(entry.arguments?.getString(Routes.VIDEO_KIND_ARG)),
                    id = entry.arguments?.getString(Routes.VIDEO_ID_ARG).orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.PLAYER,
                arguments = listOf(navArgument(Routes.CHANNEL_ARG) { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString(Routes.CHANNEL_ARG).orEmpty()
                TvPlayerScreen(channelId = id, onBack = { nav.popBackStack() })
            }
            composable(
                Routes.CATEGORY,
                arguments = listOf(navArgument(Routes.CATEGORY_ARG) { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString(Routes.CATEGORY_ARG).orEmpty()
                TvCategoryScreen(
                    categoryId = id,
                    onBack = { nav.popBackStack() },
                    onOpenChannel = { nav.navigate(Routes.player(it)) },
                )
            }
        }
    }
    // В полноэкранном плеере часы размещаются внутри TvPlayerScreen,
    // чтобы их можно было скрывать при открытом чате.
    if (route != Routes.PLAYER) {
        TvClockOverlay(isPlayerScreen = route == Routes.VIDEO)
    }
    }
    }
}

private fun NavHostController.navigateTop(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Компактная боковая навигация: только значки, активный пункт подсвечен акцентом. */
@Composable
private fun TvRail(currentRoute: String?, onRefresh: (String) -> Unit, onSelect: (String) -> Unit) {
    val colors = LocalNovaColors.current
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(84.dp)
            .background(NovaColors.Card)
            .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        railItems.forEach { item ->
            val selected = item.route == currentRoute
            val shape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .novaClickable(
                        shape,
                        focusScale = 1.08f,
                        onLongClick = { if (selected) onRefresh(item.route) else onSelect(item.route) },
                    ) { if (selected) onRefresh(item.route) else onSelect(item.route) }
                    .clip(shape)
                    .background(if (selected) colors.accentSoft else androidx.compose.ui.graphics.Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = if (selected) colors.accent else NovaColors.TextSecondary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
