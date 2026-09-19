package com.nexusflow.backend.feature.research.infrastructure.source.web

internal fun String?.toBoundedWebTitle(maxChars: Int = 200): String? =
    toBoundedWebText(maxChars = maxChars)

internal fun String?.toBoundedWebSnippet(maxChars: Int = 700): String? =
    toBoundedWebText(maxChars = maxChars)

internal fun String?.toBoundedExtractedText(maxChars: Int = 4_000): String? =
    toBoundedWebText(maxChars = maxChars)

private fun String?.toBoundedWebText(maxChars: Int): String? {
    val cleaned = this
        ?.replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        ?.replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        ?.replace(Regex("<[^>]+>"), " ")
        ?.replace(Regex("```[\\s\\S]*?```"), " ")
        ?.replace(Regex("[\\p{Cntrl}&&[^\\r\\n\\t]]+"), " ")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(maxChars)
        ?.trim()
    return cleaned?.takeIf(String::isNotBlank)
}
