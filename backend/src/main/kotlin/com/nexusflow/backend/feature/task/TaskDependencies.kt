package com.nexusflow.backend.feature.task

import com.nexusflow.ai.planner.PlanComposer
import com.nexusflow.ai.planner.PlanExplainer
import com.nexusflow.ai.planner.StructuredPlanComposer
import com.nexusflow.ai.planner.StructuredPlanExplainer
import com.nexusflow.ai.provider.StructuredModelProvider
import com.nexusflow.ai.provider.deepseek.DeepSeekStructuredModelProvider
import com.nexusflow.ai.provider.openai.OpenAiStructuredModelProvider
import com.nexusflow.ai.provider.qwen.QwenStructuredModelProvider
import com.nexusflow.ai.understanding.StructuredUserMessageUnderstanding
import com.nexusflow.ai.understanding.UserMessageUnderstanding
import com.nexusflow.backend.core.aicontext.ModelContextAssembler
import com.nexusflow.backend.core.aicontext.ModelContextCatalog
import com.nexusflow.backend.core.config.AiProvider
import com.nexusflow.backend.core.config.BackendRuntimeConfig
import com.nexusflow.backend.feature.profile.application.ExplicitPreferenceModelContextResolver
import com.nexusflow.backend.feature.profile.domain.ExplicitPreferenceRepository
import com.nexusflow.backend.feature.profile.infrastructure.JdbcExplicitPreferenceRepository
import com.nexusflow.backend.feature.task.application.PlanningService
import com.nexusflow.backend.feature.task.application.TaskService
import com.nexusflow.backend.feature.task.domain.ControlledOpportunityProvider
import com.nexusflow.backend.feature.task.domain.PlanValidator
import com.nexusflow.backend.feature.task.domain.OpportunityProvider
import com.nexusflow.backend.feature.task.domain.TaskRepository
import com.nexusflow.backend.feature.task.infrastructure.JdbcTaskRepository
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
    val applicationLogger = environment.log
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
        provide<StructuredModelProvider?> {
            val config = resolve<BackendRuntimeConfig>()
            val ai = config.ai ?: return@provide null
            when (ai.provider) {
                AiProvider.OpenAi -> OpenAiStructuredModelProvider(
                    client = resolve<HttpClient>(),
                    apiKey = ai.apiKey,
                    model = ai.model,
                    baseUrl = ai.baseUrl,
                )
                AiProvider.Qwen -> QwenStructuredModelProvider(
                    client = resolve<HttpClient>(),
                    apiKey = ai.apiKey,
                    model = ai.model,
                    baseUrl = ai.baseUrl,
                )
                AiProvider.DeepSeek -> DeepSeekStructuredModelProvider(
                    client = resolve<HttpClient>(),
                    apiKey = ai.apiKey,
                    model = ai.model,
                    baseUrl = ai.baseUrl,
                )
            }
        }
        provide<UserMessageUnderstanding?> {
            val provider = resolve<StructuredModelProvider?>() ?: return@provide null
            StructuredUserMessageUnderstanding(provider)
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
            val provider = resolve<StructuredModelProvider?>() ?: return@provide null
            StructuredPlanComposer(provider)
        }
        provide<PlanExplainer?> {
            val provider = resolve<StructuredModelProvider?>() ?: return@provide null
            StructuredPlanExplainer(provider)
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
            )
        }
        provide {
            TaskService(
                repository = resolve(),
                planningService = resolve(),
                understanding = resolve(),
                modelContextCatalog = resolve(),
                modelContextAssembler = resolve(),
                logUnderstandingFailure = { event ->
                    applicationLogger.warn(
                        "Task understanding failed [taskId={}, taskRevision={}, aiRequestId={}, failureType={}]",
                        event.taskId,
                        event.taskRevision,
                        event.aiRequestId,
                        event.failureType,
                    )
                },
            )
        }
    }
}
