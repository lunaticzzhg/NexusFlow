package com.nexusflow.backend.feature.profile.infrastructure

import com.nexusflow.backend.feature.profile.domain.ExplicitPreference
import com.nexusflow.backend.feature.profile.domain.ExplicitPreferenceRepository
import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.CommutePreferenceValue
import com.nexusflow.backend.feature.task.domain.ProfilePreferenceId
import com.nexusflow.backend.feature.task.domain.RequirementKind
import com.nexusflow.backend.feature.task.domain.RequirementValue
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcExplicitPreferenceRepository(
    private val dataSource: DataSource,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : ExplicitPreferenceRepository {
    override suspend fun listForOwner(owner: TaskOwner): List<ExplicitPreference> =
        blocking {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT id, tenant_id, owner_user_id, kind, value_json, created_at, updated_at
                    FROM explicit_preferences
                    WHERE tenant_id = ? AND owner_user_id = ?
                    ORDER BY created_at ASC, id ASC
                    """.trimIndent(),
                ).use { statement ->
                    statement.setOwner(owner)
                    statement.executeQuery().use { result ->
                        buildList {
                            while (result.next()) add(result.preference())
                        }
                    }
                }
            }
        }

    override suspend fun findForOwner(
        owner: TaskOwner,
        preferenceId: ProfilePreferenceId,
    ): ExplicitPreference? =
        blocking {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT id, tenant_id, owner_user_id, kind, value_json, created_at, updated_at
                    FROM explicit_preferences
                    WHERE tenant_id = ? AND owner_user_id = ? AND id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setOwner(owner)
                    statement.setObject(3, preferenceId.value)
                    statement.executeQuery().use { result -> if (result.next()) result.preference() else null }
                }
            }
        }

    private suspend fun <T> blocking(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun java.sql.PreparedStatement.setOwner(owner: TaskOwner) {
        setObject(1, owner.tenantId.value)
        setObject(2, owner.userId.value)
    }

    private fun ResultSet.preference(): ExplicitPreference =
        ExplicitPreference(
            id = ProfilePreferenceId(getObject("id", UUID::class.java)),
            owner = TaskOwner(TenantId(getObject("tenant_id", UUID::class.java)), UserId(getObject("owner_user_id", UUID::class.java))),
            kind = RequirementKind.valueOf(getString("kind")),
            value = json.decodeFromString<RequirementValueDocument>(getString("value_json")).toDomain(),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )

    @Serializable
    private sealed class RequirementValueDocument {
        abstract fun toDomain(): RequirementValue

        @Serializable
        @SerialName("time_window")
        data class TimeWindow(
            @SerialName("startAt")
            val startAt: String? = null,
            @SerialName("endAt")
            val endAt: String? = null,
            @SerialName("timeZoneId")
            val timeZoneId: String,
            @SerialName("originalText")
            val originalText: String,
        ) : RequirementValueDocument() {
            override fun toDomain(): RequirementValue =
                RequirementValue.TimeWindow(startAt?.let(Instant::parse), endAt?.let(Instant::parse), timeZoneId, originalText)
        }

        @Serializable
        @SerialName("budget_limit")
        data class BudgetLimit(
            @SerialName("wholeUnits")
            val wholeUnits: Long,
            @SerialName("currencyCode")
            val currencyCode: String? = null,
        ) : RequirementValueDocument() {
            override fun toDomain(): RequirementValue = RequirementValue.BudgetLimit(wholeUnits, currencyCode)
        }

        @Serializable
        @SerialName("commute_limit")
        data class CommuteLimit(
            @SerialName("maxMinutes")
            val maxMinutes: Int,
        ) : RequirementValueDocument() {
            override fun toDomain(): RequirementValue = RequirementValue.CommuteLimit(maxMinutes)
        }

        @Serializable
        @SerialName("commute_preference")
        data class CommutePreference(
            @SerialName("value")
            val value: CommutePreferenceValue,
        ) : RequirementValueDocument() {
            override fun toDomain(): RequirementValue = RequirementValue.CommutePreference(value)
        }

        @Serializable
        @SerialName("location")
        data class Location(
            @SerialName("text")
            val text: String,
        ) : RequirementValueDocument() {
            override fun toDomain(): RequirementValue = RequirementValue.Location(text)
        }

        @Serializable
        @SerialName("activity_domain")
        data class ActivityDomain(
            @SerialName("value")
            val value: String,
        ) : RequirementValueDocument() {
            override fun toDomain(): RequirementValue = RequirementValue.ActivityDomain(value)
        }

        @Serializable
        @SerialName("activity_mode")
        data class ActivityMode(
            @SerialName("value")
            val value: ActivityModeValue,
        ) : RequirementValueDocument() {
            override fun toDomain(): RequirementValue = RequirementValue.ActivityMode(value)
        }

        @Serializable
        @SerialName("topic")
        data class Topic(
            @SerialName("text")
            val text: String,
        ) : RequirementValueDocument() {
            override fun toDomain(): RequirementValue = RequirementValue.Topic(text)
        }

        @Serializable
        @SerialName("experience_preference")
        data class ExperiencePreference(
            @SerialName("text")
            val text: String,
        ) : RequirementValueDocument() {
            override fun toDomain(): RequirementValue = RequirementValue.ExperiencePreference(text)
        }
    }
}
