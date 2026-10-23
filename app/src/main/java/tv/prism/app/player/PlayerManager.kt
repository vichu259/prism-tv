package tv.prism.app.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
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
import tv.prism.app.data.SubtitleFile

/** One subtitle or audio choice offered by the current stream. */
data class TrackOption(
    val label: String,
    val language: String?,
    val selected: Boolean,
    internal val group: Tracks.Group,
    internal val index: Int,
)

enum class PlayState { Idle, Loading, Playing, Error }

/**
 * One ExoPlayer for the whole app. Preview and fullscreen attach their PlayerView to it, so
 * going fullscreen is instant and never re-buffers.
 */
@OptIn(UnstableApi::class)
class PlayerManager(context: Context) {
    private val dataSource = OkHttpDataSource.Factory(Http.client)
    private val prefs = context.getSharedPreferences("prism_player", Context.MODE_PRIVATE)

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

    /** Subtitle and audio choices for what's playing now; empty until the stream's tracks are known. */
    private val _textTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    val textTracks: StateFlow<List<TrackOption>> = _textTracks.asStateFlow()
    private val _audioTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    val audioTracks: StateFlow<List<TrackOption>> = _audioTracks.asStateFlow()

    /** Remembered across streams: subtitles on/off and the language last picked. */
    private var subtitlesOn: Boolean
        get() = prefs.getBoolean("subs_on", false)
        set(v) = prefs.edit().putBoolean("subs_on", v).apply()
    private var subtitleLanguage: String?
        get() = prefs.getString("subs_lang", null)
        set(v) = prefs.edit().putString("subs_lang", v).apply()
    private var audioLanguage: String?
        get() = prefs.getString("audio_lang", null)
        set(v) = prefs.edit().putString("audio_lang", v).apply()
    /** Whether the remembered subtitle choice has been applied to the current stream yet. */
    private var appliedPreference = false

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
            .setSubtitleConfigurations(stream.subtitles.mapNotNull(::subtitleConfig))
            .build()
        // Overrides point at the previous stream's tracks; start fresh and let preferences pick.
        appliedPreference = false
        _textTracks.value = emptyList()
        _audioTracks.value = emptyList()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesOn)
            .setPreferredTextLanguage(subtitleLanguage)
            .setSelectUndeterminedTextLanguage(subtitlesOn)
            .setPreferredAudioLanguage(audioLanguage)
            .build()
        if (startMs > 0) player.setMediaItem(item, startMs) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    /** Turn subtitles off ([option] null) or show a specific track; remembered for later streams. */
    fun selectSubtitles(option: TrackOption?) {
        val b = player.trackSelectionParameters.buildUpon()
        if (option == null) {
            subtitlesOn = false
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).clearOverridesOfType(C.TRACK_TYPE_TEXT)
        } else {
            subtitlesOn = true
            option.language?.let { subtitleLanguage = it }
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.index))
        }
        player.trackSelectionParameters = b.build()
    }

    fun selectAudio(option: TrackOption) {
        option.language?.let { audioLanguage = it }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.index))
            .build()
    }

    private fun subtitleConfig(f: SubtitleFile): MediaItem.SubtitleConfiguration? {
        val ext = f.url.substringBefore('?').substringAfterLast('.', "").lowercase()
        val mime = when (ext) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "vtt", "webvtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
            else -> return null
        }
        return MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(f.url))
            .setMimeType(mime).setLanguage(f.language).setLabel(f.label).build()
    }

    private fun refreshTracks(tracks: Tracks) {
        _textTracks.value = options(tracks, C.TRACK_TYPE_TEXT)
        _audioTracks.value = options(tracks, C.TRACK_TYPE_AUDIO)
        // Subtitles were on but nothing matched the remembered language: take the first track there is.
        if (!appliedPreference && _textTracks.value.isNotEmpty()) {
            appliedPreference = true
            if (subtitlesOn && _textTracks.value.none { it.selected }) selectSubtitles(_textTracks.value.first())
        }
    }

    private fun options(tracks: Tracks, type: Int): List<TrackOption> {
        var n = 0
        return tracks.groups.filter { it.type == type }.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
                val f = g.getTrackFormat(i)
                n++
                TrackOption(trackLabel(f, type, n), f.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }, g.isTrackSelected(i), g, i)
            }
        }
    }

    private fun trackLabel(f: Format, type: Int, n: Int): String {
        val lang = f.language?.takeIf { it != C.LANGUAGE_UNDETERMINED && it.isNotBlank() }
            ?.let { java.util.Locale(it).displayLanguage.replaceFirstChar(Char::uppercase) }
        val cc = f.sampleMimeType == MimeTypes.APPLICATION_CEA608 || f.sampleMimeType == MimeTypes.APPLICATION_CEA708
        val base = f.label?.takeIf { it.isNotBlank() } ?: lang ?: if (type == C.TRACK_TYPE_TEXT) "Subtitles $n" else "Audio $n"
        return when {
            cc -> "Closed captions" + (lang?.let { " · $it" } ?: "")
            type == C.TRACK_TYPE_AUDIO && f.channelCount >= 6 -> "$base · 5.1"
            else -> base
        }
    }

    fun stop() {
        current = null
        player.stop()
        player.clearMediaItems()
        _state.value = PlayState.Idle
    }

    fun release() = player.release()

    private fun listener() = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) = refreshTracks(tracks)

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
