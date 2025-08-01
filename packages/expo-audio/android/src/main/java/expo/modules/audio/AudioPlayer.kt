package expo.modules.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.sharedobjects.SharedRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private const val PLAYBACK_STATUS_UPDATE = "playbackStatusUpdate"
private const val AUDIO_SAMPLE_UPDATE = "audioSampleUpdate"

@UnstableApi
class AudioPlayer(
  context: Context,
  appContext: AppContext,
  source: MediaSource?,
  private val updateInterval: Double
) : SharedRef<ExoPlayer>(
  ExoPlayer.Builder(context)
    .setLooper(context.mainLooper)
    .setAudioAttributes(AudioAttributes.DEFAULT, false)
    .build(),
  appContext
) {
  val id = UUID.randomUUID().toString()
  var preservesPitch = false
  var isPaused = false
  var isMuted = false
  var previousVolume = 1f

  private var playerScope = CoroutineScope(Dispatchers.Default)
  private var samplingEnabled = false
  private var visualizer: Visualizer? = null
  private var playing = false

  private var updateJob: Job? = null

  val currentTime get() = ref.currentPosition / 1000f
  val duration get() = if (ref.duration != C.TIME_UNSET) ref.duration / 1000f else 0f

  init {
    addPlayerListeners()
    source?.let {
      setMediaSource(source)
    }
  }

  fun setVolume(volume: Float?) = appContext?.mainQueue?.launch {
    val boundedVolume = volume?.coerceIn(0f, 1f) ?: 1f
    if (isMuted) {
      if (boundedVolume > 0f) {
        previousVolume = boundedVolume
      }
      ref.volume = 0f
    } else {
      ref.volume = if (boundedVolume > 0) boundedVolume else previousVolume
    }
  }

  fun setMediaSource(source: MediaSource) {
    ref.setMediaSource(source)
    ref.prepare()
    startUpdating()
  }

  private fun startUpdating() {
    updateJob = flow {
      while (true) {
        emit(Unit)
        delay(updateInterval.toLong())
      }
    }
      .onEach {
        if (playing) {
          sendPlayerUpdate()
        }
      }
      .launchIn(playerScope)
  }

  private fun addPlayerListeners() = ref.addListener(object : Player.Listener {
    override fun onIsPlayingChanged(isPlaying: Boolean) {
      playing = isPlaying
      playerScope.launch {
        sendPlayerUpdate(mapOf("playing" to isPlaying))
      }
    }

    override fun onIsLoadingChanged(isLoading: Boolean) {
      playerScope.launch {
        sendPlayerUpdate(mapOf("isLoaded" to !isLoading))
      }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      playerScope.launch {
        sendPlayerUpdate(mapOf("playbackState" to playbackStateToString(playbackState)))
      }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
      playerScope.launch {
        sendPlayerUpdate()
      }
    }
  })

  fun setSamplingEnabled(enabled: Boolean) {
    appContext?.reactContext?.let {
      if (ContextCompat.checkSelfPermission(it, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        Log.d(TAG, "\'android.permission.RECORD_AUDIO\' is required to use audio sampling. Please request this permission and try again.")
        return
      }
    }

    samplingEnabled = enabled
    if (enabled) {
      createVisualizer()
    } else {
      visualizer?.release()
      visualizer = null
    }
  }

  private fun extractAmplitudes(chunk: ByteArray): List<Float> = chunk.map { byte ->
    val unsignedByte = byte.toInt() and 0xFF
    ((unsignedByte - 128).toDouble() / 128.0).toFloat()
  }

  fun currentStatus(): Map<String, Any?> {
    val isMuted = ref.volume == 0f
    val isLooping = ref.repeatMode == Player.REPEAT_MODE_ONE
    val isLoaded = ref.playbackState == Player.STATE_READY
    val isBuffering = ref.playbackState == Player.STATE_BUFFERING

    return mapOf(
      "id" to id,
      "currentTime" to currentTime,
      "playbackState" to playbackStateToString(ref.playbackState),
      "timeControlStatus" to if (ref.isPlaying) "playing" else "paused",
      "reasonForWaitingToPlay" to null,
      "mute" to isMuted,
      "duration" to duration,
      "playing" to ref.isPlaying,
      "loop" to isLooping,
      "didJustFinish" to (ref.playbackState == Player.STATE_ENDED),
      "isLoaded" to if (ref.playbackState == Player.STATE_ENDED) true else isLoaded,
      "playbackRate" to ref.playbackParameters.speed,
      "shouldCorrectPitch" to preservesPitch,
      "isBuffering" to isBuffering
    )
  }

  private suspend fun sendPlayerUpdate(map: Map<String, Any?>? = null) =
    withContext(Dispatchers.Main) {
      val data = currentStatus()
      val body = map?.let { data + it } ?: data
      emit(PLAYBACK_STATUS_UPDATE, body)
    }

  private fun sendAudioSampleUpdate(sample: List<Float>) {
    val body = mapOf(
      "channels" to listOf(
        mapOf("frames" to sample)
      ),
      "timestamp" to ref.currentPosition
    )
    emit(AUDIO_SAMPLE_UPDATE, body)
  }

  private fun playbackStateToString(state: Int): String {
    return when (state) {
      Player.STATE_READY -> "ready"
      Player.STATE_BUFFERING -> "buffering"
      Player.STATE_IDLE -> "idle"
      Player.STATE_ENDED -> "ended"
      else -> "unknown"
    }
  }

  private fun createVisualizer() {
    // It must only be created once, otherwise the app will crash
    if (visualizer == null) {
      visualizer = Visualizer(ref.audioSessionId).apply {
        captureSize = Visualizer.getCaptureSizeRange()[1]
        setDataCaptureListener(
          object : Visualizer.OnDataCaptureListener {
            override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
              waveform?.let {
                if (samplingEnabled && ref.isPlaying) {
                  val data = extractAmplitudes(it)
                  sendAudioSampleUpdate(data)
                }
              }
            }

            override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) = Unit
          },
          Visualizer.getMaxCaptureRate() / 2,
          true,
          false
        )
        enabled = true
      }
    }
  }

  override fun sharedObjectDidRelease() {
    appContext?.mainQueue?.launch {
      playerScope.cancel()
      visualizer?.release()
      ref.release()
    }
  }

  companion object {
    val TAG = AudioPlayer::class.simpleName
  }
}
