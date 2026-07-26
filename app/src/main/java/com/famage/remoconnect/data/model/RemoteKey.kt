package com.famage.remoconnect.data.model

enum class RemoteKey(
    val label: String,
    val androidKeycode: Int,
    val appPackage: String? = null,
    val appLink: String? = null
) {
    POWER("Power", 26),
    UP("Up", 19),
    DOWN("Down", 20),
    LEFT("Left", 21),
    RIGHT("Right", 22),
    ENTER_OK("OK", 66),
    BACK("Back", 4),
    HOME("Home", 3),
    MENU("Menu", 82),
    SETTINGS("Settings", 176, "com.android.tv.settings"),
    VOLUME_UP("Vol +", 24),
    VOLUME_DOWN("Vol -", 25),
    MUTE("Mute", 164),
    CHANNEL_UP("Ch +", 166),
    CHANNEL_DOWN("Ch -", 167),
    INPUT_SOURCE("Input", 178),
    VOICE_ASSISTANT("Voice", 231),

    // Media Controls
    PLAY_PAUSE("Play/Pause", 85),
    STOP("Stop", 86),
    FAST_FORWARD("FFWD", 90),
    REWIND("Rewind", 89),

    // App Shortcuts
    NETFLIX("Netflix", 0, "com.netflix.ninja"),
    YOUTUBE("YouTube", 0, "com.google.android.youtube.tv"),
    PRIME_VIDEO("Prime Video", 0, "com.amazon.amazonvideo.livingroom"),
    DISNEY_PLUS("Disney+", 0, "com.disney.disneyplus"),
    SPOTIFY("Spotify", 0, "com.spotify.tv.android"),
    GOOGLE_PLAY("Play Store", 0, "com.android.vending")
}
