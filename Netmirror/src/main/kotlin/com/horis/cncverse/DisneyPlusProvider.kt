package com.horis.cncverse

import android.content.Context

class DisneyPlusProvider : NewTvBaseProvider(
    name = "Disney Plus",
    ott = "hs",
    categoryFilter = { cate ->
        val lower = cate.lowercase()
        lower.contains("disney") ||
        lower.contains("marvel") ||
        lower.contains("mcu") ||
        lower.contains("star wars") ||
        lower.contains("toy story") ||
        lower.contains("shorts") ||
        lower.contains("vault") ||
        lower.contains("heroes")
    }
) {
    companion object {
        var context: Context? = null
    }
}
