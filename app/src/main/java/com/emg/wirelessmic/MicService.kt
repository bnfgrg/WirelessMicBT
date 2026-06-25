package com.emg.wirelessmic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

enum class MicStatus {
    IDLE, CONNECTING_SCO, STREAMING, ERROR_AUDIO
}

enum class BtMode { NONE, SCO, A2DP }

data class MicState(
    val status: MicStatus = MicStatus.IDLE,
    val level: Float = 0f,
    val aecActive: Boolean = false,
    val nsActive: Boolean = false,
    val btMode: BtMode = BtMode.NONE,
    val message: String = ""
)

object MicController {
    private val _state = MutableStateFlow(MicState())
    val state: StateFlow<MicState> = _state
    fun update(transform: (MicState) -> MicState) { _state.value = transform(_state.value) }
}

class MicService : Service() {

    companion object {
        const val ACTION_START = "com.emg.wirelessmic.START"
        const val ACTION_STOP  = "com.emg.wirelessmic.STOP"
        private const val CHANNEL_ID = "mic_service_channel"
        private const val NOTIF_ID   = 1
        private const val SCO_TIMEOUT_MS = 6000L
        private val SCO_SAMPLE_RATES  = intArrayOf(16000, 8000)
        private val A2DP_SAMPLE_RATES = intArrayOf(44100, 48000, 16000)
    }

