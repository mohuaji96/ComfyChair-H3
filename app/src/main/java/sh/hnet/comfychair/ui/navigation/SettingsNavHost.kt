package sh.hnet.comfychair.ui.navigation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import sh.hnet.comfychair.R
import sh.hnet.comfychair.navigation.SettingsRoute
import sh.hnet.comfychair.ui.screens.AboutSettingsScreen
import sh.hnet.comfychair.ui.screens.ApiProviderEditScreen
import sh.hnet.comfychair.ui.screens.ApiProviderListScreen
import sh.hnet.comfychair.ui.screens.ApplicationSettingsScreen
import sh.hnet.comfychair.ui.screens.PromptSettingsScreen
import sh.hnet.comfychair.ui.screens.ServerSettingsScreen
import sh.hnet.comfychair.ui.screens.TemplateDetailScreen
import sh.hnet.comfychair.ui.screens.TemplateLibraryScreen
import sh.hnet.comfychair.ui.screens.WorkflowsSettingsScreen
import sh.hnet.comfychair.viewmodel.PromptOptimizerViewModel
import sh.hnet.comfychair.viewmodel.SettingsViewModel
import sh.hnet.comfychair.viewmodel.WorkflowManagementViewModel

@Composable
fun SettingsNavHost(
    settingsViewModel: SettingsViewModel,
    workflowManagementViewModel: WorkflowManagementViewModel,
    onNavigateToGeneration: () -> Unit,
    onLogout: () -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // One instance for the whole optimiser section, so the home page and the edit pages observe
    // the same state instead of each reloading a private copy on entry.
    val promptViewModel: PromptOptimizerViewModel = viewModel()

    Scaffold(
        bottomBar = {
            BottomAppBar(
                actions = {
                    // Add left padding to align with screen content
                    Spacer(modifier = Modifier.width(12.dp))

                    // Workflows Settings (first)
                    if (currentRoute == SettingsRoute.Workflows.route) {
                        FilledIconButton(
                            onClick = { },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                Icons.Filled.AccountTree,
                                contentDescription = stringResource(R.string.nav_workflows_settings)
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            navController.navigate(SettingsRoute.Workflows.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }) {
                            Icon(
                                Icons.Filled.AccountTree,
                                contentDescription = stringResource(R.string.nav_workflows_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Application Settings
                    if (currentRoute == SettingsRoute.Application.route) {
                        FilledIconButton(
                            onClick = { },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                Icons.Filled.PhoneAndroid,
                                contentDescription = stringResource(R.string.nav_application_settings)
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            navController.navigate(SettingsRoute.Application.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }) {
                            Icon(
                                Icons.Filled.PhoneAndroid,
                                contentDescription = stringResource(R.string.nav_application_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Server Settings
                    if (currentRoute == SettingsRoute.Server.route) {
                        FilledIconButton(
                            onClick = { },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                Icons.Filled.Storage,
                                contentDescription = stringResource(R.string.nav_server_settings)
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            navController.navigate(SettingsRoute.Server.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }) {
                            Icon(
                                Icons.Filled.Storage,
                                contentDescription = stringResource(R.string.nav_server_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Prompt Settings (stays lit on its sub-pages)
                    if (currentRoute?.startsWith("prompt_") == true) {
                        FilledIconButton(
                            onClick = { },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                Icons.Filled.Psychology,
                                contentDescription = "提示词优化"
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            navController.navigate(SettingsRoute.Prompt.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }) {
                            Icon(
                                Icons.Filled.Psychology,
                                contentDescription = "提示词优化",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // About Settings
                    if (currentRoute == SettingsRoute.About.route) {
                        FilledIconButton(
                            onClick = { },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = stringResource(R.string.nav_about_settings)
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            navController.navigate(SettingsRoute.About.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = stringResource(R.string.nav_about_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                floatingActionButton = {
                    // Back to generation FAB
                    FloatingActionButton(
                        onClick = onNavigateToGeneration,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = stringResource(R.string.menu_generation),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = SettingsRoute.Workflows.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            // Order matches BottomAppBar: Workflows → Application → Server → Prompt → About
            composable(SettingsRoute.Workflows.route) {
                WorkflowsSettingsScreen(
                    viewModel = workflowManagementViewModel,
                    onNavigateToGeneration = onNavigateToGeneration,
                    onLogout = onLogout
                )
            }

            composable(SettingsRoute.Application.route) {
                ApplicationSettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = onNavigateToGeneration,
                    onNavigateToGeneration = onNavigateToGeneration,
                    onLogout = onLogout
                )
            }

            composable(SettingsRoute.Server.route) {
                ServerSettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = onNavigateToGeneration,
                    onNavigateToGeneration = onNavigateToGeneration,
                    onLogout = onLogout
                )
            }

            composable(SettingsRoute.Prompt.route) {
                PromptSettingsScreen(
                    onOpenProviders = { navController.navigate(SettingsRoute.PromptProviders.route) },
                    onOpenTemplates = { navController.navigate(SettingsRoute.PromptTemplates.route) },
                    onNavigateToGeneration = onNavigateToGeneration,
                    onLogout = onLogout,
                    viewModel = promptViewModel
                )
            }

            composable(SettingsRoute.PromptProviders.route) {
                ApiProviderListScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { id -> navController.navigate(SettingsRoute.PromptProviderEdit.of(id)) },
                    viewModel = promptViewModel
                )
            }

            composable(
                route = SettingsRoute.PromptProviderEdit.route,
                arguments = listOf(navArgument("providerId") { type = NavType.StringType })
            ) { entry ->
                ApiProviderEditScreen(
                    providerId = entry.arguments?.getString("providerId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onDeleted = { navController.popBackStack() },
                    viewModel = promptViewModel
                )
            }

            composable(SettingsRoute.PromptTemplates.route) {
                TemplateLibraryScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { id ->
                        navController.navigate(SettingsRoute.PromptTemplateDetail.of(id))
                    }
                )
            }

            composable(
                route = SettingsRoute.PromptTemplateDetail.route,
                arguments = listOf(navArgument("templateId") { type = NavType.StringType })
            ) { entry ->
                TemplateDetailScreen(
                    templateId = entry.arguments?.getString("templateId").orEmpty(),
                    onBack = { navController.popBackStack() }
                )
            }

            composable(SettingsRoute.About.route) {
                AboutSettingsScreen(
                    onNavigateToGeneration = onNavigateToGeneration,
                    onLogout = onLogout
                )
            }
        }
    }
}

