package com.nexusflow.backend.feature.task.domain.source

enum class FootballCompetition(
    val slug: String,
    val displayName: String,
) {
    PremierLeague("premier_league", "Premier League"),
    ChampionsLeague("champions_league", "UEFA Champions League"),
    LaLiga("la_liga", "La Liga"),
    SerieA("serie_a", "Serie A"),
    Bundesliga("bundesliga", "Bundesliga"),
    Ligue1("ligue_1", "Ligue 1"),
}

interface FootballCompetitionRegistry {
    fun resolve(input: String): FootballCompetition?
}

class StaticFootballCompetitionRegistry : FootballCompetitionRegistry {
    override fun resolve(input: String): FootballCompetition? =
        aliases[input.normalizedCompetitionAlias()]

    private companion object {
        val aliases: Map<String, FootballCompetition> =
            buildMap {
                register(
                    FootballCompetition.PremierLeague,
                    "premier_league",
                    "英超",
                    "epl",
                    "premier league",
                    "english premier league",
                )
                register(
                    FootballCompetition.ChampionsLeague,
                    "champions_league",
                    "欧冠",
                    "ucl",
                    "champions league",
                    "uefa champions league",
                )
                register(
                    FootballCompetition.LaLiga,
                    "la_liga",
                    "西甲",
                    "la liga",
                    "laliga",
                    "spanish la liga",
                )
                register(
                    FootballCompetition.SerieA,
                    "serie_a",
                    "意甲",
                    "serie a",
                    "italian serie a",
                )
                register(
                    FootballCompetition.Bundesliga,
                    "bundesliga",
                    "德甲",
                    "german bundesliga",
                )
                register(
                    FootballCompetition.Ligue1,
                    "ligue_1",
                    "法甲",
                    "ligue 1",
                    "ligue1",
                    "french ligue 1",
                )
            }

        fun MutableMap<String, FootballCompetition>.register(
            competition: FootballCompetition,
            vararg values: String,
        ) {
            values.forEach { value -> put(value.normalizedCompetitionAlias(), competition) }
        }
    }
}

private fun String.normalizedCompetitionAlias(): String =
    trim()
        .lowercase()
        .replace(Regex("[\\s._-]+"), " ")
