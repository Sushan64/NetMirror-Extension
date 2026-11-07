package com.horis.cncverse

import android.content.Context

class HotStarMirrorProvider : NewTvBaseProvider(
    name = "Hotstar",
    ott = "hs"
) {
    companion object {
        var context: Context? = null
    }
}
