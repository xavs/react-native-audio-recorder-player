package com.dooboolab.audiorecorderplayer

import android.Manifest
import android.app.*
import android.content.pm.PackageManager
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter
import com.facebook.react.modules.core.PermissionListener
import java.io.IOException
import java.util.*
import kotlin.math.log10
import com.facebook.react.bridge.ReadableMap

import com.dooboolab.audiorecorderplayer.NotificationConfig

object ReactContextHolder { // here to make context available to RecordingService
    var reactContext: ReactApplicationContext? = null
}

class RNAudioRecorderPlayerModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext), PermissionListener {
    private val defaultAudioSet: HashMap<String, Any> = hashMapOf(
        "AudioEncoderAndroid" to MediaRecorder.AudioEncoder.AAC,
        "AudioSourceAndroid" to MediaRecorder.AudioSource.CAMCORDER,
        "OutputFormatAndroid" to MediaRecorder.OutputFormat.MPEG_4,
        "SampleRate" to 44100,
        "Channels" to 2,
        "BitRate" to 128000
    )
    
    private var audioFileURL = ""
    private var subsDurationMillis = 500
    private var _meteringEnabled = false    
    private var mediaPlayer: MediaPlayer? = null
    private var recorderRunnable: Runnable? = null
    private var mTask: TimerTask? = null
    private var mTimer: Timer? = null
    private var pausedRecordTime = 0L
    private var totalPausedRecordTime = 0L
    private var notificationConfig = NotificationConfig()
    private var audioSet : HashMap<String, Any> = defaultAudioSet
    var recordHandler: Handler? = Handler(Looper.getMainLooper())
    

    init{
        ReactContextHolder.reactContext = reactContext
    }
    
    private var notificationManager: NotificationManager? = null        

    override fun getName(): String {
        return tag
    }
    @ReactMethod
    fun updateNotificationConfig(config: ReadableMap) {
        config.getString("channelId")?.let { notificationConfig = notificationConfig.copy(channelId = it) }
        config.getString("channelName")?.let { notificationConfig = notificationConfig.copy(channelName = it) }
        config.getString("channelDescription")?.let { notificationConfig = notificationConfig.copy(channelDescription = it) }
        config.getInt("notificationId")?.let { notificationConfig = notificationConfig.copy(notificationId = it) }
        config.getString("notificationTitle")?.let { notificationConfig = notificationConfig.copy(notificationTitle = it) }
        config.getString("notificationText")?.let { notificationConfig = notificationConfig.copy(notificationText = it) }
        config.getInt("notificationIcon")?.let { notificationConfig = notificationConfig.copy(notificationIcon = it) }
        config.getInt("notificationPriority")?.let { notificationConfig = notificationConfig.copy(notificationPriority = it) }
    }

    private fun startRecordingService() {
        Log.e("RNAudioRecorderPlayerModule", "startRecordingService contex? $reactContext")
        val intent = Intent(reactContext, AudioRecordingService::class.java).apply {
            action = "START_RECORDING"
            putExtra("audioFileURL", audioFileURL)            
            putExtra("meteringEnabled", _meteringEnabled)
            putExtra("notificationConfig", notificationConfig)
            putExtra("audioSet", audioSet )    
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            reactContext.startForegroundService(intent)
        } else {
            reactContext.startService(intent)
        }
    }

    private fun stopRecordingService() {
        val intent = Intent(reactContext, AudioRecordingService::class.java).apply {
            action = "STOP_RECORDING"
        }
        reactContext.stopService(intent)
    }
    

