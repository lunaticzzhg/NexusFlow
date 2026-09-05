pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "NexusFlow"
include(":app:composeApp", ":contracts:app-backend", ":contracts:backend-ai", ":backend", ":ai", ":observability")
