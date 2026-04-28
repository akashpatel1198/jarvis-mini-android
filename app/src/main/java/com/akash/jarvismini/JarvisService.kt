package com.akash.jarvismini

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.sqrt

class JarvisService : Service() {
    companion object {
        const val CHANNEL_ID = "jarvis_listening"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.akash.jarvismini.STOP"
        const val DEFAULT_INACTIVITY_MS = 60L * 60L * 1000L  // 1 hour
        private const val TAG = "JarvisService"

        // Audio + detection tuning.
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 1280  // 80 ms @ 16 kHz
        private const val WAKE_THRESHOLD = 0.5f
        // After a wake event, ignore further wake activations for ~2s while we capture.
        private const val WAKE_COOLDOWN_CHUNKS = 25
        // Silence detection during command capture.
        private const val SILENCE_RMS = 250
        private const val SILENCE_CHUNKS_TO_END = 18  // ~1.4 s of silence
        private const val MAX_COMMAND_CHUNKS = 150     // ~12 s hard cap
        // Pre-roll: include some audio before the wake (catches "Hey Jarvis what's…").
        private const val PRE_ROLL_CHUNKS = 16  // ~1.28 s

        private val _running = mutableStateOf(false)
        val running: State<Boolean> = _running

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, JarvisService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisService::class.java))
        }
    }

    private enum class Mode { LISTENING, CAPTURING, PROCESSING }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoOff = Runnable {
        Log.d(TAG, "auto-off timer fired, stopping")
        stopSelf()
    }

    @Volatile private var captureRunning = false
    private var captureThread: Thread? = null

    private var detector: WakeWordDetector? = null
    private var api: JarvisApi? = null
    private var tts: JarvisTts? = null
    private var spotify: SpotifyController? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        _running.value = true
        rescheduleAutoOff()
        startCaptureLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        stopCaptureLoop()
        mainHandler.removeCallbacks(autoOff)
        _running.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCaptureLoop() {
        if (captureRunning) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted; skipping capture loop")
            return
        }
        captureRunning = true
        captureThread = thread(name = "jarvis-capture") {
            try {
                detector = WakeWordDetector(this)
                api = JarvisApi()
                tts = JarvisTts(this)
                spotify = SpotifyController(this)
                runCapture()
            } catch (t: Throwable) {
                Log.e(TAG, "capture loop crashed", t)
            } finally {
                detector?.close(); detector = null
                tts?.shutdown(); tts = null
                spotify?.shutdown(); spotify = null
                api = null
            }
        }
    }

    private fun stopCaptureLoop() {
        captureRunning = false
        captureThread?.join(1000)
        captureThread = null
    }

    private fun runCapture() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, CHUNK_SAMPLES * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            record.release()
            return
        }
        record.startRecording()
        Log.d(TAG, "capture loop started")

        var mode = Mode.LISTENING
        var wakeCooldown = 0
        val preRoll = ArrayDeque<ShortArray>()
        val commandBuffer = mutableListOf<ShortArray>()
        var silenceChunks = 0

        try {
            val chunk = ShortArray(CHUNK_SAMPLES)
            while (captureRunning) {
                var read = 0
                while (read < CHUNK_SAMPLES && captureRunning) {
                    val n = record.read(chunk, read, CHUNK_SAMPLES - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < CHUNK_SAMPLES) continue

                // Always keep a rolling pre-roll buffer, regardless of mode.
                preRoll.addLast(chunk.copyOf())
                while (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()

                when (mode) {
                    Mode.LISTENING -> {
                        if (wakeCooldown > 0) {
                            wakeCooldown--
                            detector?.feed(chunk)  // keep state warm
                            continue
                        }
                        val score = detector?.feed(chunk) ?: continue
                        if (score >= WAKE_THRESHOLD) {
                            Log.i(TAG, "WAKE (score=${"%.3f".format(score)}) — capturing command")
                            wakeCooldown = WAKE_COOLDOWN_CHUNKS
                            mode = Mode.CAPTURING
                            commandBuffer.clear()
                            // Start with the pre-roll so we don't miss the user's first words.
                            commandBuffer.addAll(preRoll)
                            silenceChunks = 0
                        }
                    }

                    Mode.CAPTURING -> {
                        commandBuffer.add(chunk.copyOf())
                        if (computeRms(chunk) < SILENCE_RMS) {
                            silenceChunks++
                        } else {
                            silenceChunks = 0
                        }
                        if (silenceChunks >= SILENCE_CHUNKS_TO_END
                            || commandBuffer.size >= MAX_COMMAND_CHUNKS
                        ) {
                            Log.i(TAG, "command end (chunks=${commandBuffer.size}, silence=$silenceChunks)")
                            mode = Mode.PROCESSING
                            val captured = commandBuffer.toList()
                            commandBuffer.clear()
                            silenceChunks = 0
                            processCommandAsync(captured) {
                                mode = Mode.LISTENING
                                rescheduleAutoOff()
                            }
                        }
                    }

                    Mode.PROCESSING -> {
                        // Drop chunks while the server + TTS does its thing.
                    }
                }
            }
        } finally {
            record.stop()
            record.release()
            Log.d(TAG, "capture loop stopped")
        }
    }

    private fun processCommandAsync(chunks: List<ShortArray>, done: () -> Unit) {
        thread(name = "jarvis-command") {
            try {
                val wav = writeWav(chunks)
                Log.d(TAG, "wrote ${wav.length()} bytes to ${wav.name}, sending to server")
                val response = api?.command(wav)
                if (response != null) {
                    Log.i(TAG, "transcript: ${response.transcript}")
                    Log.i(TAG, "reply: ${response.reply}")
                    tts?.speak(response.reply)
                    response.phoneActions.forEach { spotify?.dispatch(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "command processing failed", e)
            } finally {
                done()
            }
        }
    }

    private fun computeRms(chunk: ShortArray): Int {
        var sumSq = 0.0
        for (s in chunk) sumSq += s.toDouble() * s.toDouble()
        return sqrt(sumSq / chunk.size).toInt()
    }

    private fun writeWav(chunks: List<ShortArray>): File {
        val totalSamples = chunks.sumOf { it.size }
        val byteSize = totalSamples * 2
        val buf = ByteBuffer.allocate(44 + byteSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(36 + byteSize)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(1)
        buf.putInt(SAMPLE_RATE)
        buf.putInt(SAMPLE_RATE * 2)
        buf.putShort(2)
        buf.putShort(16)
        buf.put("data".toByteArray())
        buf.putInt(byteSize)
        for (chunk in chunks) {
            for (sample in chunk) buf.putShort(sample)
        }
        val file = File(cacheDir, "command.wav")
        file.writeBytes(buf.array())
        return file
    }

    private fun rescheduleAutoOff() {
        mainHandler.removeCallbacks(autoOff)
        mainHandler.postDelayed(autoOff, DEFAULT_INACTIVITY_MS)
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Jarvis listening",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent notification while Jarvis is active."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, JarvisService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis is listening")
            .setContentText("Say \"Hey Jarvis\" to wake. Auto-stops after 1 hour.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopIntent,
            )
            .build()
    }
}
