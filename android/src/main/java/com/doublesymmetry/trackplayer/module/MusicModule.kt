package com.doublesymmetry.trackplayer.module

import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.RatingCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.doublesymmetry.kotlinaudio.models.Capability
import com.doublesymmetry.kotlinaudio.models.RepeatMode
import com.doublesymmetry.trackplayer.extensions.NumberExt.Companion.toMilliseconds
import com.doublesymmetry.trackplayer.model.State
import com.doublesymmetry.trackplayer.model.Track
import com.doublesymmetry.trackplayer.module.MusicEvents.Companion.EVENT_INTENT
import com.doublesymmetry.trackplayer.service.MusicService
import com.doublesymmetry.trackplayer.utils.BundleUtils
import com.doublesymmetry.trackplayer.utils.RejectionException
import com.facebook.react.bridge.*
import com.google.android.exoplayer2.DefaultLoadControl.*
import com.google.android.exoplayer2.Player
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.*
import javax.annotation.Nonnull


/**
 * @author Milen Pivchev @mpivchev
 */
class MusicModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext),
    ServiceConnection {
    private var eventHandler: MusicEvents? = null
    private var playerOptions: Bundle? = null
    private var isServiceBound = false
    private var playerSetUpPromise: Promise? = null
    private val scope = MainScope()
    private lateinit var musicService: MusicService
    private val context = reactContext

    @Nonnull
    override fun getName(): String {
        return "TrackPlayerModule"
    }

    override fun initialize() {
        Timber.plant(Timber.DebugTree())
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        scope.launch {
            // If a binder already exists, don't get a new one
            if (!::musicService.isInitialized) {
                val binder: MusicService.MusicBinder = service as MusicService.MusicBinder
                musicService = binder.service
                musicService.setupPlayer(playerOptions)
                playerSetUpPromise?.resolve(null)
            }

            isServiceBound = true
        }
    }

    /**
     * Called when a connection to the Service has been lost.
     */
    override fun onServiceDisconnected(name: ComponentName) {
        scope.launch {
            isServiceBound = false
        }
    }

    /**
     * Checks wither service is bound, or rejects. Returns whether promise was rejected.
     */
    private fun verifyServiceBoundOrReject(promise: Promise): Boolean {
        if (!isServiceBound) {
            promise.reject(
                "player_not_initialized",
                "The player is not initialized. Call setupPlayer first."
            )
            return true
        }

        return false
    }

    private fun bundleToTrack(bundle: Bundle): Track {
        return Track(context, bundle, musicService.ratingType)
    }

    private fun rejectWithException(callback: Promise, exception: Exception) {
        when (exception) {
            is RejectionException -> {
                callback.reject(exception.code, exception)
            }
            else -> {
                callback.reject("runtime_exception", exception)
            }
        }
    }

    private fun readableArrayToTrackList(data: ReadableArray?): MutableList<Track> {
        val tracks: MutableList<Track> = mutableListOf()
        val bundleList = Arguments.toList(data)

        if (bundleList !is ArrayList) {
            throw RejectionException("invalid_parameter", "Was not given an array of tracks")
        }

        bundleList.forEach {
            if (it is Bundle) {
                tracks.add(bundleToTrack(it))
            } else {
                throw RejectionException(
                    "invalid_track_object",
                    "Track was not a dictionary type"
                )
            }
        }
        return tracks
    }

    /* ****************************** API ****************************** */
    override fun getConstants(): Map<String, Any> {
        val constants: MutableMap<String, Any> = HashMap()

        // Capabilities
        constants["CAPABILITY_PLAY"] = Capability.PLAY.ordinal
        constants["CAPABILITY_PLAY_FROM_ID"] = Capability.PLAY_FROM_ID.ordinal
        constants["CAPABILITY_PLAY_FROM_SEARCH"] = Capability.PLAY_FROM_SEARCH.ordinal
        constants["CAPABILITY_PAUSE"] = Capability.PAUSE.ordinal
        constants["CAPABILITY_STOP"] = Capability.STOP.ordinal
        constants["CAPABILITY_SEEK_TO"] = Capability.SEEK_TO.ordinal
        constants["CAPABILITY_SKIP"] = OnErrorAction.SKIP.ordinal
        constants["CAPABILITY_SKIP_TO_NEXT"] = Capability.SKIP_TO_NEXT.ordinal
        constants["CAPABILITY_SKIP_TO_PREVIOUS"] = Capability.SKIP_TO_PREVIOUS.ordinal
        constants["CAPABILITY_SET_RATING"] = Capability.SET_RATING.ordinal
        constants["CAPABILITY_JUMP_FORWARD"] = Capability.JUMP_FORWARD.ordinal
        constants["CAPABILITY_JUMP_BACKWARD"] = Capability.JUMP_BACKWARD.ordinal

        // States
        constants["STATE_NONE"] = State.Idle.state
        constants["STATE_READY"] = State.Ready.state
        constants["STATE_PLAYING"] = State.Playing.state
        constants["STATE_PAUSED"] = State.Paused.state
        constants["STATE_STOPPED"] = State.Stopped.state
        constants["STATE_BUFFERING"] = State.Buffering.state
        constants["STATE_LOADING"] = State.Loading.state

        // Rating Types
        constants["RATING_HEART"] = RatingCompat.RATING_HEART
        constants["RATING_THUMBS_UP_DOWN"] = RatingCompat.RATING_THUMB_UP_DOWN
        constants["RATING_3_STARS"] = RatingCompat.RATING_3_STARS
        constants["RATING_4_STARS"] = RatingCompat.RATING_4_STARS
        constants["RATING_5_STARS"] = RatingCompat.RATING_5_STARS
        constants["RATING_PERCENTAGE"] = RatingCompat.RATING_PERCENTAGE

        // Repeat Modes
        constants["REPEAT_OFF"] = Player.REPEAT_MODE_OFF
        constants["REPEAT_TRACK"] = Player.REPEAT_MODE_ONE
        constants["REPEAT_QUEUE"] = Player.REPEAT_MODE_ALL
        return constants
    }

    @ReactMethod
    fun setupPlayer(data: ReadableMap?, promise: Promise) {
        if (isServiceBound) {
            promise.reject(
                "player_already_initialized",
                "The player has already been initialized via setupPlayer."
            )
            return
        }

        // Validate buffer keys.
        val bundledData = Arguments.toBundle(data)
        val minBuffer =
            bundledData?.getDouble(MusicService.MIN_BUFFER_KEY)?.toMilliseconds()?.toInt()
                ?: DEFAULT_MIN_BUFFER_MS
        val maxBuffer =
            bundledData?.getDouble(MusicService.MAX_BUFFER_KEY)?.toMilliseconds()?.toInt()
                ?: DEFAULT_MAX_BUFFER_MS
        val playBuffer =
            bundledData?.getDouble(MusicService.PLAY_BUFFER_KEY)?.toMilliseconds()?.toInt()
                ?: DEFAULT_BUFFER_FOR_PLAYBACK_MS
        val backBuffer =
            bundledData?.getDouble(MusicService.BACK_BUFFER_KEY)?.toMilliseconds()?.toInt()
                ?: DEFAULT_BACK_BUFFER_DURATION_MS

        if (playBuffer < 0) {
            promise.reject(
                "play_buffer_error",
                "The value for playBuffer should be greater than or equal to zero."
            )
            return
        }

        if (backBuffer < 0) {
            promise.reject(
                "back_buffer_error",
                "The value for backBuffer should be greater than or equal to zero."
            )
            return
        }

        if (minBuffer < playBuffer) {
            promise.reject(
                "min_buffer_error",
                "The value for minBuffer should be greater than or equal to playBuffer."
            )
            return
        }

        if (maxBuffer < minBuffer) {
            promise.reject(
                "min_buffer_error",
                "The value for maxBuffer should be greater than or equal to minBuffer."
            )
            return
        }

        playerSetUpPromise = promise
        playerOptions = bundledData


        val manager = LocalBroadcastManager.getInstance(context)
        eventHandler = MusicEvents(context)
        manager.registerReceiver(eventHandler!!, IntentFilter(EVENT_INTENT))

        Intent(context, MusicService::class.java).also { intent ->
            context.startService(intent)
            context.bindService(intent, this, Context.BIND_AUTO_CREATE)
        }
    }

    @ReactMethod
    @Deprecated("Backwards compatible function from the old android implementation. Should be removed in the next major release.")
    fun isServiceRunning(callback: Promise) {
        callback.resolve(isServiceBound)
    }

    @ReactMethod
    fun updateOptions(data: ReadableMap?, callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        val options = Arguments.toBundle(data)

        options?.let {
            musicService.updateOptions(it)
        }

        callback.resolve(null)
    }

    @ReactMethod
    fun add(data: ReadableArray?, insertBeforeIndex: Int, callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        try {
            val tracks = readableArrayToTrackList(data);
            if (insertBeforeIndex < -1 || insertBeforeIndex > musicService.tracks.size) {
                callback.reject("index_out_of_bounds", "The track index is out of bounds")
                return@launch
            }
            musicService.add(
                tracks,
                if (insertBeforeIndex == -1) musicService.tracks.size else insertBeforeIndex
            )
        } catch (exception: Exception) {
            rejectWithException(callback, exception)
        }
    }

    @ReactMethod
    fun load(data: ReadableMap?, callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch
        if (data == null) {
            callback.resolve(null)
            return@launch
        }
        var bundle = Arguments.toBundle(data);
        if (bundle is Bundle) {
            musicService.load(bundleToTrack(bundle))
            callback.resolve(null)
        } else {
            callback.reject("invalid_track_object", "Track was not a dictionary type")
        }
    }

    @ReactMethod
    fun move(fromIndex: Int, toIndex: Int, callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch
        musicService.move(fromIndex, toIndex)
        callback.resolve(null)
    }

    @ReactMethod
    fun remove(data: ReadableArray?, callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch
        val inputIndexes = Arguments.toList(data)
        if (inputIndexes != null) {
            val size = musicService.tracks.size
            var indexes: ArrayList<Int> = ArrayList();
            for (inputIndex in inputIndexes) {
                val index = if (inputIndex is Int) inputIndex else inputIndex.toString().toInt()
                if (index < 0 || index >= size) {
                    callback.reject(
                        "index_out_of_bounds",
                        "One or more indexes was out of bounds"
                    )
                    return@launch
                }
                indexes.add(index)
            }
            musicService.remove(indexes)
        }
        callback.resolve(null)
    }

    @ReactMethod
    fun updateMetadataForTrack(index: Int, map: ReadableMap?, callback: Promise) =
        scope.launch {
            if (verifyServiceBoundOrReject(callback)) return@launch

            if (index < 0 || index >= musicService.tracks.size) {
                callback.reject("index_out_of_bounds", "The index is out of bounds")
            } else {
                val context: ReactContext = context
                val track = musicService.tracks[index]
                track.setMetadata(context, Arguments.toBundle(map), musicService.ratingType)
                musicService.updateMetadataForTrack(index, track)

                callback.resolve(null)
            }
        }

    @ReactMethod
    fun updateNowPlayingMetadata(map: ReadableMap?, callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        if (musicService.tracks.isEmpty())
            callback.reject("no_current_item", "There is no current item in the player")

        val context: ReactContext = context
        val metadata = Arguments.toBundle(map)
        musicService.updateNotificationMetadata(
            metadata?.getString("title"),
            metadata?.getString("artist"),
            BundleUtils.getUri(context, metadata, "artwork")?.toString()
        )

        callback.resolve(null)
    }

    @ReactMethod
    fun clearNowPlayingMetadata(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        if (musicService.tracks.isEmpty())
            callback.reject("no_current_item", "There is no current item in the player")

        musicService.clearNotificationMetadata()
        callback.resolve(null)
    }

    @ReactMethod
    fun removeUpcomingTracks(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService.removeUpcomingTracks()
        callback.resolve(null)
    }

    @ReactMethod
    fun skip(index: Int, initialTime: Float, callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService.skip(index)

        if (initialTime >= 0) {
            musicService.seekTo(initialTime)
        }

        callback.resolve(null)
    }

    @ReactMethod
    fun skipToNext(initialTime: Float, callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService.skipToNext()

        if (initialTime >= 0) {
            musicService.seekTo(initialTime)
        }

        callback.resolve(null)
    }

    @ReactMethod
    fun skipToPrevious(initialTime: Float, callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService.skipToPrevious()

        if (initialTime >= 0) {
            musicService.seekTo(initialTime)
        }

        callback.resolve(null)
    }

    @ReactMethod
    fun reset(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService.clear()
        musicService.stop()
        callback.resolve(null)
    }

    @ReactMethod
    fun play(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService.play()
        callback.resolve(null)
    }

    @ReactMethod
    fun pause(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService.pause()
        callback.resolve(null)
    }

    @ReactMethod
    fun stop(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService.stop()
        callback.resolve(null)
    }

    @ReactMethod
    fun seekTo(seconds: Float, callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService.seekTo(seconds)
        callback.resolve(null)
    }

    @ReactMethod
    fun seekBy(offset: Float, callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService.seekBy(offset)
        callback.resolve(null)
    }

    @ReactMethod
    fun retry(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService.retry()
        callback.resolve(null)
    }

    @ReactMethod
    fun setVolume(volume: Float, callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService.setVolume(volume)
        callback.resolve(null)
    }

    @ReactMethod
    fun getVolume(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        callback.resolve(musicService.getVolume())
    }

    @ReactMethod
    fun setRate(rate: Float, callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService.setRate(rate)
        callback.resolve(null)
    }

    @ReactMethod
    fun getRate(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        callback.resolve(musicService.getRate())
    }

    @ReactMethod
    fun setRepeatMode(mode: Int, callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService.setRepeatMode(RepeatMode.fromOrdinal(mode))
        callback.resolve(null)
    }

    @ReactMethod
    fun getRepeatMode(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        callback.resolve(musicService.getRepeatMode().ordinal)
    }

    @ReactMethod
    fun setPlayWhenReady(playWhenReady: Boolean, callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService.playWhenReady = playWhenReady
        callback.resolve(null)
    }

    @ReactMethod
    fun getPlayWhenReady(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        callback.resolve(musicService.playWhenReady)
    }

    @ReactMethod
    fun getTrack(index: Int, callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        if (index >= 0 && index < musicService.tracks.size) {
            callback.resolve(Arguments.fromBundle(musicService.tracks[index].originalItem))
        } else {
            callback.resolve(null)
        }
    }

    @ReactMethod
    fun getQueue(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        callback.resolve(Arguments.fromList(musicService.tracks.map { it.originalItem }))
    }

    @ReactMethod
    fun setQueue(data: ReadableArray?, callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        try {
            musicService.clear()
            musicService.add(readableArrayToTrackList(data))
            callback.resolve(null)
        } catch (exception: Exception) {
            rejectWithException(callback, exception)
        }
    }

    @ReactMethod
    fun getActiveTrackIndex(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch
        callback.resolve(
            if (musicService.tracks.isEmpty()) null else musicService.getCurrentTrackIndex()
        )
    }

    @ReactMethod
    fun getActiveTrack(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch
        callback.resolve(
            if (musicService.tracks.isEmpty()) null
            else Arguments.fromBundle(
                musicService.tracks[musicService.getCurrentTrackIndex()].originalItem
            )
        )
    }

    @ReactMethod
    fun getDuration(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        callback.resolve(musicService.getDurationInSeconds())
    }

    @ReactMethod
    fun getBufferedPosition(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        callback.resolve(musicService.getBufferedPositionInSeconds())
    }

    @ReactMethod
    fun getPosition(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        callback.resolve(musicService.getPositionInSeconds())
    }

    @ReactMethod
    fun getProgress(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch
        var bundle = Bundle()
        bundle.putDouble("duration", musicService.getDurationInSeconds());
        bundle.putDouble("position", musicService.getPositionInSeconds());
        bundle.putDouble("buffered", musicService.getBufferedPositionInSeconds());
        callback.resolve(Arguments.fromBundle(bundle))
    }

    @ReactMethod
    fun getPlaybackState(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch
        callback.resolve(Arguments.fromBundle(musicService.getPlayerStateBundle(musicService.state)))
    }
}
