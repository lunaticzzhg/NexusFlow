package com.nexusflow.backend.feature.profile.domain

import com.nexusflow.backend.feature.task.domain.ProfilePreferenceId
import com.nexusflow.backend.feature.task.domain.RequirementKind
import com.nexusflow.backend.feature.task.domain.RequirementValue
import com.nexusflow.backend.feature.task.domain.TaskOwner
import java.time.Instant

data class ExplicitPreference(
    val id: ProfilePreferenceId,
    val owner: TaskOwner,
    val kind: RequirementKind,
    val value: RequirementValue,
    val createdAt: Instant,
    val updatedAt: Instant,
)
