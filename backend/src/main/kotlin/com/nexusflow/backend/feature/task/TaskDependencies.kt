package com.nexusflow.backend.feature.task

import com.nexusflow.ai.AiTaskCapabilities
import com.nexusflow.ai.AiTaskCapabilityConfig
import com.nexusflow.ai.AiTaskCapabilityProvider
import com.nexusflow.ai.createAiTaskCapabilities
import com.nexusflow.contracts.backendai.planning.PlanComposer
import com.nexusflow.contracts.backendai.planning.PlanExplainer
import com.nexusflow.contracts.backendai.understanding.UserMessageUnderstanding
import com.nexusflow.backend.core.aicontext.ModelContextAssembler
import com.nexusflow.backend.core.aicontext.ModelContextCatalog
import com.nexusflow.backend.core.config.AiProvider
import com.nexusflow.backend.core.config.BackendRuntimeConfig
import com.nexusflow.backend.feature.profile.application.ExplicitPreferenceModelContextResolver
import com.nexusflow.backend.feature.profile.domain.ExplicitPreferenceRepository
import com.nexusflow.backend.feature.profile.infrastructure.JdbcExplicitPreferenceRepository
import com.nexusflow.backend.feature.task.application.PlanningService
import com.nexusflow.backend.feature.task.application.TaskService
import com.nexusflow.backend.feature.task.application.TaskUnderstandingFailureEvent
import com.nexusflow.backend.feature.task.domain.ControlledOpportunityProvider
import com.nexusflow.backend.feature.task.domain.PlanValidator
import com.nexusflow.backend.feature.task.domain.OpportunityProvider
import com.nexusflow.backend.feature.task.domain.TaskRepository
import com.nexusflow.backend.feature.task.infrastructure.JdbcTaskRepository
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

fun Application.configureTaskDependencies() {
    dependencies {
        provide<TaskRepository> {
            JdbcTaskRepository(resolve<HikariDataSource>())
        }
        provide<ExplicitPreferenceRepository> {
            JdbcExplicitPreferenceRepository(resolve<HikariDataSource>())
        }
        provide<HttpClient> {
            val config = resolve<BackendRuntimeConfig>()
            HttpClient(CIO) {
                config.ai?.let { ai ->
                    install(HttpTimeout) {
                        requestTimeoutMillis = ai.requestTimeout.toMillis()
                    }
                }
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
        provide<AiTaskCapabilities?> {
            val config = resolve<BackendRuntimeConfig>()
            val ai = config.ai ?: return@provide null
            createAiTaskCapabilities(
                client = resolve<HttpClient>(),
                config = AiTaskCapabilityConfig(
                    provider = ai.provider.toAiCapabilityProvider(),
                    apiKey = ai.apiKey,
                    model = ai.model,
                    baseUrl = ai.baseUrl,
                    enableThinking = ai.enableThinking ?: false,
                ),
                logger = resolve<StructuredLogger>(),
            )
        }
        provide<UserMessageUnderstanding?> {
            resolve<AiTaskCapabilities?>()?.understanding
        }
        provide<ModelContextCatalog> {
            ModelContextCatalog(
                listOf(
                    ExplicitPreferenceModelContextResolver(resolve<ExplicitPreferenceRepository>()),
                ),
            )
        }
        provide {
            ModelContextAssembler(resolve<ModelContextCatalog>())
        }
        provide<PlanComposer?> {
            resolve<AiTaskCapabilities?>()?.planComposer
        }
        provide<PlanExplainer?> {
            resolve<AiTaskCapabilities?>()?.planExplainer
        }
        provide<OpportunityProvider> {
            ControlledOpportunityProvider()
        }
        provide {
            PlanValidator()
        }
        provide {
            PlanningService(
                repository = resolve(),
                opportunityProvider = resolve(),
                planValidator = resolve(),
                planComposer = resolve(),
                planExplainer = resolve(),
                modelContextAssembler = resolve(),
                logger = resolve<StructuredLogger>(),
            )
        }
        provide {
            val logger = resolve<StructuredLogger>()
            TaskService(
                repository = resolve(),
                planningService = resolve(),
                understanding = resolve(),
                modelContextCatalog = resolve(),
                modelContextAssembler = resolve(),
                logUnderstandingFailure = { event -> logger.logTaskUnderstandingFailure(event) },
                logger = logger,
            )
        }
    }
}

private fun AiProvider.toAiCapabilityProvider(): AiTaskCapabilityProvider =
    when (this) {
        AiProvider.OpenAi -> AiTaskCapabilityProvider.OpenAi
        AiProvider.Qwen -> AiTaskCapabilityProvider.Qwen
        AiProvider.DeepSeek -> AiTaskCapabilityProvider.DeepSeek
    }

internal fun StructuredLogger.logTaskUnderstandingFailure(event: TaskUnderstandingFailureEvent) {
    warn(
        component = "task",
        event = "task_understanding_failed",
        fields = logFields {
            "task_id" value event.taskId
            "task_revision" value event.taskRevision
            "ai_request_id" value event.aiRequestId
            "failure_type" value event.failureType
        },
    )
}
