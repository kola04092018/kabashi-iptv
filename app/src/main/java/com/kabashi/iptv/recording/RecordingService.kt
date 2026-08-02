package com.kabashi.iptv.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kabashi.iptv.R
import com.kabashi.iptv.ui.HomeActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingJob: Job? = null
    private var connection: HttpURLConnection? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "IPTV recording" }
        if (url.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, notification("Recording $title", "Saving authorized live stream…", true))
        recordingJob?.cancel()
        recordingJob = scope.launch { record(url, title) }
        return START_NOT_STICKY
    }

    private fun record(url: String, title: String) {
        val directory = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Recordings").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(directory, "${sanitize(title)}_$timestamp.ts")
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "KABASHI-IPTV/1.0 AndroidTV")
            }
            val active = connection ?: error("Unable to create connection")
            if (active.responseCode !in 200..299) error("Provider returned HTTP ${active.responseCode}")
            active.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(
                NOTIFICATION_ID,
                notification("Recording saved", file.absolutePath, false)
            )
        } catch (_: CancellationException) {
            // A user-requested stop keeps the partial transport stream.
        } catch (error: Throwable) {
            if (file.exists() && file.length() == 0L) file.delete()
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification("Recording failed", error.message ?: "Unknown recording error", false)
            )
        } finally {
            connection?.disconnect()
            connection = null
            stopForeground(false)
            stopSelf()
        }
    }

    private fun stopRecording() {
        recordingJob?.cancel()
        connection?.disconnect()
        recordingJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(title: String, text: String, ongoing: Boolean): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .apply { if (ongoing) addAction(R.drawable.ic_stop, "Stop", stopIntent) }
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "IPTV recordings",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        connection?.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun sanitize(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._ -]"), "_")
        .trim()
        .take(70)
        .ifBlank { "recording" }

    companion object {
        private const val CHANNEL_ID = "kabashi_recording"
        private const val NOTIFICATION_ID = 8081
        private const val ACTION_STOP = "com.kabashi.iptv.STOP_RECORDING"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, url: String, title: String) {
            val intent = Intent(context, RecordingService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
