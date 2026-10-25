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
    private var firstPlayer: ExoPlayer? = null
    private var secondPlayer: ExoPlayer? = null
    private var firstPlayerListener: Player.Listener? = null
    private var secondPlayerListener: Player.Listener? = null
    private var activePlayer: ExoPlayer? = null
    private var standbyPlayer: ExoPlayer? = null

    private val handler = Handler(Looper.getMainLooper())
    private val transitionMs = 4_000L
    private var transitionStartedAt = 0L
    private var transitioning = false
    private var outgoingPlayer: ExoPlayer? = null
    private var incomingPlayer: ExoPlayer? = null
    private var pausedAt = 0L

    private fun listenerFor(owner: ExoPlayer) = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Only a transition on the outgoing player can invalidate the fade.
            // The incoming player's initial playlist transition is expected.
            if (transitioning && owner === outgoingPlayer) cancelTransition()
        }
    }

    private val transitionTicker = object : Runnable {
        override fun run() {
            val active = activePlayer
            if (active != null) {
                if (!transitioning && active.isPlaying) {
                    val duration = active.duration
                    val remaining = duration - active.currentPosition
                    if (duration != C.TIME_UNSET && duration > 0L &&
                        remaining in 1L..transitionMs &&
                        active.repeatMode != Player.REPEAT_MODE_ONE &&
                        active.hasNextMediaItem()
                    ) {
                        beginTransition(active)
                    }
                } else if (transitioning) {
                    val outgoing = outgoingPlayer
                    val incoming = incomingPlayer
                    if (outgoing != null && incoming != null) {
                        if (outgoing.isPlaying) {
                            if (pausedAt != 0L) {
                                transitionStartedAt += (SystemClock.elapsedRealtime() - pausedAt).coerceAtLeast(0L)
                                pausedAt = 0L
                            }
                            // Wait for the next track to be ready before fading, otherwise
                            // the outgoing track can fade into silence while it buffers.
                            if (incoming.playbackState == Player.STATE_READY) {
                                incoming.play()
                                val elapsed = (SystemClock.elapsedRealtime() - transitionStartedAt).coerceAtLeast(0L)
                                val fraction = (elapsed.toFloat() / transitionMs.toFloat()).coerceIn(0f, 1f)
                                outgoing.volume = 1f - fraction
                                incoming.volume = fraction
                                if (fraction >= 1f) finishTransition(outgoing, incoming)
                            } else {
                                outgoing.volume = 1f
                                incoming.volume = 0f
                            }
                        } else {
                            incoming.pause()
                            if (pausedAt == 0L) pausedAt = SystemClock.elapsedRealtime()
                        }
                    }
                }
            }
            handler.postDelayed(this, 40L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        val first = buildPlayer()
        val second = buildPlayer()
        firstPlayer = first
        secondPlayer = second
        activePlayer = first
        standbyPlayer = second
        firstPlayerListener = listenerFor(first)
        secondPlayerListener = listenerFor(second)
        first.addListener(firstPlayerListener!!)
        second.addListener(secondPlayerListener!!)

        mediaSession = MediaSession.Builder(this, first).build()
        handler.post(transitionTicker)
    }

    private fun beginTransition(outgoing: ExoPlayer) {
        val incoming = standbyPlayer ?: return
        val nextIndex = outgoing.nextMediaItemIndex
        if (nextIndex !in 0 until outgoing.mediaItemCount) return

        val queue = (0 until outgoing.mediaItemCount).map(outgoing::getMediaItemAt)
        try {
            incoming.stop()
            incoming.clearMediaItems()
            incoming.setMediaItems(queue, nextIndex, 0L)
            incoming.repeatMode = outgoing.repeatMode
            incoming.shuffleModeEnabled = outgoing.shuffleModeEnabled
            incoming.volume = 0f
            incoming.prepare()
            transitioning = true
            transitionStartedAt = SystemClock.elapsedRealtime()
            pausedAt = 0L
            outgoingPlayer = outgoing
            incomingPlayer = incoming
            incoming.play()
        } catch (_: Exception) {
            transitioning = false
            outgoingPlayer = null
            incomingPlayer = null
            incoming.pause()
            incoming.stop()
            incoming.clearMediaItems()
            incoming.volume = 1f
        }
    }

    private fun finishTransition(outgoing: ExoPlayer, incoming: ExoPlayer) {
        incoming.volume = 1f
        outgoing.volume = 0f
        mediaSession?.setPlayer(incoming)
        activePlayer = incoming
        standbyPlayer = outgoing

        outgoing.pause()
        outgoing.stop()
        outgoing.clearMediaItems()
        outgoing.volume = 1f

        transitioning = false
        outgoingPlayer = null
        incomingPlayer = null
        pausedAt = 0L
    }

    private fun cancelTransition() {
        outgoingPlayer?.volume = 1f
        incomingPlayer?.let {
            it.pause()
            it.stop()
            it.clearMediaItems()
            it.volume = 1f
        }
        transitioning = false
        outgoingPlayer = null
        incomingPlayer = null
        pausedAt = 0L
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cancelTransition()
        mediaSession?.release()
        mediaSession = null
        firstPlayer?.let { player -> firstPlayerListener?.let(player::removeListener) }
        secondPlayer?.let { player -> secondPlayerListener?.let(player::removeListener) }
        firstPlayer?.release()
        secondPlayer?.release()
        firstPlayer = null
        secondPlayer = null
        firstPlayerListener = null
        secondPlayerListener = null
        activePlayer = null
        standbyPlayer = null
        super.onDestroy()
    }
}