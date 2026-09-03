package com.nexusflow.backend.core.aicontext

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class ModelContextAssembler(
    private val catalog: ModelContextCatalog,
    private val budgetPolicy: ModelContextBudgetPolicy = ModelContextBudgetPolicy(),
    private val json: Json = Json {
        explicitNulls = false
        encodeDefaults = false
    },
) {
    suspend fun assemble(
        request: ModelContextResolveRequest,
        keys: Collection<ModelContextKey>,
    ): AssembledModelContext {
        val requestedKeys = catalog.validateSelectedKeys(keys, request.allowance)
        val requestedOrder = requestedKeys.withIndex().associate { it.value to it.index }
        val resolvedBlocks = catalog.resolve(request, requestedKeys)
        val candidates = resolvedBlocks
            .filter { it.content.isNotEmpty() }
            .sortedWith(
                compareBy<ResolvedModelContextBlock>(
                    { it.priority.ordinal },
                    { requestedOrder.getValue(it.key) },
                    { it.key.value },
                ),
            )
            .distinctBy { it.key }
            .mapNotNull { it.toBudgetCandidateOrNull() }

        val budgeted = candidates.applyBudget()
        val includedKeys = budgeted.blocks.mapTo(mutableSetOf()) { ModelContextKey(it.key) }
        val unavailableOrOversizedKeys = requestedKeys.filterNot { key -> candidates.any { it.key == key } }
        val omittedKeys = (unavailableOrOversizedKeys + budgeted.omittedKeys)
            .distinct()
            .map { it.value }
        val optionalContextSerializedChars = serializedModelBlocksChars(budgeted.blocks)
        val omittedCount = requestedKeys.size - includedKeys.size

        return AssembledModelContext(
            optionalContext = budgeted.blocks,
            omittedBlockCount = omittedCount,
            omittedContextKeys = omittedKeys,
            diagnostics = ModelContextAssemblyDiagnostics(
                selectedContextKeyCount = requestedKeys.size,
                resolvedContextBlockCount = candidates.size,
                includedContextBlockCount = budgeted.blocks.size,
                omittedContextBlockCount = omittedCount,
                omittedContextKeys = omittedKeys,
                optionalContextSerializedChars = optionalContextSerializedChars,
            ),
        )
    }

    private fun ResolvedModelContextBlock.toBudgetCandidateOrNull(): BudgetCandidate? {
        val definition = catalog.definition(key)
        val contentChars = serializedContentChars(content)
        if (contentChars > definition.maxContentChars) {
            return null
        }
        val modelBlock = toModelContextBlock()
        return BudgetCandidate(
            key = key,
            block = modelBlock,
            serializedChars = serializedModelBlockChars(modelBlock),
        )
    }

    private fun List<BudgetCandidate>.applyBudget(): BudgetedBlocks {
        val blocks = mutableListOf<ModelContextBlock>()
        val blockChars = mutableListOf<Int>()
        val omittedKeys = mutableListOf<ModelContextKey>()
        forEach { candidate ->
            val candidateCount = blocks.size + 1
            val candidateSerializedChars = serializedListChars(blockChars + candidate.serializedChars)
            if (
                candidateCount <= budgetPolicy.maxOptionalContextBlocks &&
                candidateSerializedChars <= budgetPolicy.maxOptionalContextSerializedChars
            ) {
                blocks += candidate.block
                blockChars += candidate.serializedChars
            } else {
                omittedKeys += candidate.key
            }
        }
        return BudgetedBlocks(blocks = blocks, omittedKeys = omittedKeys)
    }

    private fun ResolvedModelContextBlock.toModelContextBlock(): ModelContextBlock =
        ModelContextBlock(
            key = key.value,
            trust = trust,
            content = content,
        )

    private fun serializedContentChars(content: JsonObject): Int =
        json.encodeToString(JsonObject.serializer(), content).length

    private fun serializedModelBlockChars(block: ModelContextBlock): Int =
        json.encodeToString(ModelContextBlock.serializer(), block).length

    private fun serializedModelBlocksChars(blocks: List<ModelContextBlock>): Int =
        json.encodeToString(ListSerializer(ModelContextBlock.serializer()), blocks).length

    private fun serializedListChars(serializedBlockChars: List<Int>): Int =
        if (serializedBlockChars.isEmpty()) {
            2
        } else {
            serializedBlockChars.sum() + 2 + serializedBlockChars.lastIndex
        }

    private data class BudgetCandidate(
        val key: ModelContextKey,
        val block: ModelContextBlock,
        val serializedChars: Int,
    )

    private data class BudgetedBlocks(
        val blocks: List<ModelContextBlock>,
        val omittedKeys: List<ModelContextKey>,
    )
}

data class ModelContextBudgetPolicy(
    val maxOptionalContextSerializedChars: Int = 12_000,
    val maxOptionalContextBlocks: Int = 16,
) {
    init {
        require(maxOptionalContextSerializedChars >= 2) {
            "maxOptionalContextSerializedChars must allow an empty JSON array"
        }
        require(maxOptionalContextBlocks >= 0) { "maxOptionalContextBlocks must be non-negative" }
    }
}

data class AssembledModelContext(
    val optionalContext: List<ModelContextBlock>,
    val omittedBlockCount: Int,
    val omittedContextKeys: List<String> = emptyList(),
    val diagnostics: ModelContextAssemblyDiagnostics = ModelContextAssemblyDiagnostics(),
)

data class ModelContextAssemblyDiagnostics(
    val selectedContextKeyCount: Int = 0,
    val resolvedContextBlockCount: Int = 0,
    val includedContextBlockCount: Int = 0,
    val omittedContextBlockCount: Int = 0,
    val omittedContextKeys: List<String> = emptyList(),
    val optionalContextSerializedChars: Int = 2,
)
