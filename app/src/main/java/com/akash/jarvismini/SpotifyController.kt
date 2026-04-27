package com.akash.jarvismini

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.KeyEvent

// Spotify control without the abandoned App Remote SDK:
// - "play this URI" via ACTION_VIEW intent → Spotify foregrounds briefly, plays
// - pause/skip/resume via ACTION_MEDIA_BUTTON broadcast targeted at Spotify
//
// Tradeoff vs App Remote: we can't query "what's playing now". For Phase 6
// we don't need that — the LLM tells us what to play, the phone tells Spotify.
private const val TAG = "SpotifyController"
private const val SPOTIFY_PACKAGE = "com.spotify.music"

class SpotifyController(private val context: Context) {

    fun dispatch(action: PhoneAction) {
        Log.d(TAG, "dispatch: $action")
        when (action) {
            is PhoneAction.SpotifyPlay -> playUri(action.uri)
            PhoneAction.SpotifyPause -> sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            PhoneAction.SpotifyResume -> sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            PhoneAction.SpotifySkipNext -> sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            PhoneAction.SpotifySkipPrevious -> sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            is PhoneAction.Unknown -> Log.w(TAG, "unknown phone action: ${action.type}")
        }
    }

    private fun playUri(uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(SPOTIFY_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Spotify app not installed", e)
        }
    }

    private fun sendKey(keyCode: Int) {
        // Media key handlers expect a full press: ACTION_DOWN then ACTION_UP.
        broadcastKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        broadcastKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun broadcastKeyEvent(event: KeyEvent) {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            setPackage(SPOTIFY_PACKAGE)
            putExtra(Intent.EXTRA_KEY_EVENT, event)
        }
        context.sendBroadcast(intent)
    }

    fun shutdown() {
        // No-op now — nothing to release.
    }
}
