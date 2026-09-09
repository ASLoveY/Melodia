package com.lin0721.linmusic.core.player.ldac

import android.media.AudioDeviceInfo
import android.media.MediaFormat
import android.media.MediaPlayer
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Native media pipeline can request DIRECT PCM where Java AudioTrack remains on a mixer.
 * Owns a single item; the outer PlayerManager and CrossfadePlayer retain queue and audio focus.
 * All native calls and source setup stay off the UI thread. Epochs reject obsolete callbacks.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class NativePrecisionPlayer(private val context: Context, private val sourceFactory: DataSource.Factory) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("MelodiaNativeAudio").apply { start() }
    private val worker = Handler(thread.looper)
    @Volatile private var epoch = 0L
    @Volatile private var seekRevision = 0L
    @Volatile private var disposed = false

    // Session-facing state, accessed on the application looper.
    private var item: MediaItem? = null
    private var itemUid = Any()
    private var state = Player.STATE_IDLE
    private var position = 0L
    private var duration = C.TIME_UNSET
    private var bufferedPosition = 0L
    private var requestedPlay = false
    private var requestedVolume = 1f
    private var requestedRepeat = Player.REPEAT_MODE_OFF
    private var error: PlaybackException? = null
    private var preferredDevice: AudioDeviceInfo? = null
    var routes: List<AudioDeviceInfo> = emptyList(); private set
    var sourceSampleRate: Int? = null; private set
    var sourceMimeType: String? = null; private set
    var nativeSessionId: Int? = null; private set

    // Worker-owned native state.
    private var native: MediaPlayer? = null
    private var nativeEpoch = 0L
    private var data: CachedMediaDataSource? = null
    private var ready = false
    private var seeking = false
    private var buffering = false
    private var ended = false
    private var wantedPlay = false
    private var pendingSeek = 0L
    private var inFlightSeek = 0L
    private var appliedSeekRevision = 0L
    private var bufferPercent = 0
    private var nativeDuration = C.TIME_UNSET

    private fun publish(token: Long, revision: Long? = appliedSeekRevision, update: () -> Unit) {
        main.post { if (!disposed && token == epoch && (revision == null || revision == seekRevision)) { update(); invalidateState() } }
    }
    private fun fail(token: Long, cause: Exception) {
        main.post { if (!disposed && token == epoch) {
            error = PlaybackException("Native audio playback failed", cause, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            state = Player.STATE_IDLE
            invalidateState()
        } }
    }
    private fun releaseNative() {
        worker.removeCallbacks(poll)
        runCatching { native?.release() }; native = null
        runCatching { data?.close() }; data = null
        ready = false; seeking = false; buffering = false; ended = false; bufferPercent = 0
    }
    private fun snapshot(token: Long) {
        val player = native ?: return
        if (!ready) return
        val pos = if (seeking) pendingSeek else player.currentPosition.toLong()
        val playerRoutes = when {
            Build.VERSION.SDK_INT >= 36 -> player.routedDevices
            Build.VERSION.SDK_INT >= 28 -> listOfNotNull(player.routedDevice)
            else -> emptyList()
        }
        val playerState = when { ended -> Player.STATE_ENDED; seeking || buffering -> Player.STATE_BUFFERING; else -> Player.STATE_READY }
        val buffered = if (nativeDuration > 0) maxOf(pos, nativeDuration * bufferPercent / 100) else pos
        val totalDuration = nativeDuration
        publish(token) { position = pos; duration = totalDuration; bufferedPosition = buffered; state = playerState; routes = playerRoutes }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (disposed) return
            val token = nativeEpoch
            try { snapshot(token) } catch (failure: Exception) { fail(token, failure) }
            if (native != null) worker.postDelayed(this, 50)
        }
    }
    private fun seekNative(player: MediaPlayer) {
        if (seeking) return
        seeking = true; ended = false
        inFlightSeek = pendingSeek
        player.seekTo(inFlightSeek.coerceAtLeast(0), MediaPlayer.SEEK_CLOSEST)
    }
    private fun prepareNative(token: Long, mediaItem: MediaItem, start: Long, revision: Long, play: Boolean, volume: Float, repeat: Int, device: AudioDeviceInfo?) {
        if (token != epoch || disposed) return
        releaseNative()
        pendingSeek = start; appliedSeekRevision = revision; wantedPlay = play
        try {
            val local = requireNotNull(mediaItem.localConfiguration)
            val source = CachedMediaDataSource(sourceFactory, local.uri, local.customCacheKey)
            data = source
            val player = MediaPlayer()
            native = player
            nativeEpoch = token
            player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            player.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            player.setVolume(volume, volume)
            player.isLooping = repeat == Player.REPEAT_MODE_ONE
            if (device != null && Build.VERSION.SDK_INT >= 28) player.setPreferredDevice(device)
            player.setOnPreparedListener {
                if (token != epoch || disposed) return@setOnPreparedListener
                try {
                    ready = true; nativeDuration = player.duration.toLong()
                    val format = player.trackInfo.firstOrNull { it.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO }?.format
                    val rate = format?.takeIf { it.containsKey(MediaFormat.KEY_SAMPLE_RATE) }?.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val mime = format?.getString(MediaFormat.KEY_MIME)
                    val session = player.audioSessionId
                    // Item metadata remains valid even if a newer seek supersedes this position.
                    publish(token, revision = null) { sourceSampleRate = rate; sourceMimeType = mime; nativeSessionId = session }
                    if (pendingSeek > 0) seekNative(player) else if (wantedPlay) player.start()
                    snapshot(token)
                    worker.post(poll)
                } catch (failure: Exception) { fail(token, failure) }
            }
            player.setOnSeekCompleteListener {
                if (token != epoch || disposed) return@setOnSeekCompleteListener
                try {
                    seeking = false
                    if (inFlightSeek != pendingSeek) seekNative(player) else if (wantedPlay) player.start()
                    snapshot(token)
                } catch (failure: Exception) { fail(token, failure) }
            }
            player.setOnCompletionListener {
                if (token == epoch && !disposed) {
                    try { ended = true; snapshot(token) } catch (failure: Exception) { fail(token, failure) }
                }
            }
            player.setOnBufferingUpdateListener { _, percent -> if (token == epoch) bufferPercent = percent.coerceIn(0, 100) }
            player.setOnInfoListener { _, what, _ ->
                if (token == epoch && !disposed) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) buffering = true
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) buffering = false
                    try { snapshot(token) } catch (failure: Exception) { fail(token, failure) }
                }
                false
            }
            player.setOnErrorListener { _, what, extra -> fail(token, java.io.IOException("Native decoder error $what/$extra")); true }
            player.setDataSource(source)
            if (token == epoch && !disposed) player.prepareAsync() else releaseNative()
        } catch (failure: Exception) { releaseNative(); fail(token, failure) }
    }
    fun setPreferredAudioDevice(device: AudioDeviceInfo?) {
        if (preferredDevice?.id == device?.id) return
        preferredDevice = device
        val token = epoch
        worker.post { if (token == epoch && Build.VERSION.SDK_INT >= 28) runCatching { native?.setPreferredDevice(device) }.onFailure { fail(token, Exception(it)) } }
    }

    override fun getState(): State {
        val playlist = item?.let { listOf(MediaItemData.Builder(itemUid).setMediaItem(it).setDurationUs(if (duration > 0) duration * 1000 else C.TIME_UNSET).setIsSeekable(true).build()) }.orEmpty()
        return State.Builder().setAvailableCommands(Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_TRACKS, Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES, Player.COMMAND_GET_VOLUME, Player.COMMAND_SET_VOLUME,
            Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION
        ).build()).setPlaylist(playlist).setCurrentMediaItemIndex(if (playlist.isEmpty()) C.INDEX_UNSET else 0)
            .setContentPositionMs { position }.setContentBufferedPositionMs { bufferedPosition }
            .setTotalBufferedDurationMs { (bufferedPosition - position).coerceAtLeast(0) }
            .setPlaybackState(state).setPlayerError(error).setIsLoading(state == Player.STATE_BUFFERING)
            .setPlayWhenReady(requestedPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setRepeatMode(requestedRepeat).setVolume(requestedVolume)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()).build()
    }
    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        epoch++; seekRevision++
        item = mediaItems.getOrNull(startIndex.coerceAtLeast(0)); itemUid = Any()
        position = startPositionMs.coerceAtLeast(0); duration = C.TIME_UNSET; bufferedPosition = position
        state = Player.STATE_IDLE; error = null; routes = emptyList(); sourceSampleRate = null; sourceMimeType = null; nativeSessionId = null
        worker.post { releaseNative() }
        return done()
    }
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        if (fromIndex == 0 && toIndex > 0) handleSetMediaItems(mutableListOf(), 0, 0)
        return done()
    }
    override fun handlePrepare(): ListenableFuture<*> {
        if (state != Player.STATE_IDLE && error == null) return done()
        val mediaItem = item ?: return done()
        val token = ++epoch; val revision = seekRevision; val start = position; val play = requestedPlay
        val volume = requestedVolume; val repeat = requestedRepeat; val device = preferredDevice
        state = Player.STATE_BUFFERING; error = null
        worker.post { prepareNative(token, mediaItem, start, revision, play, volume, repeat, device) }
        return done()
    }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        requestedPlay = playWhenReady
        val token = epoch
        worker.post {
            if (token == epoch && !disposed) {
                wantedPlay = playWhenReady
                try { if (ready && !seeking) { if (playWhenReady && !ended) native?.start() else native?.pause(); snapshot(token) } }
                catch (failure: Exception) { fail(token, failure) }
            }
        }
        return done()
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = positionMs.coerceAtLeast(0); val revision = ++seekRevision; val token = epoch
        position = target
        worker.post {
            if (token == epoch && !disposed) {
                pendingSeek = target; appliedSeekRevision = revision
                try { if (ready) native?.let { seekNative(it); snapshot(token) } } catch (failure: Exception) { fail(token, failure) }
            }
        }
        return done()
    }
    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        requestedVolume = volume; val token = epoch
        worker.post { if (token == epoch) runCatching { native?.setVolume(volume, volume) }.onFailure { fail(token, Exception(it)) } }
        return done()
    }
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        requestedRepeat = repeatMode; val token = epoch
        worker.post { if (token == epoch) runCatching { native?.isLooping = repeatMode == Player.REPEAT_MODE_ONE }.onFailure { fail(token, Exception(it)) } }
        return done()
    }
    override fun handleStop(): ListenableFuture<*> { epoch++; state = Player.STATE_IDLE; routes = emptyList(); worker.post { releaseNative() }; return done() }
    override fun handleRelease(): ListenableFuture<*> {
        disposed = true; epoch++; worker.removeCallbacksAndMessages(null)
        worker.post { releaseNative(); thread.quitSafely() }
        return done()
    }
}
