// use an integer for version numbers
version = 42

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    
    language = "hi"
    // All of these properties are optional, you can safely remove them

    description = "Netflix, PrimeVideo, Disney+ Hotstar Contents in Multiple Languages"
    authors = listOf("Sushan64")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    iconUrl = "https://raw.githubusercontent.com/Sushan64/NetMirror-Extension/refs/heads/master/Netmirror/logo.jpeg"
}
