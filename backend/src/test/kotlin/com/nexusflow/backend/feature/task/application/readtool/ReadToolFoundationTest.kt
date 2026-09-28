package com.nexusflow.backend.feature.research.application.readtool

import com.nexusflow.backend.core.external.ExternalSourceUnavailableException
import com.nexusflow.backend.feature.research.application.ReadTool
import com.nexusflow.backend.feature.research.application.ReadToolCall
import com.nexusflow.backend.feature.research.application.ReadToolCatalog
import com.nexusflow.backend.feature.research.application.ReadToolDefinition
import com.nexusflow.backend.feature.research.application.ReadToolExecutionContext
import com.nexusflow.backend.feature.research.application.ReadToolExecutor
import com.nexusflow.backend.feature.research.application.ReadToolFactKind
import com.nexusflow.backend.feature.research.application.ReadToolFactValue
import com.nexusflow.backend.feature.research.application.ReadToolKey
import com.nexusflow.backend.feature.research.application.ReadToolOutcome
import com.nexusflow.backend.feature.research.application.source.FootballFixtureAcquirer
import com.nexusflow.backend.feature.research.application.source.GeneralSportsEventAcquirer
import com.nexusflow.backend.feature.research.application.source.LiveMusicEventAcquirer
import com.nexusflow.backend.feature.research.application.source.MovieDiscoveryAcquirer
import com.nexusflow.backend.feature.research.application.source.MovieMetadataAcquirer
import com.nexusflow.backend.feature.research.application.source.MovieShowtimeAcquirer
import com.nexusflow.backend.feature.research.application.source.OutdoorAcquirer
import com.nexusflow.backend.feature.task.domain.AvailabilityFact
import com.nexusflow.backend.feature.task.domain.MoneyFact
import com.nexusflow.backend.feature.task.domain.OpportunityFactKey
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.SourceRef
import com.nexusflow.backend.feature.task.domain.source.FixtureStatus
import com.nexusflow.backend.feature.task.domain.source.FootballFixtureCandidate
import com.nexusflow.backend.feature.task.domain.source.FootballFixtureQuery
import com.nexusflow.backend.feature.task.domain.source.FootballFixtureSource
import com.nexusflow.backend.feature.task.domain.source.GeneralSportsEventCandidate
import com.nexusflow.backend.feature.task.domain.source.GeneralSportsEventQuery
import com.nexusflow.backend.feature.task.domain.source.GeneralSportsEventSource
import com.nexusflow.backend.feature.task.domain.source.GeoPoint
import com.nexusflow.backend.feature.task.domain.source.LiveMusicEventCandidate
import com.nexusflow.backend.feature.task.domain.source.LiveMusicEventQuery
import com.nexusflow.backend.feature.task.domain.source.LiveMusicEventSource
import com.nexusflow.backend.feature.task.domain.source.MovieDiscoveryCandidate
import com.nexusflow.backend.feature.task.domain.source.MovieDiscoveryMode
import com.nexusflow.backend.feature.task.domain.source.MovieDiscoveryQuery
import com.nexusflow.backend.feature.task.domain.source.MovieDiscoverySource
import com.nexusflow.backend.feature.task.domain.source.MovieMetadataCandidate
import com.nexusflow.backend.feature.task.domain.source.MovieMetadataQuery
import com.nexusflow.backend.feature.task.domain.source.MovieMetadataSource
import com.nexusflow.backend.feature.task.domain.source.MovieShowtimeCandidate
import com.nexusflow.backend.feature.task.domain.source.MovieShowtimeQuery
import com.nexusflow.backend.feature.task.domain.source.MovieShowtimeSource
import com.nexusflow.backend.feature.task.domain.source.MusicMetadataCandidate
import com.nexusflow.backend.feature.task.domain.source.MusicMetadataQuery
import com.nexusflow.backend.feature.task.domain.source.MusicMetadataSearchType
import com.nexusflow.backend.feature.task.domain.source.MusicMetadataSource
import com.nexusflow.backend.feature.task.domain.source.PlaceCandidate
import com.nexusflow.backend.feature.task.domain.source.PlaceLookupQuery
import com.nexusflow.backend.feature.task.domain.source.PlaceLookupSource
import com.nexusflow.backend.feature.task.domain.source.RouteFact
import com.nexusflow.backend.feature.task.domain.source.RouteQuery
import com.nexusflow.backend.feature.task.domain.source.RouteSource
import com.nexusflow.backend.feature.task.domain.source.StaticFootballCompetitionRegistry
import com.nexusflow.backend.feature.task.domain.source.TrailCandidate
import com.nexusflow.backend.feature.task.domain.source.TrailDiscoveryQuery
import com.nexusflow.backend.feature.task.domain.source.TrailDiscoverySource
import com.nexusflow.backend.feature.task.domain.source.WeatherFact
import com.nexusflow.backend.feature.task.domain.source.WeatherQuery
import com.nexusflow.backend.feature.task.domain.source.WeatherSource
import com.nexusflow.backend.feature.task.domain.source.WebDiscoverySource
import com.nexusflow.backend.feature.task.domain.source.WebExtractedPage
import com.nexusflow.backend.feature.task.domain.source.WebSearchHit
import com.nexusflow.backend.feature.task.domain.source.WebSearchQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadToolFoundationTest {
    private val now = Instant.parse("2026-09-06T00:00:00Z")
    private val context = ReadToolExecutionContext(referenceTime = now, timeZoneId = "Asia/Shanghai")

    @Test
    fun `catalog rejects duplicate keys`() {
        assertFailsWith<IllegalArgumentException> {
            ReadToolCatalog(
                listOf(
                    FakeReadTool(WebSearchKey),
                    FakeReadTool(WebSearchKey),
                ),
            )
        }
    }

    @Test
    fun `executor rejects unknown key`() =
        runBlocking {
            val executor = ReadToolExecutor(ReadToolCatalog(emptyList()))

            assertFailsWith<IllegalArgumentException> {
                executor.execute(listOf(ReadToolCall(ReadToolKey("unknown.tool"), emptyArgs())), context)
            }
            Unit
        }

    @Test
    fun `executor allows same key with different arguments but rejects exact duplicate calls`() =
        runBlocking {
            val executor = ReadToolExecutor(ReadToolCatalog(listOf(FakeReadTool(WebSearchKey))))

            val executions = executor.execute(
                listOf(
                    ReadToolCall(WebSearchKey, buildJsonObject { put("query", "one") }),
                    ReadToolCall(WebSearchKey, buildJsonObject { put("query", "two") }),
                ),
                context,
            )

            assertEquals(2, executions.size)
            assertEquals("one", executions.first().call.arguments["query"]?.toString()?.trim('"'))
            assertFailsWith<IllegalArgumentException> {
                executor.execute(
                    listOf(
                        ReadToolCall(WebSearchKey, buildJsonObject { put("query", "same") }),
                        ReadToolCall(WebSearchKey, buildJsonObject { put("query", "same") }),
                    ),
                    context,
                )
            }
            Unit
        }


    @Test
    fun `executor bounds concurrent calls and preserves result order`() =
        runBlocking {
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val first = GatedReadTool(ReadToolKey("test.first")) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
            val second = GatedReadTool(ReadToolKey("test.second")) {
                secondEntered.complete(Unit)
            }
            val executor = ReadToolExecutor(
                catalog = ReadToolCatalog(listOf(first, second)),
                maxCallsPerTurn = 2,
                maxConcurrentCalls = 1,
            )

            val deferred = async {
                executor.execute(
                    listOf(
                        ReadToolCall(first.definition.key, emptyArgs()),
                        ReadToolCall(second.definition.key, emptyArgs()),
                    ),
                    context,
                )
            }
            withTimeout(1_000) { firstEntered.await() }
            assertEquals(false, secondEntered.isCompleted)
            releaseFirst.complete(Unit)

            val executions = withTimeout(1_000) { deferred.await() }

            withTimeout(1_000) { secondEntered.await() }
            assertEquals(listOf(first.definition.key, second.definition.key), executions.map { it.call.key })
        }

    @Test
    fun `executor allows two overlapping calls and queues the third when limit is two`() =
        runBlocking {
            val firstEntered = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val thirdEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val releaseSecond = CompletableDeferred<Unit>()
            val first = GatedReadTool(ReadToolKey("test.limit2.first")) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
            val second = GatedReadTool(ReadToolKey("test.limit2.second")) {
                secondEntered.complete(Unit)
                releaseSecond.await()
            }
            val third = GatedReadTool(ReadToolKey("test.limit2.third")) {
                thirdEntered.complete(Unit)
            }
            val executor = ReadToolExecutor(
                catalog = ReadToolCatalog(listOf(first, second, third)),
                maxCallsPerTurn = 3,
                maxConcurrentCalls = 2,
            )

            val deferred = async {
                executor.execute(
                    listOf(
                        ReadToolCall(first.definition.key, emptyArgs()),
                        ReadToolCall(second.definition.key, emptyArgs()),
                        ReadToolCall(third.definition.key, emptyArgs()),
                    ),
                    context,
                )
            }

            withTimeout(1_000) { firstEntered.await() }
            withTimeout(1_000) { secondEntered.await() }
            assertEquals(false, thirdEntered.isCompleted)

            releaseFirst.complete(Unit)
            withTimeout(1_000) { thirdEntered.await() }
            assertEquals(false, deferred.isCompleted)

            releaseSecond.complete(Unit)
            val executions = withTimeout(1_000) { deferred.await() }

            assertEquals(
                listOf(first.definition.key, second.definition.key, third.definition.key),
                executions.map { it.call.key },
            )
        }

    @Test
    fun `executor rejects over max calls`() =
        runBlocking {
            val executor = ReadToolExecutor(
                catalog = ReadToolCatalog(
                    listOf(
                        FakeReadTool(ReadToolKey("one")),
                        FakeReadTool(ReadToolKey("two")),
                        FakeReadTool(ReadToolKey("three")),
                    ),
                ),
                maxCallsPerTurn = 2,
            )

            assertFailsWith<IllegalArgumentException> {
                executor.execute(
                    listOf(
                        ReadToolCall(ReadToolKey("one"), emptyArgs()),
                        ReadToolCall(ReadToolKey("two"), emptyArgs()),
                        ReadToolCall(ReadToolKey("three"), emptyArgs()),
                    ),
                    context,
                )
            }
            Unit
        }

    @Test
    fun `weather forecast missing coords returns MissingInput`() =
        runBlocking {
            val tool = WeatherForecastReadTool(primary = StaticWeatherSource(now), secondary = null)

            val outcome = tool.execute(buildJsonObject { put("latitude", 22.236) }, context)

            assertIs<ReadToolOutcome.MissingInput>(outcome)
            Unit
        }

    @Test
    fun `weather forecast source success returns weather forecast evidence`() =
        runBlocking {
            val source = StaticWeatherSource(now)
            val tool = WeatherForecastReadTool(primary = source, secondary = null)

            val outcome = tool.execute(
                buildJsonObject {
                    put("latitude", 22.236)
                    put("longitude", 114.242)
                    put("dateFrom", "2026-09-06")
                    put("dateTo", "2026-09-07")
                },
                context,
            )

            val success = assertIs<ReadToolOutcome.Success>(outcome)
            assertEquals(WeatherForecastKey.value, success.payload.evidence.single().sourceKey)
            assertEquals(
                WeatherQuery(
                    point = GeoPoint(22.236, 114.242),
                    dateFrom = LocalDate.of(2026, 9, 6),
                    dateTo = LocalDate.of(2026, 9, 7),
                ),
                source.queries.single(),
            )
            assertEquals(27L, success.integerFact(ReadToolFactKind.TEMPERATURE_CELSIUS))
        }

    @Test
    fun `weather forecast uses MET primary before OpenMeteo secondary`() =
        runBlocking {
            val metNo = StaticWeatherSource(now, label = "MET Norway", sourceId = "met-no")
            val openMeteo = StaticWeatherSource(now, label = "Open-Meteo", sourceId = "open-meteo")
            val tool = WeatherForecastReadTool(primary = metNo, secondary = openMeteo)

            val outcome = tool.execute(
                buildJsonObject {
                    put("latitude", 22.236)
                    put("longitude", 114.242)
                },
                context,
            )

            val success = assertIs<ReadToolOutcome.Success>(outcome)
            assertEquals(WeatherForecastKey.value, success.payload.evidence.single().sourceKey)
            assertTrue(success.payload.evidence.single().sourceId.contains("met-no"))
            assertEquals(1, metNo.queries.size)
            assertEquals(emptyList(), openMeteo.queries)
            Unit
        }

    @Test
    fun `movie details missing title returns MissingInput`() =
        runBlocking {
            val tool = MovieDetailsReadTool(
                MovieMetadataAcquirer(
                    primary = StaticMovieMetadataSource(now),
                    secondary = null,
                ),
            )

            val outcome = tool.execute(emptyArgs(), context)

            assertIs<ReadToolOutcome.MissingInput>(outcome)
            Unit
        }

    @Test
    fun `movie details empty source result returns Empty`() =
        runBlocking {
            val tool = MovieDetailsReadTool(
                MovieMetadataAcquirer(
                    primary = EmptyMovieMetadataSource(),
                    secondary = null,
                ),
            )

            val outcome = tool.execute(buildJsonObject { put("title", "Unknown Movie") }, context)

            assertIs<ReadToolOutcome.Empty>(outcome)
            Unit
        }

    @Test
    fun `movie discovery uses vertical discovery source and does not call metadata or web when TMDB has data`() =
        runBlocking {
            val metadata = RecordingMovieMetadataSource()
            val discovery = RecordingMovieDiscoverySource(now)
            val web = RecordingWebDiscoverySource(
                hits = listOf(
                    WebSearchHit(
                        title = "Now playing movies",
                        url = "https://movies.example/now-playing",
                        content = "Current release list from an external discovery source.",
                        score = 0.9,
                    ),
                ),
            )
            val catalog = ReadToolCatalog(
                listOf(
                    MovieDetailsReadTool(MovieMetadataAcquirer(primary = metadata, secondary = null)),
                    MovieDiscoveryReadTool(MovieDiscoveryAcquirer(primary = discovery, webDiscoverySource = web)),
                ),
            )
            val executor = ReadToolExecutor(catalog)

            val executions = executor.execute(listOf(ReadToolCall(MovieDiscoveryKey, emptyArgs())), context)

            val success = assertIs<ReadToolOutcome.Success>(executions.single().outcome)
            assertEquals(MovieDiscoveryKey.value, success.payload.evidence.single().sourceKey)
            assertEquals(emptyList(), metadata.queries)
            assertEquals(listOf(MovieDiscoveryMode.NowPlaying), discovery.queries.map { it.mode })
            assertEquals(6, discovery.queries.single().maxResults)
            assertEquals(emptyList(), web.searchRequests)
            assertEquals("TMDB Now Playing", success.textFact(ReadToolFactKind.TITLE))
        }

    @Test
    fun `movie discovery falls back to web evidence without pretending it is TMDB metadata`() =
        runBlocking {
            val web = RecordingWebDiscoverySource(
                hits = listOf(
                    WebSearchHit(
                        title = "Current releases",
                        url = "https://movies.example/releases",
                        content = "External discovery snippet.",
                        score = 0.8,
                    ),
                ),
            )
            val tool = MovieDiscoveryReadTool(
                MovieDiscoveryAcquirer(primary = EmptyMovieDiscoverySource(), webDiscoverySource = web),
            )

            val outcome = tool.execute(buildJsonObject { put("mode", "trending") }, context)

            val success = assertIs<ReadToolOutcome.Success>(outcome)
            assertEquals(MovieDiscoveryKey.value, success.payload.evidence.single().sourceKey)
            assertTrue(success.payload.evidence.single().sourceId.startsWith("movie-discovery-web-"))
            assertTrue(web.searchRequests.single().query.contains("trending movies"))
        }

    @Test
    fun `web-backed read tools accept lowercase maxresults from model proposals`() =
        runBlocking {
            val web = RecordingWebDiscoverySource(
                hits = listOf(
                    WebSearchHit(
                        title = "Premier League fixtures",
                        url = "https://sports.example/fixtures",
                        content = "Fixture list from an external discovery source.",
                        score = 0.9,
                    ),
                ),
            )
            val tool = WebSearchReadTool(web)

            val outcome = tool.execute(
                buildJsonObject {
                    put("query", "Premier League fixtures")
                    put("maxresults", 3)
                },
                context,
            )

            assertIs<ReadToolOutcome.Success>(outcome)
            assertEquals(3, web.searchRequests.single().maxResults)
        }

    @Test
    fun `movie showtimes missing city returns MissingInput`() =
        runBlocking {
            val tool = MovieShowtimeReadTool(
                MovieShowtimeAcquirer(
                    metadataAcquirer = null,
                    primary = StaticMovieShowtimeSource(now),
                    webDiscoverySource = null,
                ),
            )

            val outcome = tool.execute(buildJsonObject { put("title", "哪吒2") }, context)

            assertIs<ReadToolOutcome.MissingInput>(outcome)
            Unit
        }

    @Test
    fun `movie showtimes official source success returns evidence`() =
        runBlocking {
            val source = StaticMovieShowtimeSource(now)
            val tool = MovieShowtimeReadTool(
                MovieShowtimeAcquirer(
                    metadataAcquirer = null,
                    primary = source,
                    webDiscoverySource = null,
                ),
            )

            val outcome = tool.execute(
                buildJsonObject {
                    put("title", "哪吒2")
                    put("city", "深圳")
                    put("countryCode", "CN")
                    put("dateFrom", "2026-09-06")
                    put("dateTo", "2026-09-07")
                },
                context,
            )

            val success = assertIs<ReadToolOutcome.Success>(outcome)
            assertEquals(MovieShowtimesKey.value, success.payload.evidence.single().sourceKey)
            assertEquals("深圳", source.queries.single().city)
            assertEquals("哪吒2 at 深圳影城", success.textFact(ReadToolFactKind.TITLE))
        }

    @Test
    fun `movie showtimes empty official result returns Empty`() =
        runBlocking {
            val tool = MovieShowtimeReadTool(
                MovieShowtimeAcquirer(
                    metadataAcquirer = null,
                    primary = EmptyMovieShowtimeSource(),
                    webDiscoverySource = null,
                ),
            )

            val outcome = tool.execute(buildJsonObject { put("city", "深圳") }, context)

            assertIs<ReadToolOutcome.Empty>(outcome)
            Unit
        }

    @Test
    fun `movie showtimes technical failure returns Unavailable`() =
        runBlocking {
            val tool = MovieShowtimeReadTool(
                MovieShowtimeAcquirer(
                    metadataAcquirer = null,
                    primary = FailingMovieShowtimeSource(),
                    webDiscoverySource = null,
                ),
            )

            val outcome = tool.execute(buildJsonObject { put("city", "深圳") }, context)

            assertIs<ReadToolOutcome.Unavailable>(outcome)
            Unit
        }

    @Test
    fun `football competition registry resolves canonical aliases`() {
        val registry = StaticFootballCompetitionRegistry()

        assertEquals("premier_league", registry.resolve("英超")?.slug)
        assertEquals("premier_league", registry.resolve("EPL")?.slug)
        assertEquals("premier_league", registry.resolve("Premier League")?.slug)
        assertEquals("champions_league", registry.resolve("欧冠")?.slug)
        assertEquals("champions_league", registry.resolve("UCL")?.slug)
        assertEquals("la_liga", registry.resolve("西甲")?.slug)
        assertEquals("serie_a", registry.resolve("意甲")?.slug)
        assertEquals("bundesliga", registry.resolve("德甲")?.slug)
        assertEquals("ligue_1", registry.resolve("法甲")?.slug)
    }

    @Test
    fun `sports fixtures resolves Chinese competition alias and applies default date window`() =
        runBlocking {
            val source = StaticFootballFixtureSource(now)
            val tool = SportsFixturesReadTool(
                fixtureAcquirer = FootballFixtureAcquirer(
                    primary = source,
                    secondary = null,
                    webDiscoverySource = null,
                ),
                competitionRegistry = StaticFootballCompetitionRegistry(),
            )

            val outcome = tool.execute(buildJsonObject { put("competition", "英超") }, context)

            val success = assertIs<ReadToolOutcome.Success>(outcome)
            assertEquals(SportsFixturesKey.value, success.payload.evidence.single().sourceKey)
            assertEquals(
                FootballFixtureQuery(
                    teamName = null,
                    dateFrom = LocalDate.of(2026, 9, 6),
                    dateTo = LocalDate.of(2026, 9, 16),
                    league = "premier_league",
                ),
                source.queries.single(),
            )
            assertEquals("Premier League", success.textFact(ReadToolFactKind.COMPETITION))
            assertEquals("Liverpool", success.textFact(ReadToolFactKind.HOME_TEAM))
            assertEquals("Arsenal", success.textFact(ReadToolFactKind.AWAY_TEAM))
            assertEquals(Instant.parse("2026-09-12T12:00:00Z"), success.timestampFact(ReadToolFactKind.START_TIME))
            assertEquals("Anfield, Liverpool", success.textFact(ReadToolFactKind.LOCATION_NAME))
            assertEquals("Scheduled", success.textFact(ReadToolFactKind.STATUS))
            Unit
        }

    @Test
    fun `sports fixtures returns evidence when venue is absent`() =
        runBlocking {
            val source = StaticFootballFixtureSource(now, venueName = null, venueCity = null)
            val tool = SportsFixturesReadTool(
                fixtureAcquirer = FootballFixtureAcquirer(
                    primary = source,
                    secondary = null,
                    webDiscoverySource = null,
                ),
                competitionRegistry = StaticFootballCompetitionRegistry(),
            )

            val outcome = tool.execute(buildJsonObject { put("competition", "EPL") }, context)

            val success = assertIs<ReadToolOutcome.Success>(outcome)
            assertEquals(SportsFixturesKey.value, success.payload.evidence.single().sourceKey)
            assertEquals("Liverpool", success.textFact(ReadToolFactKind.HOME_TEAM))
            assertEquals("Arsenal", success.textFact(ReadToolFactKind.AWAY_TEAM))
            assertNull(success.textFact(ReadToolFactKind.LOCATION_NAME))
            Unit
        }

    @Test
    fun `sports fixtures opportunity keeps location absent when venue is absent`() =
        runBlocking {
            val acquirer = FootballFixtureAcquirer(
                primary = StaticFootballFixtureSource(now, venueName = null, venueCity = null),
                secondary = null,
                webDiscoverySource = null,
            )

            val opportunities = acquirer.acquire(
                FootballFixtureQuery(
                    teamName = null,
                    dateFrom = LocalDate.of(2026, 9, 6),
                    dateTo = LocalDate.of(2026, 9, 16),
                    league = "premier_league",
                ),
                referenceTime = now,
            )

            val opportunity = opportunities.single()
            assertNull(opportunity.facts.location)
            assertFalse(opportunity.facts.attributes.containsKey("locations"))
            assertTrue(opportunity.facts.attributes.containsKey("topics"))
            assertTrue(opportunity.facts.attributes.containsKey("fixtureStatus"))
            Unit
        }

    @Test
    fun `sports fixtures executor returns evidence from fake football source`() =
        runBlocking {
            val source = StaticFootballFixtureSource(now)
            val executor = ReadToolExecutor(
                ReadToolCatalog(
                    listOf(
                        SportsFixturesReadTool(
                            fixtureAcquirer = FootballFixtureAcquirer(
                                primary = source,
                                secondary = null,
                                webDiscoverySource = null,
                            ),
                            competitionRegistry = StaticFootballCompetitionRegistry(),
                        ),
                    ),
                ),
            )

            val executions = executor.execute(
                listOf(
                    ReadToolCall(
                        SportsFixturesKey,
                        buildJsonObject {
                            put("competition", "Premier League")
                            put("team", "Liverpool")
                            put("dateFrom", "2026-09-12")
                            put("dateTo", "2026-09-13")
                        },
                    ),
                ),
                context,
            )

            val success = assertIs<ReadToolOutcome.Success>(executions.single().outcome)
            assertEquals(SportsFixturesKey.value, success.payload.evidence.single().sourceKey)
            assertEquals("https://football.example/fixtures/1", success.payload.evidence.single().sourceUrl)
            assertEquals("Liverpool", source.queries.single().teamName)
            assertEquals("premier_league", source.queries.single().league)
            Unit
        }

    @Test
    fun `sports fixtures unsupported competition returns InvalidArguments`() =
        runBlocking {
            val tool = SportsFixturesReadTool(
                fixtureAcquirer = FootballFixtureAcquirer(
                    primary = StaticFootballFixtureSource(now),
                    secondary = null,
                    webDiscoverySource = null,
                ),
                competitionRegistry = StaticFootballCompetitionRegistry(),
            )

            val outcome = tool.execute(buildJsonObject { put("competition", "provider-league-39") }, context)

            assertIs<ReadToolOutcome.InvalidArguments>(outcome)
            Unit
        }

    @Test
    fun `sports fixtures empty vertical source returns Empty`() =
        runBlocking {
            val tool = SportsFixturesReadTool(
                fixtureAcquirer = FootballFixtureAcquirer(
                    primary = EmptyFootballFixtureSource(),
                    secondary = null,
                    webDiscoverySource = null,
                ),
                competitionRegistry = StaticFootballCompetitionRegistry(),
            )

            val outcome = tool.execute(buildJsonObject { put("competition", "EPL") }, context)

            assertIs<ReadToolOutcome.Empty>(outcome)
            Unit
        }

    @Test
    fun `sports fixtures technical failure returns Unavailable`() =
        runBlocking {
            val tool = SportsFixturesReadTool(
                fixtureAcquirer = FootballFixtureAcquirer(
                    primary = FailingFootballFixtureSource(),
                    secondary = null,
                    webDiscoverySource = null,
                ),
                competitionRegistry = StaticFootballCompetitionRegistry(),
            )

            val outcome = tool.execute(buildJsonObject { put("competition", "EPL") }, context)

            assertIs<ReadToolOutcome.Unavailable>(outcome)
            Unit
        }

    @Test
    fun `music metadata returns MusicBrainz evidence without raw provider payload`() =
        runBlocking {
            val source = StaticMusicMetadataSource(now)
            val tool = MusicMetadataReadTool(source)

            val outcome = tool.execute(
                buildJsonObject {
                    put("query", "Radiohead")
                    put("type", "release")
                    put("maxResults", 3)
                },
                context,
            )

            val success = assertIs<ReadToolOutcome.Success>(outcome)
            assertEquals(MusicMetadataKey.value, success.payload.evidence.single().sourceKey)
            assertEquals(MusicMetadataQuery("Radiohead", MusicMetadataSearchType.Release, 3), source.queries.single())
            assertEquals("OK Computer", success.textFact(ReadToolFactKind.TITLE))
            assertFalse(success.textFacts().any { it.contains("RAW_MUSICBRAINZ_PAYLOAD") })
            Unit
        }

    @Test
    fun `music events applies default live music query and date window`() =
        runBlocking {
            val source = StaticLiveMusicEventSource(now)
            val tool = MusicEventsReadTool(
                LiveMusicEventAcquirer(
                    primary = source,
                    secondary = null,
                    webDiscoverySource = null,
                ),
            )

            val outcome = tool.execute(buildJsonObject { put("city", "London") }, context)

            val success = assertIs<ReadToolOutcome.Success>(outcome)
            assertEquals(MusicEventsKey.value, success.payload.evidence.single().sourceKey)
            assertEquals(
                LiveMusicEventQuery(
                    keyword = "concert live music",
                    city = "London",
                    dateFrom = LocalDate.of(2026, 9, 6),
                    dateTo = LocalDate.of(2026, 10, 6),
                ),
                source.queries.single(),
            )
            assertEquals("Radiohead Live", success.textFact(ReadToolFactKind.TITLE))
            Unit
        }

    @Test
    fun `music events reports invalid arguments empty and unavailable distinctly`() =
        runBlocking {
            val emptyTool = MusicEventsReadTool(
                LiveMusicEventAcquirer(
                    primary = EmptyLiveMusicEventSource(),
                    secondary = null,
                    webDiscoverySource = null,
                ),
            )
            assertIs<ReadToolOutcome.InvalidArguments>(
                emptyTool.execute(buildJsonObject { put("maxResults", 99) }, context),
            )
            assertIs<ReadToolOutcome.Empty>(emptyTool.execute(emptyArgs(), context))

            val failingTool = MusicEventsReadTool(
                LiveMusicEventAcquirer(
                    primary = FailingLiveMusicEventSource(),
                    secondary = null,
                    webDiscoverySource = null,
                ),
            )
            assertIs<ReadToolOutcome.Unavailable>(failingTool.execute(emptyArgs(), context))
            Unit
        }

    @Test
    fun `sports events returns evidence and remains distinct from sports fixtures`() =
        runBlocking {
            val sportsEventSource = StaticGeneralSportsEventSource(now)
            val fixtureSource = StaticFootballFixtureSource(now)
            val executor = ReadToolExecutor(
                ReadToolCatalog(
                    listOf(
                        SportsEventsReadTool(
                            GeneralSportsEventAcquirer(
                                primary = sportsEventSource,
                                secondary = null,
                                webDiscoverySource = null,
                            ),
                        ),
                        SportsFixturesReadTool(
                            fixtureAcquirer = FootballFixtureAcquirer(
                                primary = fixtureSource,
                                secondary = null,
                                webDiscoverySource = null,
                            ),
                            competitionRegistry = StaticFootballCompetitionRegistry(),
                        ),
                    ),
                ),
            )

            val executions = executor.execute(
                listOf(ReadToolCall(SportsEventsKey, buildJsonObject { put("sport", "basketball") })),
                context,
            )

            val success = assertIs<ReadToolOutcome.Success>(executions.single().outcome)
            assertEquals(SportsEventsKey.value, success.payload.evidence.single().sourceKey)
            assertEquals("basketball", sportsEventSource.queries.single().keyword)
            assertEquals(emptyList(), fixtureSource.queries)
            assertEquals("London Lions vs Paris", success.textFact(ReadToolFactKind.TITLE))
            Unit
        }

    @Test
    fun `sports events reports empty and unavailable distinctly`() =
        runBlocking {
            val emptyTool = SportsEventsReadTool(
                GeneralSportsEventAcquirer(
                    primary = EmptyGeneralSportsEventSource(),
                    secondary = null,
                    webDiscoverySource = null,
                ),
            )
            assertIs<ReadToolOutcome.Empty>(emptyTool.execute(emptyArgs(), context))

            val failingTool = SportsEventsReadTool(
                GeneralSportsEventAcquirer(
                    primary = FailingGeneralSportsEventSource(),
                    secondary = null,
                    webDiscoverySource = null,
                ),
            )
            assertIs<ReadToolOutcome.Unavailable>(failingTool.execute(emptyArgs(), context))
            Unit
        }

    @Test
    fun `outdoor trails composite read tool returns enriched typed evidence`() =
        runBlocking {
            val places = StaticPlaceLookupSource(now)
            val trails = StaticTrailDiscoverySource(now)
            val routes = StaticRouteSource(now)
            val weather = StaticWeatherSource(now, label = "MET Norway", sourceId = "met-no")
            val tool = OutdoorTrailsReadTool(
                OutdoorAcquirer(
                    trailPrimary = trails,
                    trailSecondary = null,
                    placePrimary = places,
                    placeSecondary = null,
                    routePrimary = routes,
                    routeSecondary = null,
                    weatherPrimary = weather,
                    weatherSecondary = null,
                    webDiscoverySource = null,
                ),
            )

            val outcome = tool.execute(
                buildJsonObject {
                    put("near", "Hong Kong")
                    put("keyword", "Dragon Back")
                    put("originLatitude", 22.300)
                    put("originLongitude", 114.170)
                    put("dateFrom", "2026-09-12")
                    put("dateTo", "2026-09-12")
                },
                context,
            )

            val success = assertIs<ReadToolOutcome.Success>(outcome)
            assertEquals(OutdoorTrailsKey.value, success.payload.evidence.single().sourceKey)
            assertEquals(PlaceLookupQuery("Hong Kong", null), places.queries.first())
            assertEquals(GeoPoint(22.236, 114.242), trails.queries.single().center)
            assertEquals(GeoPoint(22.300, 114.170), routes.queries.single().origin)
            assertEquals(GeoPoint(22.236, 114.242), weather.queries.single().point)
            assertEquals("Dragon Back Trail", success.textFact(ReadToolFactKind.TITLE))
            assertEquals(150L, success.integerFact(ReadToolFactKind.COMMUTE_MINUTES))
            Unit
        }

    @Test
    fun `outdoor trails reports missing location empty and unavailable distinctly`() =
        runBlocking {
            val emptyTool = OutdoorTrailsReadTool(
                OutdoorAcquirer(
                    trailPrimary = EmptyTrailDiscoverySource(),
                    trailSecondary = null,
                    placePrimary = StaticPlaceLookupSource(now),
                    placeSecondary = null,
                    routePrimary = null,
                    routeSecondary = null,
                    weatherPrimary = null,
                    weatherSecondary = null,
                    webDiscoverySource = null,
                ),
            )
            assertIs<ReadToolOutcome.MissingInput>(emptyTool.execute(emptyArgs(), context))
            assertIs<ReadToolOutcome.Empty>(emptyTool.execute(buildJsonObject { put("near", "Hong Kong") }, context))

            val failingTool = OutdoorTrailsReadTool(
                OutdoorAcquirer(
                    trailPrimary = FailingTrailDiscoverySource(),
                    trailSecondary = null,
                    placePrimary = StaticPlaceLookupSource(now),
                    placeSecondary = null,
                    routePrimary = null,
                    routeSecondary = null,
                    weatherPrimary = null,
                    weatherSecondary = null,
                    webDiscoverySource = null,
                ),
            )
            assertIs<ReadToolOutcome.Unavailable>(failingTool.execute(buildJsonObject { put("near", "Hong Kong") }, context))
            Unit
        }

    @Test
    fun `places search returns coordinates and falls back from empty primary to secondary`() =
        runBlocking {
            val secondary = StaticPlaceLookupSource(now)
            val tool = PlacesSearchReadTool(primary = EmptyPlaceLookupSource(), secondary = secondary)

            val outcome = tool.execute(
                buildJsonObject {
                    put("text", "Dragon Back")
                    put("near", "Hong Kong")
                },
                context,
            )

            val success = assertIs<ReadToolOutcome.Success>(outcome)
            assertEquals(PlacesSearchKey.value, success.payload.evidence.single().sourceKey)
            assertEquals(PlaceLookupQuery("Dragon Back", "Hong Kong"), secondary.queries.single())
            assertEquals(22.236, success.decimalFact(ReadToolFactKind.LATITUDE))
            assertEquals(114.242, success.decimalFact(ReadToolFactKind.LONGITUDE))
            Unit
        }

    @Test
    fun `route estimate returns typed distance evidence and validates coordinates`() =
        runBlocking {
            val source = StaticRouteSource(now)
            val tool = RouteEstimateReadTool(primary = source, secondary = null)

            assertIs<ReadToolOutcome.MissingInput>(
                tool.execute(buildJsonObject { put("destinationLatitude", 22.236) }, context),
            )
            assertIs<ReadToolOutcome.InvalidArguments>(
                tool.execute(
                    buildJsonObject {
                        put("originLatitude", 22.300)
                        put("originLongitude", 114.170)
                        put("destinationLatitude", 22.236)
                        put("destinationLongitude", 114.242)
                        put("profile", "driving")
                    },
                    context,
                ),
            )

            val outcome = tool.execute(
                buildJsonObject {
                    put("originLatitude", 22.300)
                    put("originLongitude", 114.170)
                    put("destinationLatitude", 22.236)
                    put("destinationLongitude", 114.242)
                    put("profile", "walking")
                },
                context,
            )

            val success = assertIs<ReadToolOutcome.Success>(outcome)
            assertEquals(RouteEstimateKey.value, success.payload.evidence.single().sourceKey)
            assertEquals(GeoPoint(22.300, 114.170), source.queries.single().origin)
            assertEquals(8500L, success.integerFact(ReadToolFactKind.DISTANCE_METERS))
            assertEquals(150L, success.integerFact(ReadToolFactKind.DURATION_MINUTES))
            Unit
        }

    @Test
    fun `source exception returns Unavailable not Empty`() =
        runBlocking {
            val tool = MovieDetailsReadTool(
                MovieMetadataAcquirer(
                    primary = FailingMovieMetadataSource(),
                    secondary = null,
                ),
            )

            val outcome = tool.execute(buildJsonObject { put("title", "Failure") }, context)

            assertIs<ReadToolOutcome.Unavailable>(outcome)
            Unit
        }

    private fun ReadToolOutcome.Success.textFact(kind: ReadToolFactKind): String? =
        payload.evidence.single().facts.firstOrNull { it.kind == kind }?.value?.let { value ->
            (value as? ReadToolFactValue.Text)?.value
        }

    private fun ReadToolOutcome.Success.textFacts(): List<String> =
        payload.evidence.single().facts.mapNotNull { fact ->
            (fact.value as? ReadToolFactValue.Text)?.value
        }

    private fun ReadToolOutcome.Success.integerFact(kind: ReadToolFactKind): Long? =
        payload.evidence.single().facts.firstOrNull { it.kind == kind }?.value?.let { value ->
            (value as? ReadToolFactValue.Integer)?.value
        }

    private fun ReadToolOutcome.Success.decimalFact(kind: ReadToolFactKind): Double? =
        payload.evidence.single().facts.firstOrNull { it.kind == kind }?.value?.let { value ->
            (value as? ReadToolFactValue.Decimal)?.value
        }

    private fun ReadToolOutcome.Success.timestampFact(kind: ReadToolFactKind): Instant? =
        payload.evidence.single().facts.firstOrNull { it.kind == kind }?.value?.let { value ->
            (value as? ReadToolFactValue.Timestamp)?.value
        }

    private fun emptyArgs(): JsonObject = buildJsonObject {}

    private class FakeReadTool(
        key: ReadToolKey,
    ) : ReadTool {
        override val definition: ReadToolDefinition = ReadToolDefinition(
            key = key,
            description = "Fake read-only tool.",
            argumentHint = "No arguments.",
        )

        override suspend fun execute(
            proposedArguments: JsonObject,
            context: ReadToolExecutionContext,
        ): ReadToolOutcome = ReadToolOutcome.Empty
    }


    private class GatedReadTool(
        key: ReadToolKey,
        private val gate: suspend () -> Unit,
    ) : ReadTool {
        override val definition: ReadToolDefinition = ReadToolDefinition(
            key = key,
            description = "Gated read-only tool.",
            argumentHint = "No arguments.",
        )

        override suspend fun execute(
            proposedArguments: JsonObject,
            context: ReadToolExecutionContext,
        ): ReadToolOutcome {
            gate()
            return ReadToolOutcome.Empty
        }
    }

    private class StaticTrailDiscoverySource(
        private val now: Instant,
    ) : TrailDiscoverySource {
        val queries = mutableListOf<TrailDiscoveryQuery>()

        override suspend fun search(query: TrailDiscoveryQuery): List<TrailCandidate> {
            queries += query
            return listOf(
                TrailCandidate(
                    externalTrailId = "trail-1",
                    name = "Dragon Back Trail",
                    routeType = "hiking",
                    distanceMeters = 8_500,
                    elevationGainMeters = 300,
                    startLocation = GeoPoint(22.236, 114.242),
                    summary = "A ridge hike with sea views.",
                    publicUrl = "https://outdoor.example/trails/dragon-back",
                    sources = listOf(
                        SourceRef(
                            label = "OpenStreetMap Overpass",
                            uri = "https://www.openstreetmap.org/way/1001",
                            sourceUpdatedAt = now,
                            sourceId = "overpass",
                            authority = SourceAuthority.StructuredPrimary,
                            factKeys = setOf(OpportunityFactKey.TrailMetadata),
                        ),
                    ),
                ),
            )
        }
    }

    private class EmptyTrailDiscoverySource : TrailDiscoverySource {
        override suspend fun search(query: TrailDiscoveryQuery): List<TrailCandidate> = emptyList()
    }

    private class FailingTrailDiscoverySource : TrailDiscoverySource {
        override suspend fun search(query: TrailDiscoveryQuery): List<TrailCandidate> {
            throw ExternalSourceUnavailableException("fake-trails", "search")
        }
    }

    private class StaticPlaceLookupSource(
        private val now: Instant,
    ) : PlaceLookupSource {
        val queries = mutableListOf<PlaceLookupQuery>()

        override suspend fun find(query: PlaceLookupQuery): List<PlaceCandidate> {
            queries += query
            return listOf(
                PlaceCandidate(
                    externalPlaceId = "place-1",
                    displayName = "Dragon Back Trail, Hong Kong",
                    point = GeoPoint(22.236, 114.242),
                    publicUrl = "https://www.openstreetmap.org/way/1001",
                    sources = listOf(
                        SourceRef(
                            label = "Nominatim",
                            uri = "https://nominatim.example/search",
                            sourceUpdatedAt = now,
                            sourceId = "nominatim",
                            authority = SourceAuthority.StructuredSecondary,
                            factKeys = setOf(OpportunityFactKey.PlaceLookup, OpportunityFactKey.Location),
                        ),
                    ),
                ),
            )
        }
    }

    private class EmptyPlaceLookupSource : PlaceLookupSource {
        override suspend fun find(query: PlaceLookupQuery): List<PlaceCandidate> = emptyList()
    }

    private class StaticRouteSource(
        private val now: Instant,
    ) : RouteSource {
        val queries = mutableListOf<RouteQuery>()

        override suspend fun route(query: RouteQuery): RouteFact {
            queries += query
            return RouteFact(
                distanceMeters = 8_500,
                durationMinutes = 150,
                commuteMinutes = 150,
                source = SourceRef(
                    label = "openrouteservice Routing",
                    uri = "https://routes.example/route",
                    sourceUpdatedAt = now,
                    sourceId = "openrouteservice-route",
                    authority = SourceAuthority.StructuredPrimary,
                    factKeys = setOf(OpportunityFactKey.Route),
                ),
            )
        }
    }

    private class StaticWeatherSource(
        private val now: Instant,
        private val label: String = "Open-Meteo",
        private val sourceId: String = "open-meteo",
    ) : WeatherSource {
        val queries = mutableListOf<WeatherQuery>()

        override suspend fun forecast(query: WeatherQuery): WeatherFact {
            queries += query
            return WeatherFact(
                summary = "Sunny",
                temperatureCelsius = 27,
                precipitationProbabilityPercent = 10,
                windSpeedKph = 12,
                source = SourceRef(
                    label = label,
                    uri = "https://weather.example/forecast",
                    sourceUpdatedAt = now,
                    sourceId = sourceId,
                    authority = SourceAuthority.StructuredPrimary,
                    factKeys = setOf(OpportunityFactKey.Weather),
                ),
            )
        }
    }

    private class StaticMovieMetadataSource(
        private val now: Instant,
    ) : MovieMetadataSource {
        override suspend fun search(query: MovieMetadataQuery): List<MovieMetadataCandidate> =
            listOf(
                MovieMetadataCandidate(
                    externalMovieId = "tmdb-1",
                    title = query.title,
                    releaseDate = LocalDate.of(2025, 1, 29),
                    runtimeMinutes = 144,
                    genres = setOf("Animation"),
                    summary = "A bounded movie summary.",
                    source = SourceRef(
                        label = "TMDB",
                        uri = "https://www.themoviedb.org/movie/1",
                        sourceUpdatedAt = now,
                        sourceId = "tmdb",
                        authority = SourceAuthority.StructuredPrimary,
                        factKeys = setOf(OpportunityFactKey.MovieMetadata),
                    ),
                ),
            )
    }

    private class EmptyMovieMetadataSource : MovieMetadataSource {
        override suspend fun search(query: MovieMetadataQuery): List<MovieMetadataCandidate> = emptyList()
    }

    private class RecordingMovieDiscoverySource(
        private val now: Instant,
    ) : MovieDiscoverySource {
        val queries = mutableListOf<MovieDiscoveryQuery>()

        override suspend fun discover(query: MovieDiscoveryQuery): List<MovieDiscoveryCandidate> {
            queries += query
            return listOf(
                MovieDiscoveryCandidate(
                    externalMovieId = "tmdb-2",
                    title = "TMDB Now Playing",
                    originalTitle = "TMDB Now Playing",
                    releaseDate = LocalDate.of(2026, 9, 1),
                    summary = "Structured TMDB discovery result.",
                    genreIds = listOf("tmdb:18"),
                    popularity = 42.0,
                    publicUrl = "https://www.themoviedb.org/movie/2",
                    source = SourceRef(
                        label = "TMDB",
                        uri = "https://www.themoviedb.org/movie/2",
                        sourceUpdatedAt = now,
                        sourceId = "tmdb",
                        authority = SourceAuthority.StructuredPrimary,
                        factKeys = setOf(OpportunityFactKey.MovieMetadata),
                    ),
                ),
            )
        }
    }

    private class EmptyMovieDiscoverySource : MovieDiscoverySource {
        override suspend fun discover(query: MovieDiscoveryQuery): List<MovieDiscoveryCandidate> = emptyList()
    }

    private class RecordingMovieMetadataSource : MovieMetadataSource {
        val queries = mutableListOf<MovieMetadataQuery>()

        override suspend fun search(query: MovieMetadataQuery): List<MovieMetadataCandidate> {
            queries += query
            return emptyList()
        }
    }

    private class FailingMovieMetadataSource : MovieMetadataSource {
        override suspend fun search(query: MovieMetadataQuery): List<MovieMetadataCandidate> {
            throw ExternalSourceUnavailableException("tmdb", "search")
        }
    }

    private class StaticMovieShowtimeSource(
        private val now: Instant,
    ) : MovieShowtimeSource {
        val queries = mutableListOf<MovieShowtimeQuery>()

        override suspend fun search(query: MovieShowtimeQuery): List<MovieShowtimeCandidate> {
            queries += query
            return listOf(
                MovieShowtimeCandidate(
                    externalShowtimeId = "showtime-1",
                    movieTitle = query.title ?: "哪吒2",
                    cinemaName = "深圳影城",
                    startsAt = now.plusSeconds(3600),
                    endsAt = now.plusSeconds(9000),
                    city = query.city,
                    price = MoneyFact(48, "CNY"),
                    availability = AvailabilityFact.Available,
                    publicUrl = "https://cinema.example/showtimes/1",
                    source = SourceRef(
                        label = "China official cinema page",
                        uri = "https://cinema.example/showtimes/1",
                        sourceUpdatedAt = now,
                        sourceId = "china-official-cinema-page",
                        authority = SourceAuthority.OfficialWeb,
                        factKeys = setOf(OpportunityFactKey.MovieShowtime),
                    ),
                ),
            )
        }
    }

    private class EmptyMovieShowtimeSource : MovieShowtimeSource {
        override suspend fun search(query: MovieShowtimeQuery): List<MovieShowtimeCandidate> = emptyList()
    }

    private class FailingMovieShowtimeSource : MovieShowtimeSource {
        override suspend fun search(query: MovieShowtimeQuery): List<MovieShowtimeCandidate> {
            throw ExternalSourceUnavailableException("china-official-cinema-page", "search")
        }
    }

    private class StaticFootballFixtureSource(
        private val now: Instant,
        private val venueName: String? = "Anfield",
        private val venueCity: String? = "Liverpool",
    ) : FootballFixtureSource {
        val queries = mutableListOf<FootballFixtureQuery>()

        override suspend fun search(query: FootballFixtureQuery): List<FootballFixtureCandidate> {
            queries += query
            return listOf(
                FootballFixtureCandidate(
                    externalFixtureId = "fixture-1",
                    competition = "Premier League",
                    homeTeam = "Liverpool",
                    awayTeam = "Arsenal",
                    startsAt = Instant.parse("2026-09-12T12:00:00Z"),
                    venueName = venueName,
                    venueCity = venueCity,
                    status = FixtureStatus.Scheduled,
                    sources = listOf(
                        SourceRef(
                            label = "Fake Football",
                            uri = "https://football.example/fixtures/1",
                            sourceUpdatedAt = now,
                            sourceId = "fake-football",
                            authority = SourceAuthority.StructuredPrimary,
                            factKeys = setOf(
                                OpportunityFactKey.Title,
                                OpportunityFactKey.StartTime,
                                OpportunityFactKey.Location,
                                OpportunityFactKey.FixtureStatus,
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    private class EmptyFootballFixtureSource : FootballFixtureSource {
        override suspend fun search(query: FootballFixtureQuery): List<FootballFixtureCandidate> = emptyList()
    }

    private class FailingFootballFixtureSource : FootballFixtureSource {
        override suspend fun search(query: FootballFixtureQuery): List<FootballFixtureCandidate> {
            throw ExternalSourceUnavailableException("fake-football", "search")
        }
    }

    private class StaticMusicMetadataSource(
        private val now: Instant,
    ) : MusicMetadataSource {
        val queries = mutableListOf<MusicMetadataQuery>()

        override suspend fun search(query: MusicMetadataQuery): List<MusicMetadataCandidate> {
            queries += query
            return listOf(
                MusicMetadataCandidate(
                    externalId = "mb-release-1",
                    type = MusicMetadataSearchType.Release,
                    title = "OK Computer",
                    artists = setOf("Radiohead"),
                    date = LocalDate.of(1997, 5, 21),
                    countryCode = "GB",
                    status = "Official",
                    disambiguation = null,
                    lengthMillis = null,
                    source = SourceRef(
                        label = "MusicBrainz",
                        uri = "https://musicbrainz.org/release/mb-release-1",
                        sourceUpdatedAt = now,
                        sourceId = "musicbrainz",
                        authority = SourceAuthority.StructuredPrimary,
                        factKeys = setOf(OpportunityFactKey.Summary),
                    ),
                ),
            )
        }
    }

    private class StaticLiveMusicEventSource(
        private val now: Instant,
    ) : LiveMusicEventSource {
        val queries = mutableListOf<LiveMusicEventQuery>()

        override suspend fun search(query: LiveMusicEventQuery): List<LiveMusicEventCandidate> {
            queries += query
            return listOf(
                LiveMusicEventCandidate(
                    externalEventId = "music-event-1",
                    title = "Radiohead Live",
                    artists = setOf("Radiohead"),
                    startsAt = Instant.parse("2026-10-05T19:00:00Z"),
                    endsAt = Instant.parse("2026-10-05T22:00:00Z"),
                    venueName = "O2 Arena",
                    city = "London",
                    latitude = null,
                    longitude = null,
                    publicUrl = "https://ticketmaster.example/music-event-1",
                    availability = AvailabilityFact.Available,
                    sources = listOf(
                        SourceRef(
                            label = "Ticketmaster",
                            uri = "https://ticketmaster.example/music-event-1",
                            sourceUpdatedAt = now,
                            sourceId = "ticketmaster",
                            authority = SourceAuthority.StructuredPrimary,
                            factKeys = setOf(OpportunityFactKey.LiveEventMetadata),
                        ),
                    ),
                ),
            )
        }
    }

    private class EmptyLiveMusicEventSource : LiveMusicEventSource {
        override suspend fun search(query: LiveMusicEventQuery): List<LiveMusicEventCandidate> = emptyList()
    }

    private class FailingLiveMusicEventSource : LiveMusicEventSource {
        override suspend fun search(query: LiveMusicEventQuery): List<LiveMusicEventCandidate> {
            throw ExternalSourceUnavailableException("fake-live-music", "search")
        }
    }

    private class StaticGeneralSportsEventSource(
        private val now: Instant,
    ) : GeneralSportsEventSource {
        val queries = mutableListOf<GeneralSportsEventQuery>()

        override suspend fun search(query: GeneralSportsEventQuery): List<GeneralSportsEventCandidate> {
            queries += query
            return listOf(
                GeneralSportsEventCandidate(
                    externalEventId = "sports-event-1",
                    title = "London Lions vs Paris",
                    sportName = "Basketball",
                    startsAt = Instant.parse("2026-10-05T19:00:00Z"),
                    endsAt = Instant.parse("2026-10-05T21:00:00Z"),
                    venueName = "Copper Box Arena",
                    city = "London",
                    publicUrl = "https://ticketmaster.example/sports-event-1",
                    availability = AvailabilityFact.Available,
                    sources = listOf(
                        SourceRef(
                            label = "Ticketmaster",
                            uri = "https://ticketmaster.example/sports-event-1",
                            sourceUpdatedAt = now,
                            sourceId = "ticketmaster",
                            authority = SourceAuthority.StructuredPrimary,
                            factKeys = setOf(OpportunityFactKey.LiveEventMetadata),
                        ),
                    ),
                ),
            )
        }
    }

    private class EmptyGeneralSportsEventSource : GeneralSportsEventSource {
        override suspend fun search(query: GeneralSportsEventQuery): List<GeneralSportsEventCandidate> = emptyList()
    }

    private class FailingGeneralSportsEventSource : GeneralSportsEventSource {
        override suspend fun search(query: GeneralSportsEventQuery): List<GeneralSportsEventCandidate> {
            throw ExternalSourceUnavailableException("fake-sports-event", "search")
        }
    }

    private class RecordingWebDiscoverySource(
        private val hits: List<WebSearchHit>,
    ) : WebDiscoverySource {
        val searchRequests = mutableListOf<WebSearchQuery>()

        override suspend fun search(request: WebSearchQuery): List<WebSearchHit> {
            searchRequests += request
            return hits
        }

        override suspend fun extract(urls: List<String>): List<WebExtractedPage> = emptyList()
    }
}
