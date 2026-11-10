package com.horis.cncverse

import android.content.Context

class PrimeVideoMirrorProvider : NewTvBaseProvider(
    name = "Prime Video",
    ott = "pv"
) {
    companion object {
        var context: Context? = null
    }
}