    private lateinit var audioManager: AudioManager
    private var serviceScope: CoroutineScope? = null
    private var streamJob: Job? = null
    private var scoTimeoutJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var scoReceiverRegistered = false

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val scoState = intent.getIntExtra(
                AudioManager.EXTRA_SCO_AUDIO_STATE,
                AudioManager.SCO_AUDIO_STATE_ERROR
            )
            when (scoState) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    scoTimeoutJob?.cancel()
                    MicController.update { it.copy(message = "SCO connesso, avvio streaming…") }
                    startStreaming(useSco = true)
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    if (MicController.state.value.status == MicStatus.STREAMING
                        && MicController.state.value.btMode == BtMode.SCO) {
                        MicController.update {
                            it.copy(
                                status = MicStatus.IDLE,
                                btMode = BtMode.NONE,
                                message = "Collegamento SCO perso"
                            )
                        }
                        stopStreamingInternal(stopSco = false)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> beginSession()
            ACTION_STOP  -> endSession()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── avvio sessione: tenta prima SCO ──────────────────────────────────────
    private fun beginSession() {
        startForeground(NOTIF_ID, buildNotification("Ricerca dispositivo Bluetooth…"))
        serviceScope = CoroutineScope(Dispatchers.Default)

        MicController.update { MicState(status = MicStatus.CONNECTING_SCO, message = "Tentativo collegamento SCO (6s)…") }

        if (!scoReceiverRegistered) {
            registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            scoReceiverRegistered = true
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = true
        audioManager.startBluetoothSco()

        // Timeout: se SCO non risponde entro SCO_TIMEOUT_MS → fallback A2DP
        scoTimeoutJob = serviceScope?.launch {
            kotlinx.coroutines.delay(SCO_TIMEOUT_MS)
            if (MicController.state.value.status == MicStatus.CONNECTING_SCO) {
                MicController.update { it.copy(message = "SCO non disponibile → fallback A2DP") }
                // Ripristina modo audio normale prima di A2DP
                try {
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                    audioManager.mode = AudioManager.MODE_NORMAL
                } catch (_: Exception) {}
                startStreaming(useSco = false)
            }
        }
    }

    // ─── streaming vero e proprio ─────────────────────────────────────────────
    private fun startStreaming(useSco: Boolean) {
        if (streamJob?.isActive == true) return

        val sampleRates = if (useSco) SCO_SAMPLE_RATES else A2DP_SAMPLE_RATES
        val (sampleRate, recordBufSize) = pickWorkingSampleRate(sampleRates)
        if (sampleRate == -1) {
            MicController.update { it.copy(status = MicStatus.ERROR_AUDIO, message = "Impossibile inizializzare AudioRecord") }
            return
        }

        try {
            val audioSource = if (useSco) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                             else MediaRecorder.AudioSource.MIC

            audioRecord = AudioRecord(
                audioSource, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                recordBufSize
            )

            val sessionId = audioRecord!!.audioSessionId
            var aecActive = false
            var nsActive  = false

            // AEC/NS/AGC sono utili in entrambe le modalità, ma affidabili solo con SCO
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)
                aec?.enabled = true
                aecActive = aec?.enabled == true
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)
                ns?.enabled = true
                nsActive = ns?.enabled == true
            }
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)
                agc?.enabled = true
            }

            val trackBufSize = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )

            val usage   = if (useSco) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
            val content = if (useSco) AudioAttributes.CONTENT_TYPE_SPEECH       else AudioAttributes.CONTENT_TYPE_MUSIC

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(content).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(trackBufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            // Forza device di output su BT (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (useSco) {
                    val dev = audioManager.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    if (dev != null) audioManager.setCommunicationDevice(dev)
                }
                // Per A2DP non serve forzare: il sistema instrada già sull'ultimo BT connesso
            }

            audioRecord?.startRecording()
            audioTrack?.play()

            val btMode = if (useSco) BtMode.SCO else BtMode.A2DP
            val modeLabel = if (useSco) "SCO · ${sampleRate}Hz" else "A2DP · ${sampleRate}Hz"

            MicController.update {
                it.copy(
                    status    = MicStatus.STREAMING,
                    btMode    = btMode,
                    aecActive = aecActive,
                    nsActive  = nsActive,
                    message   = "Streaming attivo ($modeLabel)"
                )
            }
            updateNotification("Microfono attivo — $modeLabel")

            streamJob = serviceScope?.launch(Dispatchers.IO) {
                val buffer = ShortArray(recordBufSize / 2)
                val rec = audioRecord ?: return@launch
                val trk = audioTrack ?: return@launch
                while (isActive) {
                    val read = rec.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        trk.write(buffer, 0, read)
                        MicController.update { s -> s.copy(level = computeRms(buffer, read)) }
                    }
                }
            }

        } catch (e: Exception) {
            MicController.update { it.copy(status = MicStatus.ERROR_AUDIO, message = "Errore audio: ${e.message}") }
            cleanupAudio()
        }
    }

    private fun computeRms(buffer: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) { val v = buffer[i] / 32768.0; sum += v * v }
        return (sqrt(sum / count) * 6.0).coerceIn(0.0, 1.0).toFloat()
    }

    private fun pickWorkingSampleRate(rates: IntArray): Pair<Int, Int> {
        for (rate in rates) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min > 0) return Pair(rate, min * 2)
        }
        return Pair(-1, -1)
    }

    private fun endSession() {
        MicController.update { it.copy(status = MicStatus.IDLE, level = 0f, btMode = BtMode.NONE, message = "Fermato") }
        stopStreamingInternal(stopSco = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopStreamingInternal(stopSco: Boolean) {
        scoTimeoutJob?.cancel(); scoTimeoutJob = null
        streamJob?.cancel();     streamJob = null
        cleanupAudio()
        if (stopSco) {
            try {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
                audioManager.mode = AudioManager.MODE_NORMAL
            } catch (_: Exception) {}
            if (scoReceiverRegistered) {
                try { unregisterReceiver(scoReceiver) } catch (_: Exception) {}
                scoReceiverRegistered = false
            }
        }
    }

    private fun cleanupAudio() {
        try { aec?.release() } catch (_: Exception) {}; aec = null
        try { ns?.release()  } catch (_: Exception) {}; ns  = null
        try { agc?.release() } catch (_: Exception) {}; agc = null
        try { audioRecord?.stop();    audioRecord?.release() } catch (_: Exception) {}; audioRecord = null
        try { audioTrack?.stop();     audioTrack?.release()  } catch (_: Exception) {}; audioTrack  = null
    }

    override fun onDestroy() { stopStreamingInternal(stopSco = true); super.onDestroy() }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Microfono Wireless", NotificationManager.IMPORTANCE_LOW))
    }
    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Microfono Wireless BT").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build()
    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
}