    @ReactMethod
    fun startRecorder(path: String, audioSet: ReadableMap?, meteringEnabled: Boolean, promise: Promise) {
        Log.e("RNAudioRecorderPlayerModule", "start recorder")
        if ( audioSet != null ) this.audioSet = audioSet. toHashMap()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val permissions = if (Build.VERSION.SDK_INT < 29) {
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                } else {
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO,                        
                        Manifest.permission.WAKE_LOCK,
                        Manifest.permission.FOREGROUND_SERVICE,
                        Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
                    )
                }
                Log.e("RNAudioRecorderPlayerModule", "permissions: ${permissions.joinToString()}")
                if (!hasPermissions(permissions)) {
                    ActivityCompat.requestPermissions((currentActivity)!!, permissions, 0)
                    promise.reject("No permission granted.", "Try again after adding permission.")
                    return
                }
            }
        } catch (ne: NullPointerException) {
            Log.w(tag, ne.toString())
            promise.reject("No permission granted.", "Try again after adding permission.")
            return
        }        
        var outputFormat = if (audioSet != null && audioSet.hasKey("OutputFormatAndroid"))
            audioSet.getInt("OutputFormatAndroid")
        else
            MediaRecorder.OutputFormat.MPEG_4
        audioFileURL = if (path == "DEFAULT") "${reactContext.cacheDir}/sound.${defaultFileExtensions[outputFormat]}" else path
        _meteringEnabled = meteringEnabled

        try {
            startRecordingService()            
            promise.resolve("file:///$audioFileURL")
        } catch (e: Exception) {
            Log.e(tag, "Exception: ", e)
            stopRecordingService()
            promise.reject("startRecorder", e.message)
        }
    }


    private fun hasPermissions(permissions: Array<String>): Boolean {
        return permissions.all {
            ActivityCompat.checkSelfPermission(reactContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }


    @ReactMethod
    fun resumeRecorder(promise: Promise) {      
        try {
            val intent = Intent(reactContext, AudioRecordingService::class.java).apply {
                action = "RESUME_RECORDING"
            }
            reactContext.startService(intent)
            promise.resolve("Recorder resumed.")
        } catch (e: Exception) {
            Log.e(tag, "resumeRecorder exception: " + e.message)
            promise.reject("resumeRecorder", e.message)
        }
        
    }

    @ReactMethod
    fun pauseRecorder(promise: Promise) {        
        try {
            val intent = Intent(reactContext, AudioRecordingService::class.java).apply {
                action = "PAUSE_RECORDING"
            }
            reactContext.startService(intent)
            promise.resolve("Recorder paused.")
        } catch (e: Exception) {
            Log.e(tag, "pauseRecorder exception: " + e.message)
            promise.reject("pauseRecorder", e.message)
        }
    }

    @ReactMethod
    fun stopRecorder(promise: Promise) {
        try {
            val intent = Intent(reactContext, AudioRecordingService::class.java).apply {
                action = "STOP_RECORDING"
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reactContext.startForegroundService(intent)
            } else {
                reactContext.startService(intent)
            }
            
            promise.resolve("file:///$audioFileURL")
        } catch (e: Exception) {
            Log.e(tag, "stopRecorder exception: ${e.message}")
            promise.reject("stopRecorder", e.message)
        }
    }

    @ReactMethod
    fun setVolume(volume: Double, promise: Promise) {
        if (mediaPlayer == null) {
            promise.reject("setVolume", "player is null.")
            return
        }

        val mVolume = volume.toFloat()
        mediaPlayer!!.setVolume(mVolume, mVolume)
        promise.resolve("set volume")
    }

    @ReactMethod
    fun setPlaybackSpeed(playbackSpeed: Float, promise: Promise) {
        if (mediaPlayer == null) {
            promise.reject("setPlaybackSpeed", "player is null.")
            return
        }
        mediaPlayer!!.playbackParams = mediaPlayer!!.playbackParams.setSpeed(playbackSpeed)
        promise.resolve("setPlaybackSpeed")
    }

    @ReactMethod
    fun startPlayer(path: String, httpHeaders: ReadableMap?, promise: Promise) {
        if (mediaPlayer != null) {
            val isPaused = !mediaPlayer!!.isPlaying && mediaPlayer!!.currentPosition > 1

            if (isPaused) {
                mediaPlayer!!.start()
                promise.resolve("player resumed.")
                return
            }

            Log.e(tag, "Player is already running. Stop it first.")
            promise.reject("startPlay", "Player is already running. Stop it first.")
            return
        } else {
            mediaPlayer = MediaPlayer()
        }

        try {
            if ((path == "DEFAULT")) {
                mediaPlayer!!.setDataSource("${reactContext.cacheDir}/$defaultFileName")
            } else {
                if (httpHeaders != null) {
                    val headers: MutableMap<String, String?> = HashMap<String, String?>()
                    val iterator = httpHeaders.keySetIterator()
                    while (iterator.hasNextKey()) {
                        val key = iterator.nextKey()
                        headers.put(key, httpHeaders.getString(key))
                    }
                    mediaPlayer!!.setDataSource(currentActivity!!.applicationContext, Uri.parse(path), headers)
                } else {
                    mediaPlayer!!.setDataSource(path)
                }
            }

            mediaPlayer!!.setOnPreparedListener { mp ->
                Log.d(tag, "Mediaplayer prepared and start")
                mp.start()
                /**
                 * Set timer task to send event to RN.
                 */
                mTask = object : TimerTask() {
                    override fun run() {
                        try {
                            val obj = Arguments.createMap()
                            obj.putInt("duration", mp.duration)
                            obj.putInt("currentPosition", mp.currentPosition)
                            obj.putBoolean("isFinished", false);
                            sendEvent(reactContext, "rn-playback", obj)
                        } catch (e: IllegalStateException) {
                            // IllegalStateException 처리
                            Log.e(tag, "Mediaplayer error: ${e.message}")
                        }
                    }
                }

                mTimer = Timer()
                mTimer!!.schedule(mTask, 0, subsDurationMillis.toLong())
                val resolvedPath = if (((path == "DEFAULT"))) "${reactContext.cacheDir}/$defaultFileName" else path
                promise.resolve(resolvedPath)
            }

            /**
             * Detect when finish playing.
             */
            mediaPlayer!!.setOnCompletionListener { mp ->
                /**
                 * Send last event
                 */
                val obj = Arguments.createMap()
                obj.putInt("duration", mp.duration)
                obj.putInt("currentPosition", mp.currentPosition)
                obj.putBoolean("isFinished", true);
                sendEvent(reactContext, "rn-playback", obj)
                /**
                 * Reset player.
                 */
                Log.d(tag, "Plays completed.")
                mTimer?.cancel()
                mp.stop()
                mp.reset()
                mp.release()
                mediaPlayer = null
            }

            mediaPlayer!!.prepare()
        } catch (e: IOException) {
            Log.e(tag, "startPlay() io exception")
            promise.reject("startPlay", e.message)
        } catch (e: NullPointerException) {
            Log.e(tag, "startPlay() null exception")
        }
    }

    @ReactMethod
    fun resumePlayer(promise: Promise) {
        if (mediaPlayer == null) {
            promise.reject("resume", "Mediaplayer is null on resume.")
            return
        }

        if (mediaPlayer!!.isPlaying) {
            promise.reject("resume", "Mediaplayer is already running.")
            return
        }

        try {
            mediaPlayer!!.seekTo(mediaPlayer!!.currentPosition)
            mediaPlayer!!.start()
            promise.resolve("resume player")
        } catch (e: Exception) {
            Log.e(tag, "Mediaplayer resume: " + e.message)
            promise.reject("resume", e.message)
        }
    }

    @ReactMethod
    fun pausePlayer(promise: Promise) {
        if (mediaPlayer == null) {
            promise.reject("pausePlay", "Mediaplayer is null on pause.")
            return
        }

        try {
            mediaPlayer!!.pause()
            promise.resolve("pause player")
        } catch (e: Exception) {
            Log.e(tag, "pausePlay exception: " + e.message)
            promise.reject("pausePlay", e.message)
        }
    }

    @ReactMethod
    fun seekToPlayer(time: Double, promise: Promise) {
        if (mediaPlayer == null) {
            promise.reject("seekTo", "Mediaplayer is null on seek.")
            return
        }

        mediaPlayer!!.seekTo(time.toInt())
        promise.resolve("pause player")
    }

    private fun sendEvent(reactContext: ReactContext,
                          eventName: String,
                          params: WritableMap?) {
        reactContext
                .getJSModule<RCTDeviceEventEmitter>(RCTDeviceEventEmitter::class.java)
                .emit(eventName, params)
    }

    @ReactMethod
    fun stopPlayer(promise: Promise) {
        if (mTimer != null) {
            mTimer!!.cancel()
        }

        if (mediaPlayer == null) {
            promise.resolve("Already stopped player")
            return
        }

        try {
            mediaPlayer!!.stop()
            mediaPlayer!!.reset()
            mediaPlayer!!.release()
            mediaPlayer = null
            promise.resolve("stopped player")
        } catch (e: Exception) {
            Log.e(tag, "stopPlay exception: " + e.message)
            promise.reject("stopPlay", e.message)
        }
    }

    @ReactMethod
    fun setSubscriptionDuration(sec: Double, promise: Promise) {
        subsDurationMillis = (sec * 1000).toInt()
        promise.resolve("setSubscriptionDuration: $subsDurationMillis")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray): Boolean {
        var requestRecordAudioPermission: Int = 200

        when (requestCode) {
            requestRecordAudioPermission -> if (grantResults[0] == PackageManager.PERMISSION_GRANTED) return true
        }

        return false
    }

    companion object {
        private var tag = "RNAudioRecorderPlayer"
        private var defaultFileName = "sound.mp4"
        private var defaultFileExtensions = listOf(
            "mp4", // DEFAULT = 0
            "3gp", // THREE_GPP
            "mp4", // MPEG_4
            "amr", // AMR_NB
            "amr", // AMR_WB
            "aac", // AAC_ADIF
            "aac", // AAC_ADTS
            "rtp", // OUTPUT_FORMAT_RTP_AVP
            "ts",  // MPEG_2_TSMPEG_2_TS
            "webm",// WEBM
            "xxx", // UNUSED
            "ogg", // OGG
        )
    }
}
