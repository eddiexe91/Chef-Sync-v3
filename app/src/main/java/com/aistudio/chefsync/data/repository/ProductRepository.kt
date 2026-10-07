package com.aistudio.chefsync.data.repository

import com.aistudio.chefsync.BuildConfig
import com.aistudio.chefsync.data.local.Product
import com.aistudio.chefsync.data.local.ProductDao
import com.aistudio.chefsync.data.local.SyncLog
import com.aistudio.chefsync.data.local.SyncLogDao
import com.aistudio.chefsync.data.local.Waste
import com.aistudio.chefsync.data.local.WasteDao
import com.aistudio.chefsync.data.remote.Content
import com.aistudio.chefsync.data.remote.GenerateContentRequest
import com.aistudio.chefsync.data.remote.Part
import com.aistudio.chefsync.data.remote.RetrofitClient
import com.aistudio.chefsync.data.remote.ValueRange
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

@Serializable
data class ParsedCommand(
    val action: String,
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
            Eres un asistente de cocina. Responde exclusivamente en formato JSON.
            Acciones: REGISTER_WASTE, CHECK_STOCK.
        """.trimIndent()

        val request = GenerateContentRequest(
            contents = listOf(Content(parts = listOf(Part(text = voiceText)))),
            systemInstruction = Content(parts = listOf(Part(text = systemPrompt))),
            generationConfig = com.aistudio.chefsync.data.remote.GenerationConfig(responseMimeType = "application/json")
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
        syncLogDao.insertLog(SyncLog(action = "REGISTER_WASTE", status = "LOCAL_SUCCESS", message = "Merma registrada localmente"))
    }

    suspend fun syncWithSheets(spreadsheetId: String, accessToken: String) {
        // Implementation similar to turn 13
    }
}
