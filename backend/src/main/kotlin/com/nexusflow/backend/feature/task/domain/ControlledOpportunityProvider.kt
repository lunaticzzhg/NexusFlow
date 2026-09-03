package com.nexusflow.backend.feature.task.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

class ControlledOpportunityProvider : OpportunityProvider {
    override fun discover(request: OpportunityRequest): List<Opportunity> {
        val requirements = request.requirements
        val domainFilters = requirements.mustValues<RequirementValue.ActivityDomain>()
            .map { it.value.normalizedPlanningToken() }
            .toSet()
        val topicFilters = requirements.mustValues<RequirementValue.Topic>()
            .map { it.text.normalizedPlanningToken() }
            .toSet()
        val modeFilters = requirements.mustValues<RequirementValue.ActivityMode>()
            .map { it.value }
            .toSet()

        return controlledOpportunities(request.referenceTime).filter { opportunity ->
            opportunity.matchesDomain(domainFilters) &&
                opportunity.matchesTopics(topicFilters) &&
                opportunity.matchesActivityMode(modeFilters)
        }
    }

    private fun controlledOpportunities(referenceTime: Instant): List<Opportunity> =
        listOf(
            sportsOpportunity(
                id = "10000000-0000-0000-0000-000000000101",
                title = "Liverpool supporters pub screening",
                summary = "Reserved table for a Liverpool match screening with crowd atmosphere.",
                location = LocationFact("Futian Sports Bar", "futian sports bar"),
                startsAfterHours = 28,
                durationHours = 3,
                cost = 180,
                commuteMinutes = 18,
                sourceUri = "controlled://sports/liverpool/pub-screening",
                validForHours = 32,
                topics = setOf("sports", "football", "soccer", "liverpool", "premier league"),
                locations = setOf("futian", "sports bar"),
                referenceTime = referenceTime,
            ),
            sportsOpportunity(
                id = "10000000-0000-0000-0000-000000000102",
                title = "Liverpool fan zone watch party",
                summary = "Outdoor-style fan zone with large screen and pre-match food stalls.",
                location = LocationFact("Nanshan Fan Zone", "nanshan fan zone"),
                startsAfterHours = 30,
                durationHours = 4,
                cost = 120,
                commuteMinutes = 28,
                sourceUri = "controlled://sports/liverpool/fan-zone",
                validForHours = 30,
                topics = setOf("sports", "football", "liverpool", "watch party"),
                locations = setOf("nanshan", "fan zone"),
                referenceTime = referenceTime,
            ),
            sportsOpportunity(
                id = "10000000-0000-0000-0000-000000000103",
                title = "Liverpool quiet lounge screening",
                summary = "Smaller lounge screening for a calmer match night.",
                location = LocationFact("Shekou Lounge", "shekou lounge"),
                startsAfterHours = 52,
                durationHours = 3,
                cost = 260,
                commuteMinutes = 35,
                sourceUri = "controlled://sports/liverpool/lounge",
                validForHours = 42,
                topics = setOf("sports", "football", "soccer", "liverpool", "screening"),
                locations = setOf("shekou", "lounge"),
                referenceTime = referenceTime,
            ),
            movieOpportunity(
                id = "20000000-0000-0000-0000-000000000201",
                title = "Nearby evening movie",
                summary = "Mainstream evening screening in a central mall cinema.",
                location = LocationFact("Coco Park Cinema", "coco park cinema"),
                startsAfterHours = 26,
                durationHours = 2,
                cost = 90,
                commuteMinutes = 12,
                sourceUri = "controlled://movies/coco-park/evening",
                validForHours = 18,
                topics = setOf("movies", "movie", "cinema", "film"),
                locations = setOf("futian", "coco park", "cinema"),
                referenceTime = referenceTime,
            ),
            movieOpportunity(
                id = "20000000-0000-0000-0000-000000000202",
                title = "Indie cinema night",
                summary = "Smaller cinema with a less familiar weekend release.",
                location = LocationFact("OCT Loft Cinema", "oct loft cinema"),
                startsAfterHours = 29,
                durationHours = 2,
                cost = 110,
                commuteMinutes = 22,
                sourceUri = "controlled://movies/oct-loft/indie-night",
                validForHours = 20,
                topics = setOf("movies", "movie", "cinema", "film", "indie"),
                locations = setOf("nanshan", "oct loft", "cinema"),
                referenceTime = referenceTime,
            ),
            movieOpportunity(
                id = "20000000-0000-0000-0000-000000000203",
                title = "Premium late movie",
                summary = "Late premium-format screening with reserved seats.",
                location = LocationFact("MixC Cinema", "mixc cinema"),
                startsAfterHours = 50,
                durationHours = 2,
                cost = 160,
                commuteMinutes = 26,
                sourceUri = "controlled://movies/mixc/premium-late",
                validForHours = 24,
                topics = setOf("movies", "movie", "cinema", "film", "premium"),
                locations = setOf("luohu", "mixc", "cinema"),
                referenceTime = referenceTime,
            ),
        )

