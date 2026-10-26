package dev.solon.bucklerhome

/** What is playing, which decides how the Now Playing screensaver lays it out. */
enum class MediaKind {
    /** Square album art with title, artist and album. */
    MUSIC,

    /** Full-screen backdrop for films and TV episodes. */
    VIDEO,

    /** 16:9 thumbnail with the channel name. */
    YOUTUBE,

    /** Live stream: thumbnail, streamer and a LIVE badge. */
    TWITCH,

    /** IPTV / live channels: channel logo, programme and a LIVE badge. */
    LIVE_TV,
}

object MediaKinds {
    private val YOUTUBE = setOf(
        "com.google.android.youtube.tv",
        "com.google.android.youtube.tvkids",
        "com.google.android.youtube",
        "com.liskovsoft.smarttubetv",
        "com.liskovsoft.smarttubetv.beta",
        "com.teamsmart.videomanager.tv",
    )
    private val TWITCH = setOf("tv.twitch.android.app", "tv.twitch.android.viewer")
    private val LIVE_TV = setOf(
        "ar.tvplayer.tv",
        "ar.tvplayer.companion",
        "com.google.android.tv",
        "com.android.tv",
        "com.google.android.youtube.tvunplugged",
        "se.hedekonsult.sparkle",
        "com.niklabs.pp",
        "com.ottplay.ottplay",
    )
    private val MUSIC = setOf(
        "com.spotify.tv.android",
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.amazon.music.tv",
        "com.amazon.mp3",
        "com.aspiro.tidal",
        "deezer.android.tv",
        "com.apple.android.music",
        "com.pandora.android.atv",
        "com.soundcloud.android",
        "tunein.player",
        "tv.plex.labs.plexamp",
    )

    /**
     * Known apps win; general players (Plex, Jellyfin, VLC, Kodi) play both, so for those the
     * metadata decides: an artist or album means music.
     */
    fun classify(packageName: String, hasArtist: Boolean, hasAlbum: Boolean): MediaKind = when {
        packageName in YOUTUBE -> MediaKind.YOUTUBE
        packageName in TWITCH -> MediaKind.TWITCH
        packageName in LIVE_TV -> MediaKind.LIVE_TV
        packageName in MUSIC -> MediaKind.MUSIC
        hasAlbum || hasArtist -> MediaKind.MUSIC
        else -> MediaKind.VIDEO
    }
}
