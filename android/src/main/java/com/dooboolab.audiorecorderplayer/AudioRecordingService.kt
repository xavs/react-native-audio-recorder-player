package com.dooboolab.audiorecorderplayer

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.modules.core.DeviceEventManagerModule
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.facebook.react.bridge.ReadableMap


import kotlin.math.log10
import android.os.PowerManager

class AudioRecordingService : Service() {
    private var mediaRecorder: MediaRecorder? = null
    private var recordHandler: Handler? = null
    private var recorderRunnable: Runnable? = null
    private var audioFileURL: String = ""
    private var meteringEnabled: Boolean = false
    private var subsDurationMillis: Int = 200
    private var totalPausedRecordTime: Long = 0
    private var pausedRecordTime: Long = 0
    private var audioSet: ReadableMap? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()        
        recordHandler = Handler(Looper.getMainLooper())
        createNotificationChannel()
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioRecordingService::WakeLock")
        wakeLock?.acquire()
    }
    private fun releaseWakeLock() {
        wakeLock?.let { 
            if ( it.isHeld ){
                it.release()
            }
         }
         wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "recording_channel",
                "Recording Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Audio recording in progress"
                setSound(null,null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).apply {
                createNotificationChannel(channel)
            }
        }
    }

    private fun startRecording(reactContext: ReactContext) {        
        Log.i("AudioRecordingService", "startRecording file: $audioFileURL")
        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }


            mediaRecorder?.apply {
                setAudioSource( audioSet?.getString("AudioSourceAndroid")?.toInt() ?: MediaRecorder.AudioSource.CAMCORDER)
                setOutputFormat( audioSet?.getInt("OutputFormatAndroid") ?: MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder( audioSet?.getInt("AudioEncoderAndroid") ?: MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioFileURL)
                prepare()
                start()
            }

            if (meteringEnabled) {
                var lastAmplitude = 0
                var hasRecordingStarted = false
                var startTime = 0L
                Log.d("AudioRecordingService", "metering enabled? $meteringEnabled hasRecordingStarted? $hasRecordingStarted")
                recorderRunnable = object : Runnable {
                    override fun run() {
                        try {                            
                            if (mediaRecorder != null) {
                                val amplitude = mediaRecorder?.maxAmplitude ?: 0
                                
                                // Check if recording should start now
                                if (!hasRecordingStarted && amplitude > 0) {
                                    Log.d("AudioRecordingService", "Recording started amplitude $amplitude")
                                    hasRecordingStarted = true
                                    startTime = SystemClock.elapsedRealtime()
                                    
                                    // Send the first valid amplitude reading
                                    val db = 20 * log10(amplitude.toDouble() / 32767.0)
                                    val obj = Arguments.createMap()
                                    obj.putInt("currentPosition", 0) // Start with 0 since we just detected sound
                                    obj.putDouble("currentMetering", db)
                                    
                                    reactContext
                                        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                                        .emit("rn-recordback", obj)
                                    Log.d("AudioRecordingService", "First reading - currentPosition: 0, currentMetering: $db")
                                } else if (hasRecordingStarted) {                                    
                                    val currentTime = SystemClock.elapsedRealtime()
                                    val currentPosition = ((currentTime - startTime - totalPausedRecordTime) / 1000).toInt()
                                    val db = if (amplitude == 0) 0.0 else 20 * log10(amplitude.toDouble() / 32767.0)
            
                                    val obj = Arguments.createMap()
                                    obj.putInt("currentPosition", currentPosition)
                                    obj.putDouble("currentMetering", db)
                                    
                                    reactContext
                                        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                                        .emit("rn-recordback", obj)
                                    //Log.d("AudioRecordingService", "currentPosition: $currentPosition, currentMetering: $db")
                                }
                                lastAmplitude = amplitude
                            }
                            
                            recordHandler!!.postDelayed(this, subsDurationMillis.toLong())
                        } catch (e: Exception) {
                            Log.e("AudioRecordingService", "Error in recorderRunnable: ${e.message}")
                        }
                    }
                }
                recordHandler!!.post(recorderRunnable!!)
            }
        } catch (e: Exception) {
            Log.e("AudioRecordingService", "Error starting recording: ${e.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("AudioRecordingService", "onStartCommand ACTION: ${intent?.action}")
        when (intent?.action) {
            "START_RECORDING" -> {
                val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(this, "recording_channel")
                } else {
                    Notification.Builder(this)
                }.apply {
                    setContentTitle("Recording Audio")
                    setContentText("Recording in progress")
                    setSmallIcon(android.R.drawable.ic_btn_speak_now)
                }.build()

                startForeground(1, notification)
                
                audioFileURL = intent.getStringExtra("audioFileURL") ?: ""
                meteringEnabled = intent.getBooleanExtra("meteringEnabled", true)
                val bundle = intent.getBundleExtra("audioSet")
                val audioSet = bundle?.let {
                    val map = HashMap<String, Any?>()
                    for (key in it.keySet()) {
                        map[key] = it.get(key)
                    }
                    map
                }
                Log.d("AudioRecordingService", "audioSet: $audioSet")
                Log.d("AudioRecordingService", "audioFileURL: $audioFileURL")
                Log.d("AudioRecordingService", "meteringEnabled: $meteringEnabled")
                var reactContext = ReactContextHolder.reactContext
                Log.d("AudioRecordingService", "metering? $meteringEnabled reactContext: $reactContext.")
                if (reactContext != null) {
                    startRecording(reactContext)
                }
            }
            "PAUSE_RECORDING" -> {
                Log.i("AudioRecordingService", "PAUSE_RECORDING")
                mediaRecorder?.pause()
                pausedRecordTime = SystemClock.elapsedRealtime()
                recorderRunnable?.let { recordHandler?.removeCallbacks(it) }
            }
            "RESUME_RECORDING" -> {
                Log.i ("AudioRecordingService", "RESUME_RECORDING")
                mediaRecorder?.resume()
                totalPausedRecordTime += SystemClock.elapsedRealtime() - pausedRecordTime
                recorderRunnable?.let { recordHandler?.postDelayed(it, subsDurationMillis.toLong()) }
            }
            "STOP_RECORDING" -> {                
                stopRecording()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun stopRecording() {
        Log.i("AudioRecordingService", "stopRecording, remove callbacks from $recordHandler")
        recorderRunnable?.let { recordHandler?.removeCallbacks(it) }
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
            mediaRecorder = null
        } catch (e: Exception) {
            Log.e("AudioRecordingService", "Error stopping recording: ${e.message}")
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        stopRecording()
        super.onDestroy()
    }
}