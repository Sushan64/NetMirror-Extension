package com.horis.cncverse

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.Interceptor
import okhttp3.Response

class NetMirrorProvider : MainAPI() {
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override var mainUrl = com.cncverse.BuildConfig.TMDB_URL
    private val net27Url = "https://net27.cc"
    override var name = "NetMirror"
    override val hasMainPage = true

    private val tmdbApiKey = com.cncverse.BuildConfig.TMDB_API_KEY

    private val net27Headers = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "/3/trending/movie/day" to "Trending Movies",
        "/3/trending/tv/day" to "Trending TV Shows",
        "/3/movie/popular" to "Popular Movies",
        "/3/tv/popular" to "Popular TV Shows"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}?api_key=$tmdbApiKey&page=$page"
        val response = app.get(url).parsed<TmdbPageResponse>()
        
        val items = response.results?.mapNotNull { it.toSearchResult() } ?: emptyList()
        return newHomePageResponse(request.name, items, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/3/search/multi?api_key=$tmdbApiKey&query=$query"
        val response = app.get(url).parsed<TmdbPageResponse>()
        return response.results?.filter { it.media_type == "movie" || it.media_type == "tv" }?.mapNotNull { it.toSearchResult() } ?: emptyList()
    }

    private fun TmdbResult.toSearchResult(): SearchResponse? {
        val title = this.title ?: this.name ?: return null
        val tmdbId = this.id?.toString() ?: return null
        val isTv = this.media_type == "tv" || this.name != null && this.title == null // fallback check
        val poster = this.poster_path?.let { "$mainUrl/t/p/w500$it" }

        val data = TmdbLoadData(title, tmdbId, isTv).toJson()
        return if (isTv) {
            newTvSeriesSearchResponse(title, data, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, data, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = try { parseJson<TmdbLoadData>(url) } catch(e: Exception) { null } ?: return null
        val isTv = data.isTv
        val typeStr = if (isTv) "tv" else "movie"
        val detailsUrl = "$mainUrl/3/$typeStr/${data.tmdbId}?api_key=$tmdbApiKey&append_to_response=credits"
        
        val details = app.get(detailsUrl).parsed<TmdbDetails>()
        val title = details.title ?: details.name ?: data.title
        val poster = details.poster_path?.let { "$mainUrl/t/p/w500$it" }
        val background = details.backdrop_path?.let { "$mainUrl/t/p/original$it" }
        
        val plot = details.overview
        val year = (details.release_date ?: details.first_air_date)?.take(4)?.toIntOrNull()
        val rating = details.vote_average?.times(10)?.toInt()
        
        val actors = details.credits?.cast?.mapNotNull { cast ->
            cast.name?.let { ActorData(Actor(it)) }
        }

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            val seasons = details.seasons?.filter { it.season_number != null && it.season_number > 0 } ?: emptyList()
            
            for (season in seasons) {
                val sNum = season.season_number!!
                val seasonUrl = "$mainUrl/3/tv/${data.tmdbId}/season/$sNum?api_key=$tmdbApiKey"
                val seasonDetails = try {
                    app.get(seasonUrl).parsed<TmdbSeason>()
                } catch(e: Exception) { null }
                
                seasonDetails?.episodes?.forEach { ep ->
                    val epNum = ep.episode_number ?: return@forEach
                    val epData = TmdbLoadData(title, data.tmdbId, true, sNum, epNum).toJson()
                    episodes.add(newEpisode(epData) {
                        this.name = ep.name
                        this.season = sNum
                        this.episode = epNum
                        this.description = ep.overview
                        this.posterUrl = ep.still_path?.let { "$mainUrl/t/p/w500$it" }
                    })
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.actors = actors
            }
        } else {
            val movieData = TmdbLoadData(title, data.tmdbId, false).toJson()
            return newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.actors = actors
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<TmdbLoadData>(data)
        val tmdbId = loadData.tmdbId
        val isTv = loadData.isTv

        val variantsUrl = if (!isTv) {
            "$net27Url/api/variants-tmdb/movie/$tmdbId"
        } else {
            "$net27Url/api/variants-tmdb/tv/$tmdbId?se=${loadData.season}&ep=${loadData.episode ?: 1}"
        }

        val variantsRes = try {
            app.get(variantsUrl, headers = net27Headers).parsed<Net27VariantsResponse>()
        } catch (e: Exception) { return false }

        if (variantsRes.ok != true) return false
        
        val defaultSid = variantsRes.defaultSubjectId
        val defaultDp = variantsRes.defaultDetailPath

        // Extract available dubs
        val dubVariants = variantsRes.variants ?: emptyList()
        val processedDubs = mutableSetOf<String>()

        // 1. First fetch default
        if (defaultSid != null && defaultDp != null) {
            fetchNet27Embed(tmdbId, isTv, loadData.season, loadData.episode, defaultSid, defaultDp, "Default", subtitleCallback, callback)
        } else {
            fetchNet27Embed(tmdbId, isTv, loadData.season, loadData.episode, null, null, "Default", subtitleCallback, callback)
        }

        // 2. Fetch specific dubs via aoneroom if available
        if (defaultDp != null && dubVariants.isNotEmpty()) {
            val detailUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/detail?detailPath=$defaultDp"
            try {
                val detailRes = app.get(detailUrl, headers = mapOf("Accept" to "application/json")).parsed<AoneRoomResponse>()
                val dubs = detailRes.data?.subject?.dubs ?: emptyList()
                
                for (v in dubVariants) {
                    val lang = v.language ?: continue
                    val dubSid = v.dubSubjectId ?: continue
                    
                    if (processedDubs.contains(lang)) continue
                    processedDubs.add(lang)
                    
                    val dubDp = dubs.find { it.subjectId == dubSid }?.detailPath
                    if (dubDp != null && defaultSid != null) {
                        fetchNet27Embed(tmdbId, isTv, loadData.season, loadData.episode, dubSid, dubDp, lang, subtitleCallback, callback, defaultSid, defaultDp)
                    }
                }
            } catch (e: Exception) {
                // Ignore aoneroom errors and fallback to default
            }
        }
        
        return true
    }
    
    private suspend fun fetchNet27Embed(
        tmdbId: String,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        sid: String?,
        dp: String?,
        audioLang: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        defaultSid: String? = null,
        defaultDp: String? = null
    ) {
        val typeStr = if (isTv) "tv" else "movie"
        var embedUrl = "$net27Url/api/embed-tmdb/$tmdbId?type=$typeStr"
        if (isTv) {
            embedUrl += "&se=${season ?: 1}&ep=${episode ?: 1}"
        }
        if (sid != null && dp != null) {
            if (defaultSid != null && defaultDp != null) {
                // For dubs: dub=dubSid, dubdp=dubDp, sid=defaultSid, dp=defaultDp
                embedUrl += "&dub=$sid&dubdp=$dp&sid=$defaultSid&dp=$defaultDp"
            } else {
                embedUrl += "&sid=$sid&dp=$dp"
            }
        }

        val response = try {
            app.get(embedUrl, headers = net27Headers).parsed<Net27Response>()
        } catch (e: Exception) { return }

        if (response.ok != true) return

        response.streams?.sortedByDescending { it.resolution }?.forEach { stream ->
            callback.invoke(
                newExtractorLink(name, "$name $audioLang", stream.url, type = ExtractorLinkType.VIDEO) {
                    this.referer = "https://videodownloader.site/"
                    this.quality = stream.resolution
                }
            )
        }

        if (response.streams.isNullOrEmpty()) {
            val mp4 = response.mp4
            if (!mp4.isNullOrBlank()) {
                val resolution = response.resolution?.toString() ?: "Unknown"
                callback.invoke(
                    newExtractorLink(name, "$name $audioLang", mp4, type = ExtractorLinkType.VIDEO) {
                        this.referer = "https://videodownloader.site/"
                    }
                )
            }
        }

        response.captions?.forEach { caption ->
            val subUrl = if (caption.url.startsWith("/")) "$net27Url${caption.url}" else caption.url
            val lang = caption.name ?: caption.lang ?: "Unknown"
            subtitleCallback.invoke(SubtitleFile(lang, subUrl))
        }
    }

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request().newBuilder()
                    .header("Referer", "https://videodownloader.site/")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36")
                    .build()
                return chain.proceed(request)
            }
        }
    }

    data class TmdbLoadData(
        val title: String,
        val tmdbId: String,
        val isTv: Boolean,
        val season: Int? = null,
        val episode: Int? = null
    )

    data class TmdbPageResponse(val results: List<TmdbResult>?)
    data class TmdbResult(
        val id: Int?, 
        val title: String?, 
        val name: String?, 
        val media_type: String?, 
        val poster_path: String?
    )
    
    data class TmdbDetails(
        val id: Int?,
        val title: String?,
        val name: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val overview: String?,
        val release_date: String?,
        val first_air_date: String?,
        val vote_average: Double?,
        val credits: TmdbCredits?,
        val seasons: List<TmdbSeasonLite>?
    )

    data class TmdbCredits(val cast: List<TmdbCast>?)
    data class TmdbCast(val name: String?)
    data class TmdbSeasonLite(val season_number: Int?, val episode_count: Int?)
    
    data class TmdbSeason(val episodes: List<TmdbEpisode>?)
    data class TmdbEpisode(
        val episode_number: Int?,
        val name: String?,
        val overview: String?,
        val still_path: String?
    )

    data class Net27VariantsResponse(
        val ok: Boolean? = null,
        val defaultSubjectId: String? = null,
        val defaultDetailPath: String? = null,
        val variants: List<Net27Variant>? = null
    )
    
    data class Net27Variant(
        val language: String?,
        val dubSubjectId: String?
    )

    data class AoneRoomResponse(val data: AoneRoomData?)
    data class AoneRoomData(val subject: AoneRoomSubject?)
    data class AoneRoomSubject(val dubs: List<AoneRoomDub>?)
    data class AoneRoomDub(val subjectId: String?, val detailPath: String?)

    data class Net27Response(
        val ok: Boolean? = null,
        val mp4: String? = null,
        val resolution: Any? = null,
        val streams: List<Net27Stream>? = null,
        val captions: List<Net27Caption>? = null
    )

    data class Net27Stream(val url: String, val resolution: Int)
    data class Net27Caption(val lang: String?, val name: String?, val url: String)
}
