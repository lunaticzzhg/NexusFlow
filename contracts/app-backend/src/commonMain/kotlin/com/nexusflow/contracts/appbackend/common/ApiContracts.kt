package com.nexusflow.contracts.appbackend.common

import kotlinx.serialization.Serializable

/** The path prefix used by every externally consumable HTTP endpoint. */
object ApiVersion {
    const val V1 = "v1"
}

/** Standard, versioned response body returned by all App to Backend JSON API services. */
@Serializable
data class KResponse<T>(
    val code: Int,
    val message: String? = null,
    val data: T? = null,
)
