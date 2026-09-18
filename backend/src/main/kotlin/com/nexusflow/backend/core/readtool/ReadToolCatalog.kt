package com.nexusflow.backend.core.readtool

class ReadToolCatalog(
    tools: List<ReadTool>,
) {
    private val toolsByKey: Map<ReadToolKey, ReadTool>

    init {
        val duplicateKey = tools
            .groupBy { tool -> tool.definition.key }
            .entries
            .firstOrNull { (_, registrations) -> registrations.size > 1 }
            ?.key
        require(duplicateKey == null) {
            "Duplicate read tool key registered: ${duplicateKey?.value}"
        }

        toolsByKey = tools.associateBy { tool -> tool.definition.key }
    }

    fun definitions(): List<ReadToolDefinition> =
        toolsByKey.values
            .map(ReadTool::definition)
            .sortedBy { definition -> definition.key.value }

    fun tool(key: ReadToolKey): ReadTool? = toolsByKey[key]
}
