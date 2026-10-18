package com.novastreams.app.ui.mobile

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.novastreams.app.appContainer
import com.novastreams.app.core.video.VideoKind
import com.novastreams.app.feature.auth.LoginScreen
import com.novastreams.app.ui.components.LocalChannelPageOpener
import com.novastreams.app.ui.components.StreamerScreen
import com.novastreams.app.ui.components.VideoScreen
import com.novastreams.app.ui.Routes
import com.novastreams.app.ui.components.novaClickable
import com.novastreams.app.ui.theme.LocalNovaColors
import com.novastreams.app.ui.theme.NovaColors

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Главная", Icons.Rounded.Home),
    Tab(Routes.CATEGORIES, "Категории", Icons.Rounded.Category),
    Tab(Routes.SEARCH, "Поиск", Icons.Rounded.Search),
    Tab(Routes.LIBRARY, "Каналы", Icons.Rounded.Tv),
    Tab(Routes.PROFILE, "Профиль", Icons.Rounded.Person),
)

@Composable
fun MobileApp() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = route in tabs.map { it.route }

    CompositionLocalProvider(LocalChannelPageOpener provides { id -> nav.navigate(Routes.streamer(id)) }) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NovaColors.Background)
    ) {
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.weight(1f),
            enterTransition = { fadeIn(androidx.compose.animation.core.tween(180)) },
            exitTransition = { fadeOut(androidx.compose.animation.core.tween(120)) },
            popEnterTransition = { fadeIn(androidx.compose.animation.core.tween(180)) },
            popExitTransition = { fadeOut(androidx.compose.animation.core.tween(120)) },
        ) {
            composable(Routes.HOME) {
                MobileHomeScreen(
                    onOpenChannel = { nav.navigate(Routes.channel(it)) },
                    onOpenCategory = { nav.navigate(Routes.category(it)) },
                    onOpenSearch = { nav.navigateTab(Routes.SEARCH) },
                    onOpenProfile = { nav.navigateTab(Routes.PROFILE) },
                )
            }
            composable(Routes.CATEGORIES) {
                MobileCategoriesScreen(
                    onOpenCategory = { nav.navigate(Routes.category(it)) },
                )
            }
            composable(Routes.SEARCH) {
                MobileSearchScreen(
                    // Канал из поиска открывается страницей стримера: эфир, записи, клипы.
                    onOpenChannel = { nav.navigate(Routes.streamer(it)) },
                    onOpenCategory = { nav.navigate(Routes.category(it)) },
                )
            }
            composable(Routes.LIBRARY) {
                MobileLibraryScreen(onOpenChannel = { nav.navigate(Routes.streamer(it)) })
            }
            composable(Routes.PROFILE) {
                MobileProfileScreen(
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
                MobileChannelScreen(
                    channelId = id,
                    onBack = { nav.popBackStack() },
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
                    onWatchLive = { nav.navigate(Routes.channel(id)) },
                    onOpenVideo = { kind, vid -> nav.navigate(Routes.video(kind.routeName, vid)) },
                    showBackButton = true,
                    backButton = { BackButton(onBack = { nav.popBackStack() }) },
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
                MobilePlayerScreen(channelId = id, onBack = { nav.popBackStack() })
            }
            composable(
                Routes.CATEGORY,
                arguments = listOf(navArgument(Routes.CATEGORY_ARG) { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString(Routes.CATEGORY_ARG).orEmpty()
                MobileCategoryScreen(
                    categoryId = id,
                    onBack = { nav.popBackStack() },
                    onOpenChannel = { nav.navigate(Routes.channel(it)) },
                )
            }
        }
        if (showBar) {
            val context = androidx.compose.ui.platform.LocalContext.current
            MobileBottomBar(currentRoute = route) {
                if (it == route) {
                    // Повторное нажатие на текущую вкладку — обновить её данные.
                    context.appContainer().refreshBus.request(it)
                    android.widget.Toast.makeText(context, "Обновляем…", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    nav.navigateTab(it)
                }
            }
        }
    }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun MobileBottomBar(currentRoute: String?, onSelect: (String) -> Unit) {
    val accent = LocalNovaColors.current.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NovaColors.Card)
            .navigationBarsPadding()
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val selected = tab.route == currentRoute
            Column(
                modifier = Modifier
                    .weight(1f)
                    .novaClickable(RoundedCornerShape(12.dp), focusScale = 1f) { onSelect(tab.route) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = if (selected) accent else NovaColors.TextSecondary,
                )
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) accent else NovaColors.TextSecondary,
                )
            }
        }
    }
}
