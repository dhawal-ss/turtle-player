package app.turtleplayer.events

/**
 * Export files reduced from real ones: every key the real formats carry, with identifying values
 * (IP address, country, URIs) replaced by dummies. commonTest has no resource loading, so they live
 * here as text.
 */
object ImportFixtures {
    /** Spotify Extended Streaming History (`Streaming_History_Audio_*.json`): two songs, a podcast, a re-upload. */
    val extended = """
        [
          {"ts":"2024-03-01T20:03:30Z","platform":"android","ms_played":210000,"conn_country":"ZZ","ip_addr":"0.0.0.0",
           "master_metadata_track_name":"Blinding Lights","master_metadata_album_artist_name":"The Weeknd",
           "master_metadata_album_album_name":"After Hours","spotify_track_uri":"spotify:track:0000000000000000000001",
           "episode_name":null,"episode_show_name":null,"spotify_episode_uri":null,"audiobook_title":null,
           "audiobook_uri":null,"audiobook_chapter_uri":null,"audiobook_chapter_title":null,
           "reason_start":"clickrow","reason_end":"trackdone","shuffle":false,"skipped":false,"offline":false,
           "offline_timestamp":null,"incognito_mode":false},
          {"ts":"2024-03-01T20:03:42Z","platform":"android","ms_played":12000,"conn_country":"ZZ","ip_addr":"0.0.0.0",
           "master_metadata_track_name":"Save Your Tears","master_metadata_album_artist_name":"The Weeknd",
           "master_metadata_album_album_name":"After Hours","spotify_track_uri":"spotify:track:0000000000000000000002",
           "episode_name":null,"episode_show_name":null,"spotify_episode_uri":null,"audiobook_title":null,
           "audiobook_uri":null,"audiobook_chapter_uri":null,"audiobook_chapter_title":null,
           "reason_start":"trackdone","reason_end":"fwdbtn","shuffle":false,"skipped":true,"offline":false,
           "offline_timestamp":null,"incognito_mode":false},
          {"ts":"2024-03-01T20:40:00Z","platform":"android","ms_played":1800000,"conn_country":"ZZ","ip_addr":"0.0.0.0",
           "master_metadata_track_name":null,"master_metadata_album_artist_name":null,
           "master_metadata_album_album_name":null,"spotify_track_uri":null,
           "episode_name":"Some Podcast Episode","episode_show_name":"Some Show","spotify_episode_uri":"spotify:episode:0000000000000000000003",
           "audiobook_title":null,"audiobook_uri":null,"audiobook_chapter_uri":null,"audiobook_chapter_title":null,
           "reason_start":"clickrow","reason_end":"endplay","shuffle":false,"skipped":false,"offline":false,
           "offline_timestamp":null,"incognito_mode":false},
          {"ts":"2024-03-02T09:00:00Z","platform":"windows","ms_played":180000,"conn_country":"ZZ","ip_addr":"0.0.0.0",
           "master_metadata_track_name":"Blinding Lights (Official Audio)","master_metadata_album_artist_name":"The Weeknd",
           "master_metadata_album_album_name":null,"spotify_track_uri":"spotify:track:0000000000000000000004",
           "episode_name":null,"episode_show_name":null,"spotify_episode_uri":null,"audiobook_title":null,
           "audiobook_uri":null,"audiobook_chapter_uri":null,"audiobook_chapter_title":null,
           "reason_start":"clickrow","reason_end":"trackdone","shuffle":true,"skipped":false,"offline":true,
           "offline_timestamp":1709370000,"incognito_mode":false}
        ]
    """.trimIndent()

    /** One Extended row from a private session. */
    val extendedPrivate = """
        [
          {"ts":"2024-03-03T22:00:00Z","platform":"android","ms_played":200000,"conn_country":"ZZ","ip_addr":"0.0.0.0",
           "master_metadata_track_name":"Secret Song","master_metadata_album_artist_name":"Hidden Artist",
           "master_metadata_album_album_name":"Shh","spotify_track_uri":"spotify:track:0000000000000000000005",
           "episode_name":null,"episode_show_name":null,"spotify_episode_uri":null,"audiobook_title":null,
           "audiobook_uri":null,"audiobook_chapter_uri":null,"audiobook_chapter_title":null,
           "reason_start":"clickrow","reason_end":"trackdone","shuffle":false,"skipped":false,"offline":false,
           "offline_timestamp":null,"incognito_mode":true}
        ]
    """.trimIndent()

    /** One Extended row whose time does not parse, one audiobook chapter, one row without names. */
    val extendedOdd = """
        [
          {"ts":"yesterday","ms_played":200000,"master_metadata_track_name":"Some Song",
           "master_metadata_album_artist_name":"Some Artist","reason_start":"clickrow","reason_end":"trackdone"},
          {"ts":"2024-03-04T10:00:00Z","ms_played":600000,"master_metadata_track_name":null,
           "master_metadata_album_artist_name":null,"audiobook_title":"Some Audiobook",
           "audiobook_uri":"spotify:show:0000000000000000000006","audiobook_chapter_title":"Chapter 1",
           "reason_start":"clickrow","reason_end":"endplay"},
          {"ts":"2024-03-04T11:00:00Z","ms_played":90000,"master_metadata_track_name":null,
           "master_metadata_album_artist_name":null,"spotify_track_uri":null,"episode_name":null,
           "reason_start":"clickrow","reason_end":"endplay"}
        ]
    """.trimIndent()

    /** Spotify Account data (`StreamingHistory_music_*.json`) for a year after the Extended history. */
    val account = """
        [
          {"endTime":"2024-05-01 21:14","artistName":"Kino","trackName":"Группа крови","msPlayed":250000},
          {"endTime":"2024-05-01 21:20","artistName":"Unknown Artist","trackName":"Unknown Track","msPlayed":1000}
        ]
    """.trimIndent()

    /** Account data for a play the Extended history already holds (minute precision). */
    val accountInsideExtended = """
        [{"endTime":"2024-03-01 20:03","artistName":"The Weeknd","trackName":"Blinding Lights","msPlayed":210000}]
    """.trimIndent()

    /** ListenBrainz "Export listens" JSON Lines. */
    val listenBrainz = """
        {"listened_at":1700000000,"track_metadata":{"artist_name":"Radiohead","track_name":"Reckoner","release_name":"In Rainbows","additional_info":{"duration_ms":290000},"mbid_mapping":{"recording_mbid":"ABC-123"}}}
        {"listened_at":1700000400,"track_metadata":{"artist_name":"Radiohead","track_name":"Nude"}}
        {"listened_at":1700000400,"track_metadata":{"artist_name":"Radiohead","track_name":"Nude"}}
    """.trimIndent()
}
