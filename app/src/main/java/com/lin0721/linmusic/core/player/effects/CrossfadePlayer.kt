package com.lin0721.linmusic.core.player.effects

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import com.lin0721.linmusic.core.player.ldac.LdacMonitor
import com.lin0721.linmusic.core.player.ldac.PcmFormat
import com.lin0721.linmusic.core.player.ldac.shouldUseBluetoothPrecision
import java.util.concurrent.ConcurrentHashMap
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lin0721.linmusic.core.preferences.PlaybackEffectsSettings

data class PreparedTransition(val generation: Long, val currentKey: String, val nextKey: String, val mediaItem: MediaItem)

/** One session-facing player; the silent decoder never publishes its state to controllers. */
@androidx.annotation.OptIn(UnstableApi::class)
class CrossfadePlayer(
    private val context: Context,
    private val dataSource: DataSource.Factory,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val acceptHandoff: (PreparedTransition, Long) -> Boolean,
    private val onInvalidatePreparation: () -> Unit = {},
    private val isTransitionCurrent: (PreparedTransition) -> Boolean = { true },
    private val onAudibleSample: ((String, Long, Float) -> Unit)? = null,
    private val ldacMonitor: LdacMonitor? = null,
    private val onOutputModeSwitch: (String, Long) -> Unit = { _, _ -> },
    private val bluetoothRouteOverride: (() -> Boolean)? = null,
    private val precisionTrackProvider: DefaultAudioSink.AudioTrackProvider = DefaultAudioSink.AudioTrackProvider.DEFAULT
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val handler = Handler(Looper.getMainLooper())
    private val profiles = LoudnessProfileStore(context)
    private val processors = Array(2) { LoudnessAudioProcessor(profiles) }
    private val audioTracks = ConcurrentHashMap<Int, AudioTrack>()
    private val decodedFormats = ConcurrentHashMap<Int, PcmFormat>()
    private val outputFormats = ConcurrentHashMap<Int, PcmFormat>()
    private var precisionDeck: ExoPlayer? = null
    private var precisionActive = false
    val highPrecisionActive: Boolean get() = precisionActive
    private var precisionRequested = false
    private var failedPrecisionKey: String? = null
    private var preferredDevice: AudioDeviceInfo? = null
    private var nextDiagnosticAt = 0L
    private fun createDeck(index: Int, precise: Boolean): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val builder = DefaultAudioSink.Builder(context).setEnableFloatOutput(precise)
                    .setAudioTrackProvider { config, attributes, sessionId ->
                        (if (precise) precisionTrackProvider else DefaultAudioSink.AudioTrackProvider.DEFAULT).getAudioTrack(config, attributes, sessionId).also {
                            audioTracks[index] = it
                            outputFormats[index] = PcmFormat(config.sampleRate, config.encoding)
                        }
                    }
                if (!precise) builder.setAudioProcessors(arrayOf(processors[index]))
                return object : ForwardingAudioSink(builder.build()) {
                    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
                        decodedFormats[index] = PcmFormat(inputFormat.sampleRate, inputFormat.pcmEncoding)
                        super.configure(inputFormat, specifiedBufferSize, outputChannels)
                    }
                }
            }
        }
        return ExoPlayer.Builder(context, renderers).setMediaSourceFactory(DefaultMediaSourceFactory(dataSource)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        }
    }
    private val decks = Array(2) { createDeck(it, false) }
    private var activeIndex = 0
    val activeDeck: ExoPlayer get() = if (precisionActive) requireNotNull(precisionDeck) else decks[activeIndex]
    private val standby: ExoPlayer get() = decks[1 - activeIndex]
    private var prepared: PreparedTransition? = null
    private var fadeStartMs: Long? = null
    private var fadeDurationMs = 0L
    private var startPending = false
    private var userVolume = 1f
    private var duckVolume = 1f
    private var disposed = false
    private var suppressEvents = false
    var effects = PlaybackEffectsSettings()
        set(value) {
            val previous = field
            field = value
            processors.forEach { it.enabled = value.normalizationEnabled }
            if (previous.crossfadeEnabled != value.crossfadeEnabled || previous.crossfadeSeconds != value.crossfadeSeconds) {
                cancelPreparation(); onInvalidatePreparation()
            }
        }
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusOwned = false
    private var resumeOnFocus = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    duckVolume = 1f
                    if (resumeOnFocus) { resumeOnFocus = false; setBothPlaying(true) }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duckVolume = .2f
                else -> {
                    resumeOnFocus = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && activeDeck.playWhenReady
                    if (change == AudioManager.AUDIOFOCUS_LOSS) focusOwned = false
                    setBothPlaying(false)
                }
            }
            updateVolumes(); invalidateState()
        }, handler).build()

    private fun listenTo(deck: ExoPlayer) {
        deck.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!precisionActive && deck === activeDeck && reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) processors[activeIndex].complete(deck.duration)
            }
            override fun onEvents(player: Player, events: Player.Events) {
                if (disposed || suppressEvents) return
                if (player === activeDeck) {
                    if (precisionActive && player.playerError != null) {
                        failedPrecisionKey = player.currentMediaItem?.mediaId
                        switchPrecision(false)
                        return
                    }
                    if (player.playbackState == Player.STATE_ENDED) {
                        if (!precisionActive) processors[activeIndex].complete(player.duration)
                        if (fadeStartMs != null && standby.playbackState == Player.STATE_READY) handoff()
                    }
                    invalidateState()
                } else if (prepared != null && (player.playerError != null ||
                        (fadeStartMs != null && player.playbackState != Player.STATE_READY))) cancelPreparation()
            }
        })
    }
    init {
        decks.forEach(::listenTo)
        handler.post(ticker)
    }

    private val ticker: Runnable get() = object : Runnable {
        override fun run() {
            if (disposed) return
            if (SystemClock.elapsedRealtime() >= nextDiagnosticAt) {
                nextDiagnosticAt = SystemClock.elapsedRealtime() + 500
                refreshOutput()
            }
            tick()
            handler.postDelayed(this, 20)
        }
    }

    fun prepareNext(value: PreparedTransition) {
        if (precisionActive || !effects.crossfadeEnabled || !isTransitionCurrent(value) || value.currentKey != activeDeck.currentMediaItem?.mediaId || activeDeck.repeatMode == Player.REPEAT_MODE_ONE) return
        cancelPreparation()
        prepared = value
        processors[1 - activeIndex].beginSource(value.mediaItem.mediaMetadata.extras?.getString(LOUDNESS_SOURCE_KEY).orEmpty(), 0)
        standby.volume = 0f
        standby.setMediaItem(value.mediaItem)
        standby.repeatMode = Player.REPEAT_MODE_OFF
        standby.prepare()
    }

    fun cancelPreparation() {
        prepared = null; fadeStartMs = null; startPending = false
        standby.pause(); standby.stop(); standby.clearMediaItems()
        activeDeck.volume = userVolume * duckVolume
    }

    private fun tick() {
        val transition = prepared ?: return
        if (!isTransitionCurrent(transition) || transition.currentKey != activeDeck.currentMediaItem?.mediaId || !effects.crossfadeEnabled) { cancelPreparation(); return }
        if (!activeDeck.isPlaying || standby.playbackState != Player.STATE_READY) return
        val duration = CrossfadeEnvelope.duration(effects.durationMs, activeDeck.duration, standby.duration)
        if (duration < 100) return
        val remaining = activeDeck.duration - activeDeck.currentPosition
        if (fadeStartMs == null) {
            if (remaining > duration || remaining <= 0 || standby.bufferedPosition < minOf(duration + 500, standby.duration)) return
            if (!startPending) { startPending = true; standby.play() }
            if (!standby.isPlaying) return
            fadeStartMs = activeDeck.currentPosition
            fadeDurationMs = remaining
        }
        updateVolumes()
        onAudibleSample?.invoke(transition.currentKey, activeDeck.currentPosition, activeDeck.volume)
        onAudibleSample?.invoke(transition.nextKey, standby.currentPosition, standby.volume)
        if (activeDeck.currentPosition - fadeStartMs!! >= fadeDurationMs) handoff()
    }

    private fun updateVolumes() {
        val weight = fadeStartMs?.let { CrossfadeEnvelope.incoming(activeDeck.currentPosition - it, fadeDurationMs) } ?: 0f
        activeDeck.volume = userVolume * duckVolume * (1 - weight)
        standby.volume = userVolume * duckVolume * weight
    }

    private fun handoff() {
        val transition = prepared ?: return
        val incomingPosition = standby.currentPosition
        // Clear first: the accepted queue update invalidates outstanding preparations.
        prepared = null; fadeStartMs = null; startPending = false
        suppressEvents = true
        val oldIndex = activeIndex
        activeIndex = 1 - activeIndex
        activeDeck.volume = userVolume * duckVolume
        val accepted = acceptHandoff(transition, incomingPosition)
        if (!accepted) {
            activeIndex = oldIndex
            suppressEvents = false
            cancelPreparation()
            invalidateState()
            return
        }
        processors[oldIndex].complete(decks[oldIndex].duration)
        decks[oldIndex].stop(); decks[oldIndex].clearMediaItems()
        suppressEvents = false
        invalidateState()
    }

    fun setPreferredAudioDevice(device: AudioDeviceInfo?) {
        preferredDevice = device
        decks.forEach { it.setPreferredAudioDevice(device) }; precisionDeck?.setPreferredAudioDevice(device)
    }

    fun setBluetoothPrecisionRequested(enabled: Boolean) {
        if (precisionRequested != enabled) failedPrecisionKey = null
        precisionRequested = enabled
        refreshOutput()
    }
    private fun currentRoutes(): List<AudioDeviceInfo> = runCatching {
        val track = audioTracks[if (precisionActive) 2 else activeIndex] ?: return@runCatching emptyList()
        if (Build.VERSION.SDK_INT >= 36) track.routedDevices else listOfNotNull(track.routedDevice)
    }.getOrDefault(emptyList())

    private fun refreshOutput() {
        val slot = if (precisionActive) 2 else activeIndex
        val routes = currentRoutes()
        val a2dp = bluetoothRouteOverride?.invoke() ?: (routes.singleOrNull()?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        // AudioTrack may not expose a route while paused or reinitializing. Do not oscillate
        // between paths merely because of this transient unknown state.
        val failed = failedPrecisionKey != null && failedPrecisionKey == activeDeck.currentMediaItem?.mediaId
        if (!precisionRequested) switchPrecision(false)
        else if (precisionActive && bluetoothRouteOverride == null && audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }) switchPrecision(false)
        else if (routes.isNotEmpty() || bluetoothRouteOverride != null) switchPrecision(shouldUseBluetoothPrecision(precisionRequested, a2dp, failed))
        ldacMonitor?.publish(routes, decodedFormats[slot], outputFormats[slot], precisionActive, failed)
    }

    private fun switchPrecision(enabled: Boolean) {
        if (enabled == precisionActive || disposed) return
        val previous = activeDeck
        val item = previous.currentMediaItem
        val position = previous.currentPosition
        val shouldPrepare = previous.playbackState != Player.STATE_IDLE || previous.playerError != null
        val wasPlaying = previous.playWhenReady
        val repeat = previous.repeatMode
        cancelPreparation(); onInvalidatePreparation()
        if (enabled && precisionDeck == null) precisionDeck = createDeck(2, true).also(::listenTo)
        suppressEvents = true
        previous.pause(); previous.stop(); previous.clearMediaItems()
        precisionActive = enabled
        activeDeck.setPreferredAudioDevice(preferredDevice)
        activeDeck.repeatMode = repeat
        activeDeck.volume = userVolume * duckVolume
        if (item != null) {
            onOutputModeSwitch(item.mediaId, position)
            if (!enabled) processors[activeIndex].beginSource(item.mediaMetadata.extras?.getString(LOUDNESS_SOURCE_KEY).orEmpty(), position)
            activeDeck.setMediaItem(item, position)
            if (shouldPrepare) activeDeck.prepare()
            activeDeck.playWhenReady = wasPlaying
        }
        suppressEvents = false
        invalidateState()
    }
    fun pauseForNoisyOutput() { resumeOnFocus = false; setBothPlaying(false); invalidateState() }

    override fun getState(): State {
        val deck = activeDeck
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_TRACKS, Player.COMMAND_GET_AUDIO_ATTRIBUTES, Player.COMMAND_GET_VOLUME,
            Player.COMMAND_SET_VOLUME, Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        ).build()
        return State.Builder().setAvailableCommands(commands)
            .setPlaylist(deck.currentTimeline, deck.currentTracks, deck.mediaMetadata)
            .setCurrentMediaItemIndex(deck.currentMediaItemIndex)
            .setContentPositionMs { deck.currentPosition }.setContentBufferedPositionMs { deck.bufferedPosition }
            .setTotalBufferedDurationMs { deck.totalBufferedDuration }
            .setPlayWhenReady(deck.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(deck.playbackState).setPlayerError(deck.playerError)
            .setPlaybackSuppressionReason(deck.playbackSuppressionReason)
            .setIsLoading(deck.isLoading).setRepeatMode(deck.repeatMode).setVolume(userVolume)
            .setAudioAttributes(deck.audioAttributes).build()
    }
    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()
    private fun setBothPlaying(play: Boolean) {
        activeDeck.playWhenReady = play
        if (fadeStartMs != null || startPending) standby.playWhenReady = play
    }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        resumeOnFocus = false
        if (playWhenReady && !focusOwned) focusOwned = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        setBothPlaying(playWhenReady && focusOwned)
        if (!playWhenReady && focusOwned) { audioManager.abandonAudioFocusRequest(focusRequest); focusOwned = false }
        return done()
    }
    override fun handlePrepare(): ListenableFuture<*> { activeDeck.prepare(); return done() }
    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        cancelPreparation(); onInvalidatePreparation()
        val item = mediaItems.getOrNull(startIndex.coerceAtLeast(0))
        if (failedPrecisionKey != item?.mediaId) failedPrecisionKey = null
        processors[activeIndex].beginSource(item?.mediaMetadata?.extras?.getString(LOUDNESS_SOURCE_KEY).orEmpty(), startPositionMs.coerceAtLeast(0))
        activeDeck.setMediaItems(mediaItems, startIndex.coerceAtLeast(0), startPositionMs)
        return done()
    }
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        cancelPreparation(); onInvalidatePreparation(); activeDeck.removeMediaItems(fromIndex, toIndex); return done()
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        cancelPreparation(); onInvalidatePreparation()
        processors[activeIndex].discontinuity()
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> onNext()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> onPrevious()
            else -> activeDeck.seekTo(mediaItemIndex, positionMs)
        }
        return done()
    }
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        cancelPreparation(); onInvalidatePreparation(); activeDeck.repeatMode = repeatMode; return done()
    }
    override fun handleSetVolume(volume: Float): ListenableFuture<*> { userVolume = volume; updateVolumes(); return done() }
    override fun handleStop(): ListenableFuture<*> {
        resumeOnFocus = false
        processors[activeIndex].discontinuity()
        cancelPreparation(); onInvalidatePreparation(); activeDeck.stop(); audioManager.abandonAudioFocusRequest(focusRequest); focusOwned = false; return done()
    }
    override fun handleRelease(): ListenableFuture<*> {
        disposed = true; handler.removeCallbacksAndMessages(null)
        audioManager.abandonAudioFocusRequest(focusRequest)
        decks.forEach { it.release() }
        precisionDeck?.release()
        audioTracks.clear(); decodedFormats.clear(); outputFormats.clear()
        return done()
    }
}
