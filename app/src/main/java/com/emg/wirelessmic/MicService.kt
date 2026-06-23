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
    IDLE, CONNECTING_SCO, STREAMING, ERROR_NO_SCO, ERROR_AUDIO
}

data class MicState(
    val status: MicStatus = MicStatus.IDLE,
    val level: Float = 0f,      // 0..1 livello audio in ingresso (per VU meter)
    val aecActive: Boolean = false,
    val nsActive: Boolean = false,
    val message: String = ""
)

object MicController {
    private val _state = MutableStateFlow(MicState())
    val state: StateFlow<MicState> = _state

    fun update(transform: (MicState) -> MicState) {
        _state.value = transform(_state.value)
    }
}

class MicService : Service() {

    companion object {
        const val ACTION_START = "com.emg.wirelessmic.START"
        const val ACTION_STOP = "com.emg.wirelessmic.STOP"
        private const val CHANNEL_ID = "mic_service_channel"
        private const val NOTIF_ID = 1

        // SCO è banda stretta: 16kHz se supportato, altrimenti fallback 8kHz
        private val SAMPLE_RATES = intArrayOf(16000, 8000)
    }

    private lateinit var audioManager: AudioManager
    private var serviceScope: CoroutineScope? = null
    private var streamJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    private var scoReceiverRegistered = false

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(
                AudioManager.EXTRA_SCO_AUDIO_STATE,
                AudioManager.SCO_AUDIO_STATE_ERROR
            )
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    MicController.update { it.copy(message = "SCO connesso, avvio streaming") }
                    startStreaming()
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    if (MicController.state.value.status == MicStatus.STREAMING) {
                        MicController.update {
                            it.copy(
                                status = MicStatus.ERROR_NO_SCO,
                                message = "Collegamento SCO perso (altoparlante disconnesso?)"
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
            ACTION_STOP -> endSession()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun beginSession() {
        startForeground(NOTIF_ID, buildNotification("Connessione al dispositivo Bluetooth..."))
        MicController.update { MicState(status = MicStatus.CONNECTING_SCO, message = "Avvio collegamento SCO...") }

        if (!scoReceiverRegistered) {
            registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            scoReceiverRegistered = true
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = true
        audioManager.startBluetoothSco()

        // Se entro 6 secondi lo SCO non si connette, segnala errore
        serviceScope = CoroutineScope(Dispatchers.Default)
        serviceScope?.launch {
            kotlinx.coroutines.delay(6000)
            if (MicController.state.value.status == MicStatus.CONNECTING_SCO) {
                MicController.update {
                    it.copy(
                        status = MicStatus.ERROR_NO_SCO,
                        message = "Nessun dispositivo SCO trovato. Verifica che l'altoparlante sia in modalità vivavoce/chiamata e accoppiato."
                    )
                }
            }
        }
    }

    private fun startStreaming() {
        if (streamJob?.isActive == true) return

        val (sampleRate, recordBufSize) = pickWorkingSampleRate()
        if (sampleRate == -1) {
            MicController.update {
                it.copy(status = MicStatus.ERROR_AUDIO, message = "Impossibile inizializzare l'audio (AudioRecord)")
            }
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBufSize
            )

            val sessionId = audioRecord!!.audioSessionId
            var aecActive = false
            var nsActive = false

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

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(trackBufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            // Forza l'output sul dispositivo SCO se disponibile (Android 12+: setPreferredDevice)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val scoDevice = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (scoDevice != null) {
                    audioManager.setCommunicationDevice(scoDevice)
                }
            }

            audioRecord?.startRecording()
            audioTrack?.play()

            MicController.update {
                it.copy(
                    status = MicStatus.STREAMING,
                    aecActive = aecActive,
                    nsActive = nsActive,
                    message = "In streaming (${sampleRate}Hz, SCO)"
                )
            }
            updateNotification("Microfono attivo — streaming verso altoparlante BT")

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
            MicController.update {
                it.copy(status = MicStatus.ERROR_AUDIO, message = "Errore audio: ${e.message}")
            }
            cleanupAudio()
        }
    }

    private fun computeRms(buffer: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) {
            val v = buffer[i] / 32768.0
            sum += v * v
        }
        val rms = sqrt(sum / count)
        return (rms * 6f).coerceIn(0.0, 1.0).toFloat() // scalato per leggibilità nel VU meter
    }

    private fun pickWorkingSampleRate(): Pair<Int, Int> {
        for (rate in SAMPLE_RATES) {
            val minBuf = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf > 0) return Pair(rate, minBuf * 2)
        }
        return Pair(-1, -1)
    }

    private fun endSession() {
        MicController.update { it.copy(status = MicStatus.IDLE, level = 0f, message = "Fermato") }
        stopStreamingInternal(stopSco = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopStreamingInternal(stopSco: Boolean) {
        streamJob?.cancel()
        streamJob = null
        cleanupAudio()

        if (stopSco) {
            try {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }
                audioManager.mode = AudioManager.MODE_NORMAL
            } catch (_: Exception) { }

            if (scoReceiverRegistered) {
                try { unregisterReceiver(scoReceiver) } catch (_: Exception) { }
                scoReceiverRegistered = false
            }
            serviceScope?.launch { }
            serviceScope = null
        }
    }

    private fun cleanupAudio() {
        try { aec?.release() } catch (_: Exception) {}
        try { ns?.release() } catch (_: Exception) {}
        try { agc?.release() } catch (_: Exception) {}
        aec = null; ns = null; agc = null

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    override fun onDestroy() {
        stopStreamingInternal(stopSco = true)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Microfono Wireless", NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Microfono Wireless BT")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
