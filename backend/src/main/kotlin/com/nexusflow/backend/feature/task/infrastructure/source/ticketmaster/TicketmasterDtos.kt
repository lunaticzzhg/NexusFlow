package com.nexusflow.backend.feature.task.infrastructure.source.ticketmaster

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class TicketmasterDiscoveryResponse(
    @SerialName("_embedded")
    val embedded: TicketmasterEmbeddedDto? = null,
)

@Serializable
internal data class TicketmasterEmbeddedDto(
    @SerialName("events")
    val events: List<TicketmasterEventDto> = emptyList(),
)

@Serializable
internal data class TicketmasterEventDto(
    @SerialName("id")
    val id: String? = null,
    @SerialName("name")
    val name: String? = null,
    @SerialName("url")
    val url: String? = null,
    @SerialName("dates")
    val dates: TicketmasterDatesDto? = null,
    @SerialName("_embedded")
    val embedded: TicketmasterEventEmbeddedDto? = null,
    @SerialName("classifications")
    val classifications: List<TicketmasterClassificationDto> = emptyList(),
)

@Serializable
internal data class TicketmasterDatesDto(
    @SerialName("start")
    val start: TicketmasterStartDateDto? = null,
    @SerialName("end")
    val end: TicketmasterEndDateDto? = null,
)

@Serializable
internal data class TicketmasterStartDateDto(
    @SerialName("dateTime")
    val dateTime: String? = null,
    @SerialName("localDate")
    val localDate: String? = null,
)

@Serializable
internal data class TicketmasterEndDateDto(
    @SerialName("dateTime")
    val dateTime: String? = null,
)

@Serializable
internal data class TicketmasterEventEmbeddedDto(
    @SerialName("venues")
    val venues: List<TicketmasterVenueDto> = emptyList(),
    @SerialName("attractions")
    val attractions: List<TicketmasterAttractionDto> = emptyList(),
)

@Serializable
internal data class TicketmasterVenueDto(
    @SerialName("name")
    val name: String? = null,
    @SerialName("city")
    val city: TicketmasterNamedValueDto? = null,
    @SerialName("country")
    val country: TicketmasterCountryDto? = null,
    @SerialName("location")
    val location: TicketmasterLocationDto? = null,
)

@Serializable
internal data class TicketmasterNamedValueDto(
    @SerialName("name")
    val name: String? = null,
)

@Serializable
internal data class TicketmasterCountryDto(
    @SerialName("countryCode")
    val countryCode: String? = null,
)

@Serializable
internal data class TicketmasterLocationDto(
    @SerialName("latitude")
    val latitude: String? = null,
    @SerialName("longitude")
    val longitude: String? = null,
)

@Serializable
internal data class TicketmasterAttractionDto(
    @SerialName("name")
    val name: String? = null,
)

@Serializable
internal data class TicketmasterClassificationDto(
    @SerialName("segment")
    val segment: TicketmasterNamedValueDto? = null,
    @SerialName("genre")
    val genre: TicketmasterNamedValueDto? = null,
    @SerialName("subGenre")
    val subGenre: TicketmasterNamedValueDto? = null,
)
