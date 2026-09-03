package com.nexusflow.backend.core.aicontext

interface ModelContextResolver {
    val definitions: List<ModelContextDefinition>

    suspend fun resolve(
        request: ModelContextResolveRequest,
        keys: Set<ModelContextKey>,
    ): List<ResolvedModelContextBlock>
}
