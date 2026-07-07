package com.horis.cncverse

import com.horis.cncverse.entities.EpisodesData
import com.horis.cncverse.entities.PostData
import com.horis.cncverse.entities.SearchData
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.APIHolder.unixTime

class NetflixMirrorProvider : MainAPI() {

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "hi"
    override var mainUrl = "https://net52.cc"
    override var name = "Netflix"
    override val hasMainPage = true

    private var cookieValue = ""
    private val bypassUrl = "https://net22.cc"

    private val siteHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "Cache-Control" to "max-age=0",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"
    )

    private val net27Headers = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "https://net27.cc/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
    )

    private suspend fun getCookie(): String {
        if (cookieValue.isEmpty()) cookieValue = bypass(bypassUrl)
        return cookieValue
    }

    private fun siteCookies() = mapOf(
        "t_hash_t" to cookieValue,
        "ott" to "nf",
        "hd" to "on"
    )


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        getCookie()
        val document = app.get(
            "$mainUrl/mobile/home?app=1",
            cookies = siteCookies(),
            headers = siteHeaders,
            referer = "$mainUrl/mobile/home?app=1"
        ).document
        val items = document.select(".tray-container, #top10").map { it.toHomePageList() }
        return newHomePageResponse(items, false)
    }

    private fun Element.toHomePageList(): HomePageList {
        val name = select("h2, span").text()
        val items = select("article, .top10-post").mapNotNull { it.toSearchResult() }
        return HomePageList(name, items, isHorizontalImages = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val id = selectFirst("a")?.attr("data-post") ?: attr("data-post")
        if (id.isNullOrBlank()) return null
        return newAnimeSearchResponse("", Id(id).toJson()) {
            posterUrl = "https://imgcdn.kim/poster/v/$id.jpg"
            posterHeaders = mapOf("Referer" to "$mainUrl/home")
        }
    }


    override suspend fun search(query: String): List<SearchResponse> {
        getCookie()
        val data = app.get(
            "$mainUrl/mobile/search.php?s=$query&t=$unixTime",
            referer = "$mainUrl/home",
            cookies = siteCookies()
        ).parsed<SearchData>()
        return data.searchResult.map {
            newAnimeSearchResponse(it.t, Id(it.id).toJson()) {
                posterUrl = "https://imgcdn.kim/poster/v/${it.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/home")
            }
        }
    }


    override suspend fun load(url: String): LoadResponse? {
        getCookie()
        val id = parseJson<Id>(url).id
        val data = app.get(
            "$mainUrl/mobile/post.php?id=$id&t=$unixTime",
            siteHeaders,
            referer = "$mainUrl/home",
            cookies = siteCookies()
        ).parsed<PostData>()

        val title = data.title
        val tmdbId = data.tmdb_id
        val episodes = arrayListOf<Episode>()

        val isMovie = data.episodes.isEmpty() || data.episodes.first() == null

        if (isMovie) {
            episodes.add(newEpisode(LoadData(title, id, tmdbId)) {
                name = title
            })
        } else {
            data.episodes.filterNotNull().mapTo(episodes) {
                newEpisode(LoadData(title, it.id, tmdbId, it.s.replace("S","").toIntOrNull(), it.ep.replace("E","").toIntOrNull())) {
                    this.name = it.t
                    this.episode = it.ep.replace("E", "").toIntOrNull()
                    this.season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/poster/v/150/${it.id}.jpg"
                    this.runTime = it.time.replace("m", "").toIntOrNull()
                }
            }
            if (data.nextPageShow == 1) {
                episodes.addAll(getEpisodes(title, url, data.nextPageSeason!!, 2, tmdbId))
            }
            data.season?.dropLast(1)?.amap {
                episodes.addAll(getEpisodes(title, url, it.id, 1, tmdbId))
            }
        }

        val cast = data.cast?.split(",")?.map { it.trim() }
            ?.filter { it.isNotEmpty() }?.map { ActorData(Actor(it)) }
        val genre = data.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val type = if (isMovie) TvType.Movie else TvType.TvSeries

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            posterUrl = "https://imgcdn.kim/poster/v/$id.jpg"
            backgroundPosterUrl = "https://imgcdn.kim/poster/v/$id.jpg"
            posterHeaders = mapOf("Referer" to "$mainUrl/home")
            plot = data.desc
            year = data.year.toIntOrNull()
            tags = genre
            actors = cast
            this.score = Score.from10(data.match?.replace("IMDb ", ""))
            this.duration = convertRuntimeToMinutes(data.runtime.toString())
            this.contentRating = data.ua
        }
    }

    private suspend fun getEpisodes(
        title: String, eid: String, sid: String, page: Int, tmdbId: String?
    ): List<Episode> {
        val episodes = arrayListOf<Episode>()
        var pg = page
        while (true) {
            val data = app.get(
                "$mainUrl/mobile/episodes.php?s=$sid&series=$eid&t=$unixTime&page=$pg",
                siteHeaders,
                referer = "$mainUrl/home",
                cookies = siteCookies()
            ).parsed<EpisodesData>()
            data.episodes?.mapTo(episodes) {
                newEpisode(LoadData(title, it.id, tmdbId, it.s.replace("S","").toIntOrNull(), it.ep.replace("E","").toIntOrNull())) {
                    name = it.t
                    episode = it.ep.replace("E", "").toIntOrNull()
                    season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/epimg/150/${it.id}.jpg"
                    this.runTime = it.time.replace("m", "").toIntOrNull()
                }
            }
            if (data.nextPageShow == 0) break
            pg++
        }
        return episodes
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LoadData>(data)
        val tmdbId = loadData.tmdbId ?: return false

        val apiUrl = if (loadData.season == null) {
            "https://net27.cc/api/embed-tmdb/$tmdbId"
        } else {
            "https://net27.cc/api/embed-tmdb/$tmdbId?type=tv&s=${loadData.season}&e=${loadData.episode}"
        }

        val response = app.get(apiUrl, headers = net27Headers).parsed<Net27Response>()
        if (response.ok != true) return false

        // All quality streams
        val streams = response.streams
        if (!streams.isNullOrEmpty()) {
            streams.forEach { stream ->
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name ${stream.resolution}p",
                        stream.url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://videodownloader.site/"
                        this.quality = stream.resolution
                    }
                )
            }
        } else {
            // Fallback to single mp4
            val mp4 = response.mp4 ?: return false
            callback.invoke(
                newExtractorLink(name, name, mp4, type = ExtractorLinkType.VIDEO) {
                    this.referer = "https://videodownloader.site/"
                }
            )
        }

        // Subtitles
        response.captions?.forEach { caption ->
            subtitleCallback.invoke(
                SubtitleFile(caption.name, "https://net27.cc${caption.url}")
            )
        }

        return true
    }


    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request().newBuilder()
                    .header("Referer", "https://videodownloader.site/")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
                    )
                    .build()
                return chain.proceed(request)
            }
        }
    }


    data class Id(val id: String)

    data class LoadData(
        val title: String,
        val id: String,
        val tmdbId: String? = null,
        val season: Int? = null,
        val episode: Int? = null
    )

    data class Net27Response(
        val ok: Boolean? = null,
        val mp4: String? = null,
        val streams: List<Net27Stream>? = null,
        val captions: List<Net27Caption>? = null
    )

    data class Net27Stream(
        val url: String,
        val resolution: Int
    )

    data class Net27Caption(
        val lang: String,
        val name: String,
        val url: String
    )
}