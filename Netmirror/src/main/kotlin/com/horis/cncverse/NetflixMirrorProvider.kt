package com.horis.cncverse

import android.content.Context
import com.horis.cncverse.entities.SearchResult
import com.horis.cncverse.entities.Suggest
import com.horis.cncverse.entities.Season
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.net.URLEncoder

class NetflixMirrorProvider : MainAPI() {
    companion object {
        var context: Context? = null
    }

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "hi"
    override var mainUrl = "https://net52.cc"
    override var name = "Netflix"
    override val hasMainPage = true

    private val ott = "nf"

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
        val items = response.post.orEmpty().map { category ->
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
        val template = data.detailsimgcdn ?: data.imgcdn

        return data.searchResult.orEmpty().map { item ->
            newAnimeSearchResponse(item.t, NewTvId(item.id).toJson()) {
                posterUrl = buildPosterUrl(template, item.id)
                posterHeaders = mapOf("Referer" to referer)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val apiBase = resolveApiUrl()
        val id = parseJson<NewTvId>(url).id
        val data = app.get(
            "$apiBase/newtv/post.php?id=$id",
            headers = buildNewTvHeaders(ott, mapOf("Lastep" to "", "Usertoken" to ""))
        ).parsed<NewTvPostResponse>()

        val title = data.title ?: id
        val playbackId = data.main_id ?: id
        val cast = data.cast.orEmpty().split(",").map(String::trim)
            .filter(String::isNotEmpty).map { ActorData(Actor(it)) }
        val genre = data.genre?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
        val rating = data.match?.replace("IMDb ", "")
        val duration = convertRuntimeToMinutes(data.runtime.orEmpty())
        val recommendations = data.suggest?.map {
            newAnimeSearchResponse("", NewTvId(it.id).toJson()) {
                posterUrl = buildPosterUrl(data.morelike_poster, it.id)
                posterHeaders = mapOf("Referer" to apiBase)
            }
        }
        val isSeries = data.type == "t" || data.episodes?.any { it != null } == true

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
            val selectedSeason = data.season?.indexOfFirst { it.sele == "1" }?.takeIf { it >= 0 }
            val selectedSeasonId = selectedSeason?.let { data.season?.getOrNull(it)?.id }
                ?: data.nextPageSeason
            val selectedSeasonNumber = selectedSeason?.plus(1)

            data.episodes.filterNotNull().mapTo(episodes) {
                newEpisode(NewTvLoadData(title, it.id)) {
                    name = it.t
                    episode = it.ep.replace("E", "").toIntOrNull()
                    season = selectedSeasonNumber ?: it.s.replace("S", "").toIntOrNull()
                    posterUrl = buildPosterUrl(data.ep_poster, it.id)
                    runTime = it.time.replace("m", "").toIntOrNull()
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
                newEpisode(NewTvLoadData(title, it.id)) {
                    name = it.t
                    episode = it.ep.replace("E", "").toIntOrNull()
                    season = seasonNumber ?: it.s.replace("S", "").toIntOrNull()
                    posterUrl = buildPosterUrl(posterTemplate, it.id)
                    runTime = it.time.replace("m", "").toIntOrNull()
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
        val id = parseJson<NewTvLoadData>(data).id
        val response = app.get(
            "$apiBase/newtv/player.php?id=$id",
            headers = buildNewTvHeaders(ott, mapOf("Usertoken" to ""))
        ).parsed<NewTvPlayerResponse>()

        if (response.status != "ok" || response.video_link.isNullOrBlank()) return false
        callback(newExtractorLink(name, name, response.video_link, type = ExtractorLinkType.M3U8) {
            referer = response.referer ?: apiBase
        })
        return true
    }

    private data class NewTvId(val id: String)
    private data class NewTvLoadData(val title: String, val id: String)
    private data class NewTvMainResponse(
        val post: List<NewTvPostCategory>? = null,
        val imgcdn_h: String? = null,
        val imgcdn_v: String? = null,
        val img_referer: String? = null
    )
    private data class NewTvPostCategory(
        val ids: String? = null,
        val cate: String? = null,
        val row: String? = null
    )
    private data class NewTvSearchResponse(
        val searchResult: List<SearchResult>? = null,
        val detailsimgcdn: String? = null,
        val imgcdn: String? = null,
        val img_referer: String? = null
    )
    private data class NewTvPostResponse(
        val title: String? = null,
        val main_id: String? = null,
        val desc: String? = null,
        val year: String? = null,
        val genre: String? = null,
        val cast: String? = null,
        val match: String? = null,
        val runtime: String? = null,
        val episodes: List<NewTvEpisode?>? = null,
        val season: List<Season>? = null,
        val nextPageSeason: String? = null,
        val nextPageShow: Int? = null,
        val type: String? = null,
        val main_poster: String? = null,
        val morelike_poster: String? = null,
        val ep_poster: String? = null,
        val suggest: List<Suggest>? = null
    )
    private data class NewTvEpisodesResponse(
        val episodes: List<NewTvEpisode>? = null,
        val nextPageShow: Int? = null
    )
    private data class NewTvEpisode(
        val id: String,
        val ep: String = "",
        val s: String = "",
        val t: String = "",
        val time: String = ""
    )
}
