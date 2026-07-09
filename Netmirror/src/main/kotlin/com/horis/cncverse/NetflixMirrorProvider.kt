package com.horis.cncverse

import android.content.Context
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
    companion object {
        var context: Context? = null
    }

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "hi"
    override var mainUrl = "https://net52.cc"
    private val newUrl = "https://net22.cc"
    private val net27Url = "https://net27.cc"
    override var name = "Netflix"
    override val hasMainPage = true
    private var cookie_value = ""

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "Cache-Control" to "max-age=0",
        "Connection" to "keep-alive",
        "sec-ch-ua" to "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Android WebView\";v=\"144\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 Safari/537.36 /OS.Gatu v3.0",
        "X-Requested-With" to "XMLHttpRequest"
    )

    private val net27Headers = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        cookie_value = if (cookie_value.isEmpty()) bypass(newUrl) else cookie_value
        val cookies = mapOf("t_hash_t" to cookie_value, "ott" to "nf", "hd" to "on")
        val document = app.get(
            "$mainUrl/mobile/home?app=1",
            cookies = cookies,
            headers = headers,
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
        cookie_value = if (cookie_value.isEmpty()) bypass(newUrl) else cookie_value
        val cookies = mapOf("t_hash_t" to cookie_value, "hd" to "on", "ott" to "nf")
        val data = app.get(
            "$mainUrl/mobile/search.php?s=$query&t=$unixTime",
            referer = "$mainUrl/home",
            cookies = cookies
        ).parsed<SearchData>()
        return data.searchResult.map {
            newAnimeSearchResponse(it.t, Id(it.id).toJson()) {
                posterUrl = "https://imgcdn.kim/poster/v/${it.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/home")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        cookie_value = if (cookie_value.isEmpty()) bypass(newUrl) else cookie_value
        val id = parseJson<Id>(url).id
        val cookies = mapOf("t_hash_t" to cookie_value, "hd" to "on", "ott" to "nf")
        val data = app.get(
            "$mainUrl/mobile/post.php?id=$id&t=$unixTime",
            headers,
            referer = "$mainUrl/home",
            cookies = cookies
        ).parsed<PostData>()

        val title = data.title
        val episodes = arrayListOf<Episode>()
        val isMovie = data.episodes.isEmpty() || data.episodes.first() == null

        if (isMovie) {
            episodes.add(newEpisode(LoadData(title, id)) {
                name = title
            })
        } else {
            data.episodes.filterNotNull().mapTo(episodes) {
                newEpisode(LoadData(
                    title, it.id,
                    it.s.replace("S", "").toIntOrNull(),
                    it.ep.replace("E", "").toIntOrNull()
                )) {
                    this.name = it.t
                    this.episode = it.ep.replace("E", "").toIntOrNull()
                    this.season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/poster/v/150/${it.id}.jpg"
                    this.runTime = it.time.replace("m", "").toIntOrNull()
                }
            }
            if (data.nextPageShow == 1) {
                episodes.addAll(getEpisodes(title, url, data.nextPageSeason!!, 2))
            }
            data.season?.dropLast(1)?.amap {
                episodes.addAll(getEpisodes(title, url, it.id, 1))
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
        title: String, eid: String, sid: String, page: Int
    ): List<Episode> {
        val episodes = arrayListOf<Episode>()
        val cookies = mapOf("t_hash_t" to cookie_value, "hd" to "on", "ott" to "nf")
        var pg = page
        while (true) {
            val data = app.get(
                "$mainUrl/mobile/episodes.php?s=$sid&series=$eid&t=$unixTime&page=$pg",
                headers,
                referer = "$mainUrl/home",
                cookies = cookies
            ).parsed<EpisodesData>()
            data.episodes?.mapTo(episodes) {
                newEpisode(LoadData(
                    title, it.id,
                    it.s.replace("S", "").toIntOrNull(),
                    it.ep.replace("E", "").toIntOrNull()
                )) {
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
        val isMovie = loadData.season == null

        // Use CloudStream's built-in TMDB tracker
        val trackerType = if (isMovie) TrackerType.TMDB_ID else TrackerType.TMDB_ID
        val trackerInfo = APIHolder.getTracker(
            loadData.title,
            trackerType,
            null,
            isMovie
        )
        val tmdbId = trackerInfo?.id ?: return false

        // Get variants
        val variantsUrl = if (isMovie) {
            "$net27Url/api/variants-tmdb/movie/$tmdbId"
        } else {
            "$net27Url/api/variants-tmdb/tv/$tmdbId?se=${loadData.season}&ep=${loadData.episode ?: 1}"
        }
        val variants = try {
            app.get(variantsUrl, headers = net27Headers).parsed<Net27VariantsResponse>()
        } catch (e: Exception) {
            Net27VariantsResponse()
        }

        // Build embed URL
        val hasSid = variants.ok == true && variants.defaultSubjectId != null
        val embedUrl = if (isMovie) {
            if (hasSid) "$net27Url/api/embed-tmdb/$tmdbId?type=movie&sid=${variants.defaultSubjectId}&dp=${variants.defaultDetailPath}"
            else "$net27Url/api/embed-tmdb/$tmdbId?type=movie"
        } else {
            val se = loadData.season ?: 1
            val ep = loadData.episode ?: 1
            if (hasSid) "$net27Url/api/embed-tmdb/$tmdbId?type=tv&se=$se&ep=$ep&sid=${variants.defaultSubjectId}&dp=${variants.defaultDetailPath}"
            else "$net27Url/api/embed-tmdb/$tmdbId?type=tv&se=$se&ep=$ep"
        }

        val response = app.get(embedUrl, headers = net27Headers).parsed<Net27Response>()
        if (response.ok != true || (response.mp4.isNullOrBlank() && response.streams.isNullOrEmpty())) return false

        val streams = response.streams
        if (!streams.isNullOrEmpty()) {
            streams.sortedByDescending { it.resolution }.forEach { stream ->
                callback.invoke(
                    newExtractorLink(name, "$name ${stream.resolution}p", stream.url, type = ExtractorLinkType.VIDEO) {
                        this.referer = "$net27Url/"
                        this.quality = stream.resolution
                    }
                )
            }
        } else {
            val mp4 = response.mp4 ?: return false
            callback.invoke(
                newExtractorLink(name, name, mp4, type = ExtractorLinkType.VIDEO) {
                    this.referer = "$net27Url/"
                }
            )
        }

        response.captions?.forEach { caption ->
            val subUrl = if (caption.url.startsWith("/")) "$net27Url${caption.url}" else caption.url
            subtitleCallback.invoke(SubtitleFile(caption.name, subUrl))
        }

        return true
    }

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request().newBuilder()
                    .header("Referer", "$net27Url/")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36")
                    .build()
                return chain.proceed(request)
            }
        }
    }

    data class Id(val id: String)

    data class LoadData(
        val title: String,
        val id: String,
        val season: Int? = null,
        val episode: Int? = null
    )

    data class Net27VariantsResponse(
        val ok: Boolean? = null,
        val defaultSubjectId: String? = null,
        val defaultDetailPath: String? = null
    )

    data class Net27Response(
        val ok: Boolean? = null,
        val mp4: String? = null,
        val streams: List<Net27Stream>? = null,
        val captions: List<Net27Caption>? = null
    )

    data class Net27Stream(val url: String, val resolution: Int)
    data class Net27Caption(val lang: String, val name: String, val url: String)
}