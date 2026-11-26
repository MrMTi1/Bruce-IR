package com.example.bruceir

import android.content.Context
import android.media.MediaPlayer
import android.util.Log

object ThemeSoundController {
    private var player: MediaPlayer? = null
    private var currentTrackResId: Int = 0

    @Synchronized
    fun play(context: Context) {
        try {
            val rawTracks = listOf(R.raw.theme_sound, R.raw.theme_sound_keygen)
            val chosenTrack = rawTracks.random()

            if (player != null && player?.isPlaying == true && currentTrackResId == chosenTrack) {
                return
            }

            player?.release()
            player = null

            val active = MediaPlayer.create(context.applicationContext, chosenTrack)?.also {
                it.isLooping = true
                it.setVolume(0.4f, 0.4f)
                player = it
                currentTrackResId = chosenTrack
            } ?: return
            active.start()
        } catch (error: Exception) {
            Log.e("BruceIR", "Theme sound error: ${error.message}")
        }
    }

    @Synchronized
    fun pause() {
        try {
            player?.takeIf { it.isPlaying }?.pause()
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun release() {
        try {
            player?.release()
        } catch (_: Exception) {
        } finally {
            player = null
        }
    }
}
