package com.hoppe.applelocalplayer

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@UnstableApi
class MusicPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val transitionMs = 4_000L
    private var fadeStartedAt = 0L
    private var fadeStartVolume = 1f
    private var fadingOut = false
    private var lastMediaItem: MediaItem? = null

    private val transitionTicker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.isPlaying) {
                val duration = p.duration
                val position = p.currentPosition
                if (duration > 0L && duration != C.TIME_UNSET) {
                    val remaining = duration - position
                    if (!fadingOut && remaining in 1L..transitionMs) {
                        fadingOut = true
                        fadeStartedAt = SystemClock.elapsedRealtime()
                        fadeStartVolume = p.volume.coerceIn(0f, 1f)
                    }
                    if (fadingOut) {
                        val elapsed = (SystemClock.elapsedRealtime() - fadeStartedAt).coerceAtLeast(0L)
                        val fraction = (elapsed.toFloat() / transitionMs.toFloat()).coerceIn(0f, 1f)
                        p.volume = fadeStartVolume * (1f - fraction)
                    }
                }
            }
            handler.postDelayed(this, 100L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = exoPlayer
        lastMediaItem = exoPlayer.currentMediaItem
        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem != lastMediaItem) {
                    lastMediaItem = mediaItem
                    fadingOut = false
                    fadeStartedAt = SystemClock.elapsedRealtime()
                    fadeStartVolume = 0f
                    exoPlayer.volume = 0f
                    // Fade in the new track gradually; the outgoing track has already faded down.
                    handler.post(object : Runnable {
                        override fun run() {
                            if (player !== exoPlayer || exoPlayer.currentMediaItem != mediaItem) return
                            if (!exoPlayer.isPlaying) {
                                handler.postDelayed(this, 100L)
                                return
                            }
                            val elapsed = (SystemClock.elapsedRealtime() - fadeStartedAt).coerceAtLeast(0L)
                            val fraction = (elapsed.toFloat() / transitionMs.toFloat()).coerceIn(0f, 1f)
                            exoPlayer.volume = fraction
                            if (fraction < 1f) handler.postDelayed(this, 50L)
                            else exoPlayer.volume = 1f
                        }
                    })
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !fadingOut && exoPlayer.volume == 0f) {
                    exoPlayer.volume = 1f
                }
        })
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
        handler.post(transitionTicker)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
