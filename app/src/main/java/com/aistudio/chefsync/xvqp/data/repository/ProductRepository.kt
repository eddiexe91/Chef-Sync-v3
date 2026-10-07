package com.aistudio.chefsync.xvqp.data.repository

import com.aistudio.chefsync.xvqp.BuildConfig
import com.aistudio.chefsync.xvqp.data.local.Product
import com.aistudio.chefsync.xvqp.data.local.ProductDao
import com.aistudio.chefsync.xvqp.data.local.SyncLog
import com.aistudio.chefsync.xvqp.data.local.SyncLogDao
import com.aistudio.chefsync.xvqp.data.local.Waste
import com.aistudio.chefsync.xvqp.data.local.WasteDao
import com.aistudio.chefsync.xvqp.data.remote.Content
import com.aistudio.chefsync.xvqp.data.remote.GenerateContentRequest
import com.aistudio.chefsync.xvqp.data.remote.Part
import com.aistudio.chefsync.xvqp.data.remote.RetrofitClient
import com.aistudio.chefsync.xvqp.data.remote.ValueRange
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.Serializable

@Serializable
data class ParsedCommand(
    val action: String, // REGISTER_WASTE, CHECK_STOCK, UNKNOWN
    val product: String? = null,
    val quantity: Double? = null,
    val unit: String? = null
)

class ProductRepository(
    private val productDao: ProductDao,
    private val wasteDao: WasteDao,
    private val syncLogDao: SyncLogDao
) {
    val allProducts: Flow<List<Product>> = productDao.getAllProducts()
    val syncLogs: Flow<List<SyncLog>> = syncLogDao.getRecentLogs()

    suspend fun parseCommand(voiceText: String): ParsedCommand {
        val systemPrompt = """
            Eres un asistente de cocina para un restaurant. Tu tarea es extraer información estructurada de comandos de voz del chef.
            Responde exclusivamente en formato JSON.
            Acciones posibles: REGISTER_WASTE, CHECK_STOCK.
            Ejemplo 1: "registra 2 kilos de tomate como merma" -> {"action": "REGISTER_WASTE", "product": "tomate", "quantity": 2.0, "unit": "kilos"}
            Ejemplo 2: "cuántos salmones hay en stock" -> {"action": "CHECK_STOCK", "product": "salmón"}
            Si no entiendes, responde {"action": "UNKNOWN"}
        """.trimIndent()

        val request = GenerateContentRequest(
            contents = listOf(Content(parts = listOf(Part(text = voiceText)))),
            systemInstruction = Content(parts = listOf(Part(text = systemPrompt))),
            generationConfig = com.aistudio.chefsync.xvqp.data.remote.GenerationConfig(responseMimeType = "application/json")
        )

        return try {
            val response = RetrofitClient.geminiService.generateContent(BuildConfig.GEMINI_API_KEY, request)
            val jsonText = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "{}"
            Json.decodeFromString<ParsedCommand>(jsonText)
        } catch (e: Exception) {
            ParsedCommand(action = "UNKNOWN")
        }
    }

    suspend fun registerWaste(waste: Waste) {
        wasteDao.insertWaste(waste)
        syncLogDao.insertLog(SyncLog(action = "REGISTER_WASTE", status = "LOCAL_SUCCESS", message = "Merma de ${waste.quantity} ${waste.unit} de ${waste.productName} guardada localmente"))
    }

    suspend fun syncWithSheets(spreadsheetId: String, accessToken: String) {
        try {
            val authHeader = "Bearer $accessToken"
            
            // 1. Fetch current stock from Sheets (assuming Sheet1!A2:E for products)
            val range = "Sheet1!A2:E"
            val response = RetrofitClient.sheetsService.getValues(authHeader, spreadsheetId, range)
            val products = response.values.mapNotNull { row ->
                if (row.size >= 4) {
                    Product(
                        id = row[0],
                        name = row[1],
                        quantity = row[2].toDoubleOrNull() ?: 0.0,
                        unit = row[3],
                        minStock = row.getOrNull(4)?.toDoubleOrNull() ?: 0.0
                    )
                } else null
            }
            productDao.insertProducts(products)

            // 2. Upload unsynced wastes
            val unsynced = wasteDao.getUnsyncedWastes()
            for (waste in unsynced) {
                val wasteRange = "Wastes!A:D"
                val values = listOf(listOf(waste.productName, waste.quantity.toString(), waste.unit, waste.timestamp.toString()))
                RetrofitClient.sheetsService.appendValues(authHeader, spreadsheetId, wasteRange, values = ValueRange(values = values))
                wasteDao.markAsSynced(waste.id)
            }

            syncLogDao.insertLog(SyncLog(action = "SYNC_SHEETS", status = "SUCCESS", message = "Sincronización con Google Sheets completada"))
        } catch (e: Exception) {
            syncLogDao.insertLog(SyncLog(action = "SYNC_SHEETS", status = "ERROR", message = "Error en sincronización: ${e.message}"))
        }
    }
}
