package com.nexusflow.app.app

import kotlinx.serialization.Serializable
import nexusflow.app.composeapp.generated.resources.Res
import nexusflow.app.composeapp.generated.resources.app_home_content_description
import nexusflow.app.composeapp.generated.resources.app_home_label
import nexusflow.app.composeapp.generated.resources.app_preferences_content_description
import nexusflow.app.composeapp.generated.resources.app_preferences_label
import nexusflow.app.composeapp.generated.resources.app_tasks_content_description
import nexusflow.app.composeapp.generated.resources.app_tasks_label
import nexusflow.app.composeapp.generated.resources.ic_app_home
import nexusflow.app.composeapp.generated.resources.ic_app_preferences
import nexusflow.app.composeapp.generated.resources.ic_app_tasks
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource

sealed interface AppDestination {
    val label: StringResource
    val contentDescription: StringResource
    val icon: DrawableResource
}

@Serializable
data object AppHomeDestination : AppDestination {
    override val label = Res.string.app_home_label
    override val contentDescription = Res.string.app_home_content_description
    override val icon = Res.drawable.ic_app_home
}

@Serializable
data object AppTasksDestination : AppDestination {
    override val label = Res.string.app_tasks_label
    override val contentDescription = Res.string.app_tasks_content_description
    override val icon = Res.drawable.ic_app_tasks
}

@Serializable
data object AppPreferencesDestination : AppDestination {
    override val label = Res.string.app_preferences_label
    override val contentDescription = Res.string.app_preferences_content_description
    override val icon = Res.drawable.ic_app_preferences
}

@Serializable
data object TaskCreateDestination

@Serializable
data class TaskDetailDestination(
    val taskId: String,
    val title: String,
)

val appDestinations: List<AppDestination> =
    listOf(
        AppHomeDestination,
        AppTasksDestination,
        AppPreferencesDestination,
    )
