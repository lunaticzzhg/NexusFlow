package com.nexusflow.backend.core.aicontext

class ModelContextCatalog(
    private val resolvers: List<ModelContextResolver>,
) {
    private val definitionsByKey: Map<ModelContextKey, ModelContextDefinition>
    private val resolverByKey: Map<ModelContextKey, ModelContextResolver>

    init {
        val registrations = resolvers.flatMap { resolver ->
            resolver.definitions.map { definition -> definition.key to Registration(definition, resolver) }
        }
        val duplicateKey = registrations
            .groupBy { it.first }
            .entries
            .firstOrNull { it.value.size > 1 }
            ?.key
        require(duplicateKey == null) { "Duplicate model context key registered: ${duplicateKey?.value}" }

        definitionsByKey = registrations.associate { it.first to it.second.definition }
        resolverByKey = registrations.associate { it.first to it.second.resolver }
    }

    fun definitions(allowance: ModelContextAllowance): List<ModelContextDefinition> =
        definitionsByKey.values
            .filter { it.isAllowedBy(allowance) }
            .sortedWith(definitionComparator)

    fun selectableDefinitions(allowance: ModelContextAllowance): List<SelectableModelContextDefinition> =
        definitions(allowance).map { it.toSelectable() }

    fun definition(key: ModelContextKey): ModelContextDefinition =
        definitionsByKey.getValue(key)

    fun validateSelectedKeys(
        keys: Collection<ModelContextKey>,
        allowance: ModelContextAllowance,
    ): List<ModelContextKey> {
        val duplicate = keys.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.key
        require(duplicate == null) { "Duplicate model context key selected: ${duplicate?.value}" }

        val unknown = keys.firstOrNull { it !in definitionsByKey }
        require(unknown == null) { "Unknown model context key selected: ${unknown?.value}" }

        val disallowed = keys.firstOrNull { !definitionsByKey.getValue(it).isAllowedBy(allowance) }
        require(disallowed == null) { "Model context key is not allowed for this request: ${disallowed?.value}" }

        return keys.toList()
    }

    suspend fun resolve(
        request: ModelContextResolveRequest,
        keys: Collection<ModelContextKey>,
    ): List<ResolvedModelContextBlock> {
        val selectedKeys = validateSelectedKeys(keys, request.allowance)
        if (selectedKeys.isEmpty()) {
            return emptyList()
        }

        val keysByResolver = selectedKeys.groupBy { resolverByKey.getValue(it) }
        return resolvers.flatMap { resolver ->
            val ownedKeys = keysByResolver[resolver]?.toSet() ?: return@flatMap emptyList()
            resolver.resolve(request, ownedKeys).also { blocks ->
                val invalidBlock = blocks.firstOrNull { it.key !in ownedKeys }
                require(invalidBlock == null) {
                    "Resolver returned an unrequested or unowned model context key: ${invalidBlock?.key?.value}"
                }
            }
        }
    }

    private data class Registration(
        val definition: ModelContextDefinition,
        val resolver: ModelContextResolver,
    )
}

private val definitionComparator =
    compareBy<ModelContextDefinition>({ it.priority.ordinal }, { it.key.value })
