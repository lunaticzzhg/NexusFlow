package com.nexusflow.backend.feature.profile.domain

import com.nexusflow.backend.feature.task.domain.ProfilePreferenceId
import com.nexusflow.backend.feature.task.domain.TaskOwner

interface ExplicitPreferenceRepository {
    suspend fun listForOwner(owner: TaskOwner): List<ExplicitPreference>

    suspend fun findForOwner(
        owner: TaskOwner,
        preferenceId: ProfilePreferenceId,
    ): ExplicitPreference?
}

object EmptyExplicitPreferenceRepository : ExplicitPreferenceRepository {
    override suspend fun listForOwner(owner: TaskOwner): List<ExplicitPreference> = emptyList()

    override suspend fun findForOwner(
        owner: TaskOwner,
        preferenceId: ProfilePreferenceId,
    ): ExplicitPreference? = null
}
