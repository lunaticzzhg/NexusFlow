package com.nexusflow.backend.feature.task

import com.nexusflow.ai.understanding.UserMessageUnderstanding
import com.nexusflow.ai.understanding.openai.OpenAiUserMessageUnderstanding
import com.nexusflow.backend.core.config.BackendRuntimeConfig
import com.nexusflow.backend.feature.task.application.TaskService
import com.nexusflow.backend.feature.task.domain.TaskRepository
import com.nexusflow.backend.feature.task.infrastructure.JdbcTaskRepository
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

fun Application.configureTaskDependencies() {
    val applicationLogger = environment.log
    dependencies {
        provide<TaskRepository> {
            JdbcTaskRepository(resolve<HikariDataSource>())
        }
        provide<HttpClient> {
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                        },
                    )
                }
            }
        }
        provide<UserMessageUnderstanding?> {
            val config = resolve<BackendRuntimeConfig>()
            if (config.openAiApiKey == null || config.openAiModel == null) {
                return@provide null
            }
            OpenAiUserMessageUnderstanding(
                client = resolve<HttpClient>(),
                apiKey = config.openAiApiKey,
                model = config.openAiModel,
            )
        }
        provide {
            val config = resolve<BackendRuntimeConfig>()
            TaskService(
                repository = resolve(),
                understanding = resolve(),
                fixturePlanningEnabled = config.fixturePlanningEnabled,
                logUnderstandingFailure = { event ->
                    applicationLogger.warn(
                        "Task understanding failed [taskId={}, taskVersion={}, aiRequestId={}, failureType={}]",
                        event.taskId,
                        event.taskVersion,
                        event.aiRequestId,
                        event.failureType,
                    )
                },
            )
        }
    }
}
