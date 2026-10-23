package tv.prism.app.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.prism.app.data.Http
import tv.prism.app.data.Stream

enum class PlayState { Idle, Loading, Playing, Error }

/**
 * One ExoPlayer for the whole app. Preview and fullscreen attach their PlayerView to it, so
 * going fullscreen is instant and never re-buffers.
 */
@OptIn(UnstableApi::class)
class PlayerManager(context: Context) {
    private val dataSource = OkHttpDataSource.Factory(Http.client)

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(
            // Fall back to a software decoder when the TV's hardware one can't handle a stream.
            DefaultRenderersFactory(context).setEnableDecoderFallback(true),
        )
        .setLoadControl(
            // Start after 1 s of data instead of 2.5 s: faster zapping. Capped buffer for 1 GB TVs.
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(8_000, 30_000, 1_000, 2_000)
                .build(),
        )
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
        .build()
        .apply {
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            addListener(listener())
        }

    private val _state = MutableStateFlow(PlayState.Idle)
    val state: StateFlow<PlayState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var current: Stream? = null
    private var triedFallback = false
    private var retries = 0

    /** [startMs] > 0 resumes a movie part-way through; live channels always start at the live edge. */
    fun play(stream: Stream, startMs: Long = 0) {
        current = stream
        triedFallback = false
        retries = 0
        start(stream.url, stream, startMs)
    }

    fun seekBy(deltaMs: Long) {
        val dur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, dur))
    }

    fun togglePause() {
        player.playWhenReady = !player.playWhenReady
    }

    private fun start(url: String, stream: Stream, startMs: Long = 0) {
        _error.value = null
        _state.value = PlayState.Loading
        dataSource.setDefaultRequestProperties(stream.headers)
        dataSource.setUserAgent(stream.headers["User-Agent"])
        val item = MediaItem.Builder()
            .setUri(url)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(6_000).build())
            .build()
        if (startMs > 0) player.setMediaItem(item, startMs) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    fun stop() {
        current = null
        player.stop()
        player.clearMediaItems()
        _state.value = PlayState.Idle
    }

    fun release() = player.release()

    private fun listener() = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> if (_state.value != PlayState.Error) _state.value = PlayState.Loading
                Player.STATE_READY -> { _state.value = PlayState.Playing; retries = 0 }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val stream = current ?: return
            when {
                // Live streams fall behind the window after a stall: jump back to the live edge.
                error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                    player.seekToDefaultPosition(); player.prepare()
                }
                !triedFallback && stream.fallbackUrl != null -> {
                    triedFallback = true
                    start(stream.fallbackUrl, stream)
                }
                retries < 2 && error.errorCode in RETRYABLE -> {
                    retries++
                    player.prepare()
                }
                else -> {
                    _state.value = PlayState.Error
                    _error.value = describe(error)
                }
            }
        }
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            if ((e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode == 429)
                "The provider is limiting requests. Wait a few minutes."
            else "The server refused this stream (it may allow only one device at a time)"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the stream: check your connection"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "This stream format isn't supported"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "This TV can't decode this channel"
        else -> "Playback failed (${e.errorCodeName})"
    }

    private companion object {
        val RETRYABLE = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
    }
}
