package com.horis.cncverse

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.net.URLEncoder

abstract class NewTvBaseProvider(
    override var name: String,
    val ott: String,
    private val categoryFilter: ((String) -> Boolean)? = null
) : MainAPI() {

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "hi"
    override var mainUrl = "https://net52.cc"
    override val hasMainPage = true

    protected open fun buildPosterUrl(template: String?, id: String): String? {
        if (template.isNullOrBlank()) return null
        return if (template.contains("------------------")) {
            template.replace("------------------", id)
        } else {
            "${template.trimEnd('/')}/$id.jpg"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val apiBase = resolveApiUrl()
        val response = app.get(
            "$apiBase/newtv/main.php",
            headers = buildNewTvHeaders(
                ott,
                mapOf("Page" to "all", "Recentplay" to "", "Watchlist" to "", "Usertoken" to "")
            )
        ).parsed<NewTvMainResponse>()

        val referer = response.img_referer ?: apiBase
        val categories = response.post.orEmpty().filter { cat ->
            val cateName = cat.cate.orEmpty()
            categoryFilter?.invoke(cateName) ?: true
        }

        val items = categories.map { category ->
            val ids = category.ids.orEmpty()
                .split(",")
                .map(String::trim)
                .filter(String::isNotEmpty)
            val horizontal = category.row == "h"
            val template = if (horizontal) {
                response.imgcdn_h ?: response.imgcdn_v
            } else {
                response.imgcdn_v ?: response.imgcdn_h
            }
            val results = ids.map { id ->
                newAnimeSearchResponse("", NewTvId(id).toJson()) {
                    posterUrl = buildPosterUrl(template, id)
                    posterHeaders = mapOf("Referer" to referer)
                }
            }
            HomePageList(category.cate.orEmpty(), results, isHorizontalImages = horizontal)
        }
        return newHomePageResponse(items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val apiBase = resolveApiUrl()
        val data = app.get(
            "$apiBase/newtv/search.php?s=${URLEncoder.encode(query, "UTF-8")}",
            headers = buildNewTvHeaders(ott)
        ).parsed<NewTvSearchResponse>()
        val referer = data.img_referer ?: apiBase
        val template = data.imgcdn ?: data.detailsimgcdn

        return data.searchResult.orEmpty().map { item ->
            newAnimeSearchResponse(item.t, NewTvId(item.id).toJson()) {
                posterUrl = buildPosterUrl(template, item.id)
                posterHeaders = mapOf("Referer" to referer)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val apiBase = resolveApiUrl()
        val id = tryParseJson<NewTvId>(url)?.id
            ?: tryParseJson<NewTvLoadData>(url)?.id
            ?: url.trim().removeSurrounding("\"")

        val data = app.get(
            "$apiBase/newtv/post.php?id=$id",
            headers = buildNewTvHeaders(ott, mapOf("Lastep" to "", "Usertoken" to ""))
        ).parsed<NewTvPostResponse>()

        val title = data.title?.takeIf { it.isNotBlank() }
            ?: data.title_name?.takeIf { it.isNotBlank() }
            ?: id
        val playbackId = data.main_id ?: id

        val castStr = data.cast?.takeIf { it.isNotBlank() }
            ?: data.moredetails?.firstOrNull { it.k?.equals("Cast", ignoreCase = true) == true }?.v
        val cast = castStr.orEmpty().split(",").map(String::trim)
            .filter(String::isNotEmpty).map { ActorData(Actor(it)) }

        val genreStr = data.genre?.takeIf { it.isNotBlank() }
            ?: data.moredetails?.firstOrNull { it.k?.contains("Genre", ignoreCase = true) == true }?.v
        val genre = genreStr?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)

        val rating = data.match?.replace("IMDb ", "")
            ?: data.moredetails?.firstOrNull { it.k?.contains("Rating", ignoreCase = true) == true }?.v?.replace("IMDb ", "")

        val duration = convertRuntimeToMinutes(data.runtime.orEmpty())
        val recommendations = data.suggest?.map {
            newAnimeSearchResponse("", NewTvId(it.id).toJson()) {
                posterUrl = buildPosterUrl(data.morelike_poster, it.id)
                posterHeaders = mapOf("Referer" to apiBase)
            }
        }

        val hasActualEpisodes = data.episodes?.any { it != null && it.id.isNotBlank() } == true
        val isSeries = data.type == "t" || hasActualEpisodes

        if (!isSeries) {
            return newMovieLoadResponse(
                title, url, TvType.Movie, NewTvLoadData(title, playbackId).toJson()
            ) {
                posterUrl = buildPosterUrl(data.main_poster, id)
                backgroundPosterUrl = buildPosterUrl(data.main_poster, id)
                posterHeaders = mapOf("Referer" to apiBase)
                plot = data.desc
                year = data.year?.toIntOrNull()
                tags = genre
                actors = cast
                score = Score.from10(rating)
                this.duration = duration
                this.recommendations = recommendations
            }
        }

        val episodes = arrayListOf<Episode>()
        if (data.episodes.isNullOrEmpty()) {
            if (data.type != "t") {
                episodes.add(newEpisode(NewTvLoadData(title, playbackId)) { name = title })
            }
        } else {
            val selectedSeasonIndex = data.season?.indexOfFirst { it.selected == true }?.takeIf { it >= 0 }
            val selectedSeasonId = selectedSeasonIndex?.let { data.season?.getOrNull(it)?.id }
                ?: data.nextPageSeason
            val selectedSeasonNumber = selectedSeasonIndex?.plus(1)

            data.episodes.filterNotNull().mapTo(episodes) {
                val epNum = it.ep.replace("E", "").trim().toIntOrNull()
                    ?: it.info?.firstOrNull { s -> s.startsWith("E") && s.drop(1).toIntOrNull() != null }?.drop(1)?.toIntOrNull()
                val seasonNum = selectedSeasonNumber
                    ?: it.s.replace("S", "").trim().toIntOrNull()
                    ?: it.info?.firstOrNull { s -> s.startsWith("S") && s.drop(1).toIntOrNull() != null }?.drop(1)?.toIntOrNull()
                val runTimeMinutes = it.time.replace("m", "").trim().toIntOrNull()
                    ?: it.info?.firstOrNull { s -> s.endsWith("m") && s.removeSuffix("m").trim().toIntOrNull() != null }?.removeSuffix("m")?.trim()?.toIntOrNull()

                newEpisode(NewTvLoadData(title, it.id)) {
                    name = it.t.ifBlank { "Episode ${epNum ?: (episodes.size + 1)}" }
                    episode = epNum
                    season = seasonNum
                    posterUrl = buildPosterUrl(data.ep_poster, it.id)
                    runTime = runTimeMinutes
                }
            }

            if (data.nextPageShow == 1 && !selectedSeasonId.isNullOrBlank()) {
                episodes.addAll(
                    getEpisodes(title, selectedSeasonId, 2, data.ep_poster, selectedSeasonNumber)
                )
            }
            data.season?.forEachIndexed { index, season ->
                if (season.id != selectedSeasonId && season.id.isNotBlank()) {
                    episodes.addAll(getEpisodes(title, season.id, 1, data.ep_poster, index + 1))
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = buildPosterUrl(data.main_poster, id)
            backgroundPosterUrl = buildPosterUrl(data.main_poster, id)
            posterHeaders = mapOf("Referer" to apiBase)
            plot = data.desc
            year = data.year?.toIntOrNull()
            tags = genre
            actors = cast
            score = Score.from10(rating)
            this.duration = duration
            this.recommendations = recommendations
        }
    }

    private suspend fun getEpisodes(
        title: String,
        seasonId: String,
        page: Int,
        posterTemplate: String?,
        seasonNumber: Int?
    ): List<Episode> {
        val apiBase = resolveApiUrl()
        val episodes = arrayListOf<Episode>()
        var currentPage = page
        while (true) {
            val data = app.get(
                "$apiBase/newtv/episodes.php?id=$seasonId&page=$currentPage",
                headers = buildNewTvHeaders(ott)
            ).parsed<NewTvEpisodesResponse>()
            data.episodes.orEmpty().mapTo(episodes) {
                val epNum = it.ep.replace("E", "").trim().toIntOrNull()
                    ?: it.info?.firstOrNull { s -> s.startsWith("E") && s.drop(1).toIntOrNull() != null }?.drop(1)?.toIntOrNull()
                val seasonNum = seasonNumber
                    ?: it.s.replace("S", "").trim().toIntOrNull()
                    ?: it.info?.firstOrNull { s -> s.startsWith("S") && s.drop(1).toIntOrNull() != null }?.drop(1)?.toIntOrNull()
                val runTimeMinutes = it.time.replace("m", "").trim().toIntOrNull()
                    ?: it.info?.firstOrNull { s -> s.endsWith("m") && s.removeSuffix("m").trim().toIntOrNull() != null }?.removeSuffix("m")?.trim()?.toIntOrNull()

                newEpisode(NewTvLoadData(title, it.id)) {
                    name = it.t.ifBlank { "Episode ${epNum ?: (episodes.size + 1)}" }
                    episode = epNum
                    season = seasonNum
                    posterUrl = buildPosterUrl(posterTemplate, it.id)
                    runTime = runTimeMinutes
                }
            }
            if (data.nextPageShow != 1) break
            currentPage++
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val apiBase = resolveApiUrl()
        val id = tryParseJson<NewTvLoadData>(data)?.id
            ?: tryParseJson<NewTvId>(data)?.id
            ?: data.trim().removeSurrounding("\"")

        val response = app.get(
            "$apiBase/newtv/player.php?id=$id",
            headers = buildNewTvHeaders(ott, mapOf("Usertoken" to ""))
        ).parsed<NewTvPlayerResponse>()

        if (response.video_link.isNullOrBlank()) return false

        callback(newExtractorLink(name, name, response.video_link, type = ExtractorLinkType.M3U8) {
            referer = response.referer ?: apiBase
        })
        return true
    }
}
