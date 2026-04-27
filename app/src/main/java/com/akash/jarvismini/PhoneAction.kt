package com.akash.jarvismini

import org.json.JSONObject

sealed class PhoneAction {
    data class SpotifyPlay(val uri: String) : PhoneAction()
    data object SpotifyPause : PhoneAction()
    data object SpotifyResume : PhoneAction()
    data object SpotifySkipNext : PhoneAction()
    data object SpotifySkipPrevious : PhoneAction()
    data class Unknown(val type: String) : PhoneAction()

    companion object {
        fun fromJson(json: JSONObject): PhoneAction {
            return when (val type = json.optString("type")) {
                "spotify_play" -> SpotifyPlay(json.getString("uri"))
                "spotify_pause" -> SpotifyPause
                "spotify_resume" -> SpotifyResume
                "spotify_skip_next" -> SpotifySkipNext
                "spotify_skip_previous" -> SpotifySkipPrevious
                else -> Unknown(type)
            }
        }
    }
}
