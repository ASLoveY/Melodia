package com.lin0721.linmusic.validation

import android.app.*
import android.content.*
import android.media.*
import android.media.projection.*
import android.os.*
import java.io.ByteArrayOutputStream

/** Debug-only harness: capture this app's rendered audio, never another app's output. */
class AudioCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
        startActivityForResult(intent, 71)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 71 && resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, AudioCaptureService::class.java).putExtra("result", resultCode).putExtra("data", data))
        }
    }
}

class AudioCaptureService : Service() {
    companion object {
        @Volatile var ready = false
        @Volatile var failure: String? = null
        @Volatile private var recording = false
        private val bytes = ByteArrayOutputStream()
        fun snapshot(): ByteArray = synchronized(bytes) { bytes.toByteArray() }
    }
    private var recorder: AudioRecord? = null
    private var projection: MediaProjection? = null
    private var worker: Thread? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel("audio_validation", "音频测试", NotificationManager.IMPORTANCE_LOW))
        startForeground(7901, Notification.Builder(this, "audio_validation").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("正在验证应用音频输出").build())
        if (Build.VERSION.SDK_INT < 29) { failure = "Audio capture requires Android 10"; stopSelf(); return START_NOT_STICKY }
        try {
            @Suppress("DEPRECATION") val data = intent!!.getParcelableExtra<Intent>("data")!!
            projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(intent.getIntExtra("result", Activity.RESULT_CANCELED), data)
            projection!!.registerCallback(object : MediaProjection.Callback() { override fun onStop() { recording = false } }, Handler(mainLooper))
            val capture = AudioPlaybackCaptureConfiguration.Builder(projection!!).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUid(Process.myUid()).build()
            @Suppress("MissingPermission")
            val audio = AudioRecord.Builder().setAudioPlaybackCaptureConfig(capture)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(48000).build()
            recorder = audio
            synchronized(bytes) { bytes.reset() }
            failure = null; recording = true
            audio.startRecording(); ready = true
            worker = Thread {
                val buffer = ByteArray(9600)
                while (recording) {
                    val count = audio.read(buffer, 0, buffer.size)
                    if (count > 0) synchronized(bytes) { bytes.write(buffer, 0, count) }
                }
            }.apply { start() }
        } catch (error: Exception) { failure = error.toString(); ready = false; stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        recording = false; ready = false
        runCatching { recorder?.stop() }
        worker?.join(1000)
        recorder?.release(); projection?.stop()
        super.onDestroy()
    }
}
