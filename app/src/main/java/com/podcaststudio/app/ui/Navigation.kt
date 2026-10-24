package com.podcaststudio.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.podcaststudio.app.AppContainer
import com.podcaststudio.app.ui.create.CreateScreen
import com.podcaststudio.app.ui.create.CreateViewModel
import com.podcaststudio.app.ui.create.ExoVoicePreview
import com.podcaststudio.app.ui.generation.GenerationScreen
import com.podcaststudio.app.ui.generation.GenerationViewModel
import com.podcaststudio.app.ui.library.LibraryScreen
import com.podcaststudio.app.ui.library.LibraryViewModel
import com.podcaststudio.app.ui.player.PlayerScreen
import com.podcaststudio.app.ui.player.PlayerViewModel
import com.podcaststudio.app.ui.script.ScriptScreen
import com.podcaststudio.app.ui.script.ScriptViewModel
import com.podcaststudio.app.ui.settings.SettingsScreen
import com.podcaststudio.app.ui.settings.SettingsViewModel
import com.podcaststudio.app.ui.theme.PodcastStudioTheme

object Routes {
    const val CREATE = "create"
    const val GENERATION = "generation"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val PLAYER = "player/{episodeId}"
    const val SCRIPT = "script/{episodeId}"
    fun player(id: String) = "player/$id"
    fun script(id: String) = "script/$id"
}

private data class TopLevel(val route: String, val label: String, val icon: ImageVector)

private val topLevel = listOf(
    TopLevel(Routes.CREATE, "Create", Icons.Filled.Add),
    TopLevel(Routes.LIBRARY, "Library", Icons.Filled.VideoLibrary),
    TopLevel(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun PodcastStudioRoot(container: AppContainer) {
    PodcastStudioTheme {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route
        val factory = viewModelFactory {
            initializer { CreateViewModel(container.voices, container.coordinator, container.aiSettings, ExoVoicePreview(container.context)) }
            initializer { GenerationViewModel(container.coordinator) }
            initializer { LibraryViewModel(container.episodes, container::deleteEpisode) }
            initializer { SettingsViewModel(container.secrets, container.aiSettings) }
        }

        Scaffold(
            bottomBar = {
                if (topLevel.any { it.route == route }) {
                    NavigationBar {
                        topLevel.forEach { item ->
                            NavigationBarItem(
                                selected = route == item.route,
                                onClick = {
                                    nav.navigate(item.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = Routes.CREATE,
                modifier = Modifier.padding(padding),
            ) {
                composable(Routes.CREATE) {
                    CreateScreen(
                        viewModel = viewModel(factory = factory),
                        onStarted = { nav.navigate(Routes.GENERATION) },
                    )
                }
                composable(Routes.GENERATION) {
                    GenerationScreen(
                        viewModel = viewModel(factory = factory),
                        onOpenEpisode = { id ->
                            nav.navigate(Routes.player(id)) { popUpTo(Routes.CREATE) }
                        },
                        onDone = { nav.popBackStack(Routes.CREATE, inclusive = false) },
                    )
                }
                composable(Routes.LIBRARY) {
                    LibraryScreen(
                        viewModel = viewModel(factory = factory),
                        onPlay = { id -> nav.navigate(Routes.player(id)) },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(viewModel = viewModel(factory = factory))
                }
                composable(
                    Routes.PLAYER,
                    arguments = listOf(navArgument("episodeId") { type = NavType.StringType }),
                ) { backStack ->
                    val id = backStack.arguments?.getString("episodeId").orEmpty()
                    PlayerScreen(
                        viewModel = viewModel(
                            key = "player-$id",
                            factory = viewModelFactory {
                                initializer { PlayerViewModel(id, container.episodes, container.playerConnector) }
                            },
                        ),
                        onBack = { nav.popBackStack() },
                        onOpenScript = { nav.navigate(Routes.script(id)) },
                    )
                }
                composable(
                    Routes.SCRIPT,
                    arguments = listOf(navArgument("episodeId") { type = NavType.StringType }),
                ) { backStack ->
                    val id = backStack.arguments?.getString("episodeId").orEmpty()
                    ScriptScreen(
                        viewModel = viewModel(
                            key = "script-$id",
                            factory = viewModelFactory { initializer { ScriptViewModel(id, container.content) } },
                        ),
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}
