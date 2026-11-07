package com.horis.cncverse

import com.horis.cncverse.entities.SearchResult

data class NewTvId(val id: String)
data class NewTvLoadData(val title: String, val id: String)
data class Suggest(val id: String)

data class NewTvMainResponse(
    val post: List<NewTvPostCategory>? = null,
    val imgcdn_h: String? = null,
    val imgcdn_v: String? = null,
    val img_referer: String? = null
)

data class NewTvPostCategory(
    val ids: String? = null,
    val cate: String? = null,
    val row: String? = null
)

data class NewTvSearchResponse(
    val searchResult: List<SearchResult>? = null,
    val detailsimgcdn: String? = null,
    val imgcdn: String? = null,
    val img_referer: String? = null
)

data class MoreDetail(
    val k: String? = null,
    val v: String? = null
)

data class NewTvSeason(
    val id: String = "",
    val s: String? = null,
    val selected: Boolean? = false
)

data class NewTvEpisode(
    val id: String = "",
    val ep: String = "",
    val s: String = "",
    val t: String = "",
    val time: String = "",
    val info: List<String>? = null,
    val ep_desc: String? = null
)

data class NewTvPostResponse(
    val title: String? = null,
    val title_name: String? = null,
    val main_id: String? = null,
    val desc: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val cast: String? = null,
    val match: String? = null,
    val runtime: String? = null,
    val episodes: List<NewTvEpisode?>? = null,
    val season: List<NewTvSeason>? = null,
    val nextPageSeason: String? = null,
    val nextPageShow: Int? = null,
    val type: String? = null,
    val main_poster: String? = null,
    val morelike_poster: String? = null,
    val ep_poster: String? = null,
    val suggest: List<Suggest>? = null,
    val moredetails: List<MoreDetail>? = null
)

data class NewTvEpisodesResponse(
    val episodes: List<NewTvEpisode>? = null,
    val nextPageShow: Int? = null
)
