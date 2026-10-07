package com.aistudio.chefsync.service

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aistudio.chefsync.R
import com.aistudio.chefsync.ChefSyncApp
import com.aistudio.chefsync.data.repository.ProductRepository
import com.aistudio.chefsync.data.repository.CloudSyncRepository
import com.aistudio.chefsync.data.local.Waste
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { startListening() }
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { processVoiceText(it) }
                startListening()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun processVoiceText(text: String) {
        serviceScope.launch {
            val command = repository.parseCommand(text)
            if (command.action == "REGISTER_WASTE" && command.product != null && command.quantity != null) {
                repository.registerWaste(Waste(productName = command.product, quantity = command.quantity, unit = command.unit ?: "unid"))
            }
        }
    }

    private fun startListening() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, "voice_service_channel")
            .setContentTitle("ChefSync").setContentText("Escuchando comandos").setSmallIcon(R.drawable.ic_launcher_foreground).setOngoing(true).build()
        startForeground(1, notification)
        startListening()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        speechRecognizer.destroy()
        wakeLock?.let { if (it.isHeld) it.release() }
    }
}
