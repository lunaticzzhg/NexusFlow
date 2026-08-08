package com.nexusflow.app.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nexusflow.app.core.design.AppSpacing
import com.nexusflow.app.feature.task.presentation.create.TaskCreateRoute
import com.nexusflow.app.feature.task.presentation.detail.TaskDetailPlaceholderRoute
import com.nexusflow.app.feature.task.presentation.home.TaskHomeRoute
import nexusflow.app.composeapp.generated.resources.Res
import nexusflow.app.composeapp.generated.resources.app_preferences_placeholder_body
import nexusflow.app.composeapp.generated.resources.app_preferences_placeholder_title
import nexusflow.app.composeapp.generated.resources.app_tasks_placeholder_body
import nexusflow.app.composeapp.generated.resources.app_tasks_placeholder_title
import nexusflow.app.composeapp.generated.resources.auth_logout
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
@Suppress("FunctionName", "FunctionNaming", "ktlint:standard:function-naming")
fun AppShell(onLogout: () -> Unit) {
    val navController = rememberNavController()
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination
    val showsBottomBar =
        currentDestination.isCurrentDestination(AppHomeDestination) ||
            currentDestination.isCurrentDestination(AppTasksDestination) ||
            currentDestination.isCurrentDestination(AppPreferencesDestination)

    Scaffold(
        bottomBar = {
            if (showsBottomBar) {
                NavigationBar {
                    appDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentDestination.isCurrentDestination(destination),
                            onClick = {
                                navController.navigateTo(destination)
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(destination.icon),
                                    contentDescription = stringResource(destination.contentDescription),
                                )
                            },
                            label = {
                                Text(stringResource(destination.label))
                            },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppHomeDestination,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            composable<AppHomeDestination> {
                TaskHomeRoute(
                    onOpenCreate = { navController.navigate(TaskCreateDestination) },
                )
            }
            composable<AppTasksDestination> {
                appPlaceholder(
                    title = stringResource(Res.string.app_tasks_placeholder_title),
                    body = stringResource(Res.string.app_tasks_placeholder_body),
                )
            }
            composable<AppPreferencesDestination> {
                appPlaceholder(
                    title = stringResource(Res.string.app_preferences_placeholder_title),
                    body = stringResource(Res.string.app_preferences_placeholder_body),
                    action = {
                        Button(onClick = onLogout) {
                            Text(stringResource(Res.string.auth_logout))
                        }
                    },
                )
            }
            composable<TaskCreateDestination> {
                TaskCreateRoute(
                    onBackHome = { navController.navigateToTab(AppHomeDestination) },
                    onOpenTask = { taskId, title ->
                        navController.navigate(TaskDetailDestination(taskId, title)) {
                            popUpTo(TaskCreateDestination) {
                                inclusive = true
                            }
                        }
                    },
                )
            }
            composable<TaskDetailDestination> { entry ->
                TaskDetailPlaceholderRoute(
                    taskId = entry.arguments?.getString("taskId").orEmpty(),
                    title = entry.arguments?.getString("title").orEmpty(),
                    onBackHome = {
                        navController.navigateToTab(AppHomeDestination)
                    },
                )
            }
        }
    }
}

@Composable
private fun appPlaceholder(
    title: String,
    body: String,
    action: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(AppSpacing.page),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
        )
        action()
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun NavDestination?.isCurrentDestination(destination: AppDestination): Boolean =
    when (destination) {
        AppHomeDestination -> this?.hasRoute<AppHomeDestination>() == true
        AppTasksDestination -> this?.hasRoute<AppTasksDestination>() == true
        AppPreferencesDestination -> this?.hasRoute<AppPreferencesDestination>() == true
    }

private fun androidx.navigation.NavHostController.navigateTo(destination: AppDestination) {
    when (destination) {
        AppHomeDestination -> navigateToTab(AppHomeDestination)
        AppTasksDestination -> navigateToTab(AppTasksDestination)
        AppPreferencesDestination -> navigateToTab(AppPreferencesDestination)
    }
}

private inline fun <reified T : Any> androidx.navigation.NavHostController.navigateToTab(destination: T) {
    navigate(destination) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