    private fun sportsOpportunity(
        id: String,
        title: String,
        summary: String,
        location: LocationFact,
        startsAfterHours: Long,
        durationHours: Long,
        cost: Long,
        commuteMinutes: Int,
        sourceUri: String,
        validForHours: Long,
        topics: Set<String>,
        locations: Set<String>,
        referenceTime: Instant,
    ): Opportunity =
        opportunity(
            id = id,
            provider = "Controlled Sports Feed",
            kind = OpportunityKind.Sports,
            title = title,
            summary = summary,
            location = location,
            startsAfterHours = startsAfterHours,
            durationHours = durationHours,
            cost = cost,
            commuteMinutes = commuteMinutes,
            sourceUri = sourceUri,
            validForHours = validForHours,
            topics = topics,
            locations = locations,
            referenceTime = referenceTime,
        )

    private fun movieOpportunity(
        id: String,
        title: String,
        summary: String,
        location: LocationFact,
        startsAfterHours: Long,
        durationHours: Long,
        cost: Long,
        commuteMinutes: Int,
        sourceUri: String,
        validForHours: Long,
        topics: Set<String>,
        locations: Set<String>,
        referenceTime: Instant,
    ): Opportunity =
        opportunity(
            id = id,
            provider = "Controlled Movie Feed",
            kind = OpportunityKind.Movies,
            title = title,
            summary = summary,
            location = location,
            startsAfterHours = startsAfterHours,
            durationHours = durationHours,
            cost = cost,
            commuteMinutes = commuteMinutes,
            sourceUri = sourceUri,
            validForHours = validForHours,
            topics = topics,
            locations = locations,
            referenceTime = referenceTime,
        )

    private fun opportunity(
        id: String,
        provider: String,
        kind: OpportunityKind,
        title: String,
        summary: String,
        location: LocationFact,
        startsAfterHours: Long,
        durationHours: Long,
        cost: Long,
        commuteMinutes: Int,
        sourceUri: String,
        validForHours: Long,
        topics: Set<String>,
        locations: Set<String>,
        referenceTime: Instant,
    ): Opportunity {
        val startsAt = referenceTime.plus(Duration.ofHours(startsAfterHours))
        return Opportunity(
            id = OpportunityId(UUID.fromString(id)),
            provider = provider,
            externalKey = sourceUri,
            kind = kind,
            title = title,
            facts = OpportunityFacts(
                summary = summary,
                startTime = startsAt,
                endTime = startsAt.plus(Duration.ofHours(durationHours)),
                location = location,
                activityMode = ActivityModeValue.OutOfHome,
                price = MoneyFact(cost, "CNY"),
                commute = DurationFact(commuteMinutes),
                availability = AvailabilityFact.Available,
                attributes = mapOf(
                    "topics" to FactValue.Text(topics.joinToString(",")),
                    "locations" to FactValue.Text(locations.joinToString(",")),
                ),
            ),
            sources = listOf(SourceRef(provider, sourceUri, referenceTime.minus(Duration.ofHours(2)))),
            observedAt = referenceTime,
            validUntil = referenceTime.plus(Duration.ofHours(validForHours)),
        )
    }

    private fun Opportunity.matchesDomain(filters: Set<String>): Boolean =
        filters.isEmpty() || filters.any { kind.matchesDomainText(it) }

    private fun Opportunity.matchesTopics(filters: Set<String>): Boolean {
        val topics = facts.attributes["topics"].textValues()
        return filters.isEmpty() || filters.all { it in topics }
    }

    private fun Opportunity.matchesActivityMode(filters: Set<ActivityModeValue>): Boolean =
        filters.isEmpty() || facts.activityMode in filters

    private fun FactValue?.textValues(): Set<String> =
        (this as? FactValue.Text)
            ?.value
            ?.split(",")
            ?.mapTo(mutableSetOf()) { it.normalizedPlanningToken() }
            ?: emptySet()

    private inline fun <reified T : RequirementValue> List<Requirement>.mustValues(): List<T> =
        filter { it.strength == RequirementStrength.Must }
            .mapNotNull { it.value as? T }
}
