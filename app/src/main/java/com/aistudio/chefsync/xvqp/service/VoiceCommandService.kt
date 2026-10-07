package com.aistudio.chefsync.xvqp.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import com.aistudio.chefsync.xvqp.R
import com.aistudio.chefsync.xvqp.ChefSyncApp
import com.aistudio.chefsync.xvqp.data.repository.ProductRepository
import com.aistudio.chefsync.xvqp.data.repository.CloudSyncRepository
import com.aistudio.chefsync.xvqp.data.local.Waste
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.util.Log

class VoiceCommandService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var repository: ProductRepository
    private lateinit var cloudRepository: CloudSyncRepository
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as ChefSyncApp
        val db = app.database
        repository = ProductRepository(db.productDao(), db.wasteDao(), db.syncLogDao())
        cloudRepository = CloudSyncRepository(this)

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChefSync:VoiceWakeLock")
        wakeLock?.acquire()

        setupSpeechRecognizer()
    }

    private fun setupSpeechRecognizer() {
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("VoiceService", "Escuchando...")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                Log.e("VoiceService", "Error: $error")
                // Reiniciar escucha en caso de timeout o falta de coincidencia
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    startListening()
                } else {
                    serviceScope.launch {
                        kotlinx.coroutines.delay(1000)
                        startListening()
                    }
                }
            }
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                    processVoiceText(it)
                }
                startListening()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun processVoiceText(text: String) {
        serviceScope.launch {
            val command = repository.parseCommand(text)
            when (command.action) {
                "REGISTER_WASTE" -> {
                    if (command.product != null && command.quantity != null) {
                        repository.registerWaste(Waste(
                            productName = command.product,
                            quantity = command.quantity,
                            unit = command.unit ?: "unidades"
                        ))
                        cloudRepository.addLog("REGISTER_WASTE", "LOCAL_SUCCESS", "Voz: Merma registrada de ${command.product}")
                    }
                }
                "CHECK_STOCK" -> {
                    Log.d("VoiceService", "Consulta stock: ${command.product}")
                    cloudRepository.addLog("CHECK_STOCK", "INFO", "Voz: Consulta de stock de ${command.product}")
                }
            }
        }
    }

    private fun startListening() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Parámetros para mantener la escucha activa
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            }
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e("VoiceService", "Error al iniciar escucha", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, "voice_service_channel")
            .setContentTitle("ChefSync")
            .setContentText("Modo manos libres activo (Bloqueado)")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
        startListening()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        speechRecognizer.destroy()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }
}
