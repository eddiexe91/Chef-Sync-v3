package com.aistudio.chefsync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.aistudio.chefsync.data.local.AppDatabase

class ChefSyncApp : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "ChefSync Service"
            val descriptionText = "Canal para el servicio de comandos de voz"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel("voice_service_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)

            // Critical stock notifications
            val criticalName = "Alertas de Inventario"
            val criticalDescription = "Notificaciones de stock crítico"
            val criticalImportance = NotificationManager.IMPORTANCE_HIGH
            val criticalChannel = NotificationChannel("critical_stock_channel", criticalName, criticalImportance).apply {
                description = criticalDescription
            }
            notificationManager.createNotificationChannel(criticalChannel)
        }
    }
}
