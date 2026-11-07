package com.horis.cncverse

import android.content.Context

class NetflixMirrorProvider : NewTvBaseProvider(
    name = "Netflix",
    ott = "nf"
) {
    companion object {
        var context: Context? = null
    }
}
