package com.example.data.repository

import android.util.Log
import com.example.BuildConfig
import com.example.data.local.Product
import com.example.data.local.ProductDao
import com.example.data.local.SyncLog
import com.example.data.local.SyncLogDao
import com.example.data.local.Waste
import com.example.data.local.WasteDao
import com.example.data.remote.Content
import com.example.data.remote.GenerateContentRequest
import com.example.data.remote.Part
import com.example.data.remote.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID

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

    private val httpClient = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun parseCommand(voiceText: String): ParsedCommand = withContext(Dispatchers.IO) {
        val trimmed = voiceText.trim()
        if (trimmed.isEmpty()) return@withContext ParsedCommand(action = "UNKNOWN")

        // Try AI parser with Gemini first
        try {
            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isNotBlank() && apiKey != "dummy") {
                val systemPrompt = """
                    Eres ChefSync, un asistente inteligente de cocina y gestión de inventario para restaurantes.
                    Analiza la frase hablada por el cocinero y extrae la intención en formato JSON estricto.
                    Acciones soportadas:
                    - "REGISTER_WASTE": cuando se reporta merma, pérdida, descomposición, vencimiento o desperdicio de un insumo.
                    - "CHECK_STOCK": cuando se consulta la existencia o stock de un producto.
                    
                    Estructura JSON:
                    {
                      "action": "REGISTER_WASTE" o "CHECK_STOCK",
                      "product": "nombre normalizado del producto",
                      "quantity": número flotante de la cantidad (ej. 2.5),
                      "unit": "kg" o "L" o "unid" o "paquetes" o "cajas"
                    }
                    Responde únicamente con el JSON válido.
                """.trimIndent()

                val request = GenerateContentRequest(
                    contents = listOf(Content(parts = listOf(Part(text = trimmed)))),
                    systemInstruction = Content(parts = listOf(Part(text = systemPrompt))),
                    generationConfig = com.example.data.remote.GenerationConfig(
                        responseMimeType = "application/json",
                        temperature = 0.1f
                    )
                )

                val response = RetrofitClient.geminiService.generateContent(apiKey, request)
                val jsonText = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "{}"
                return@withContext json.decodeFromString<ParsedCommand>(jsonText)
            }
        } catch (e: Exception) {
            Log.w("ProductRepo", "Gemini parsing failed, fallback to local heuristics: ${e.message}")
        }

        // Fallback local regex parsing for kitchen commands
        fallbackParse(trimmed)
    }

    private fun fallbackParse(text: String): ParsedCommand {
        val lower = text.lowercase()

        // Extract quantity if present
        val numberRegex = Regex("""(\d+(?:[.,]\d+)?)""")
        val match = numberRegex.find(lower)
        val quantity = match?.value?.replace(",", ".")?.toDoubleOrNull() ?: 1.0

        // Extract units
        val unit = when {
            lower.contains("kilo") || lower.contains(" kg") -> "kg"
            lower.contains("litro") || lower.contains(" l") -> "L"
            lower.contains("gramo") || lower.contains(" gr") -> "g"
            lower.contains("caja") -> "cajas"
            lower.contains("paquete") -> "paquetes"
            else -> "unid"
        }

        // Clean product name
        var cleanName = lower
            .replace(Regex("""(merma|mermaron|mermó|descontar|tirar|tiramos|desperdicio|botar|vencido|se vencieron|registrar|cuanto|cuanto queda|stock de|stock)"""), "")
            .replace(Regex("""\d+(?:[.,]\d+)?"""), "")
            .replace(Regex("""(kilos|kilo|kg|litros|litro|l|gramos|gr|cajas|caja|paquetes|paquete|unidades|unidad|de|del|por)"""), "")
            .trim()

        if (cleanName.isBlank()) cleanName = "Insumo general"

        val action = if (lower.contains("stock") || lower.contains("cuanto queda")) "CHECK_STOCK" else "REGISTER_WASTE"
        return ParsedCommand(
            action = action,
            product = cleanName.replaceFirstChar { it.uppercase() },
            quantity = quantity,
            unit = unit
        )
    }

    suspend fun registerWaste(waste: Waste): String = withContext(Dispatchers.IO) {
        wasteDao.insertWaste(waste)
        val existing = productDao.searchProductByName(waste.productName)

        val resultMsg = if (existing != null) {
            val newQty = (existing.quantity - waste.quantity).coerceAtLeast(0.0)
            productDao.insertProduct(existing.copy(quantity = newQty, lastUpdated = System.currentTimeMillis()))
            if (newQty <= existing.minStock) {
                "⚠️ Merma: -${waste.quantity} ${waste.unit} de ${existing.name}. ALERTA: Stock crítico (${newQty} ${existing.unit} restantes)"
            } else {
                "✅ Merma: -${waste.quantity} ${waste.unit} de ${existing.name}. Stock restante: ${newQty} ${existing.unit}"
            }
        } else {
            "ℹ️ Merma registrada: ${waste.quantity} ${waste.unit} de ${waste.productName} (sin stock previo)"
        }

        syncLogDao.insertLog(SyncLog(action = "MERMA", status = "OK", message = resultMsg))
        resultMsg
    }

    suspend fun seedDemoProducts() = withContext(Dispatchers.IO) {
        val demoList = listOf(
            Product(id = "p-1", name = "Tomates Chilenos", quantity = 25.0, unit = "kg", minStock = 5.0),
            Product(id = "p-2", name = "Carne de Res Lomo", quantity = 14.5, unit = "kg", minStock = 4.0),
            Product(id = "p-3", name = "Aceite de Oliva Extra", quantity = 8.0, unit = "L", minStock = 2.0),
            Product(id = "p-4", name = "Harina de Trigo 000", quantity = 40.0, unit = "kg", minStock = 10.0),
            Product(id = "p-5", name = "Leche Entera", quantity = 2.5, unit = "L", minStock = 5.0), // Crítico
            Product(id = "p-6", name = "Queso Mozzarella", quantity = 7.0, unit = "kg", minStock = 3.0),
            Product(id = "p-7", name = "Pechuga de Pollo", quantity = 16.0, unit = "kg", minStock = 5.0),
            Product(id = "p-8", name = "Papas Russet", quantity = 45.0, unit = "kg", minStock = 12.0)
        )
        productDao.insertProducts(demoList)
        syncLogDao.insertLog(SyncLog(action = "DEMO", status = "OK", message = "8 insumos de prueba cargados con éxito"))
    }

    suspend fun clearInventory() = withContext(Dispatchers.IO) {
        productDao.clearAllProducts()
        syncLogDao.insertLog(SyncLog(action = "LIMPIEZA", status = "INFO", message = "Inventario reiniciado"))
    }

    suspend fun syncWithSheets(rawIdOrUrl: String): Result<Int> = withContext(Dispatchers.IO) {
        val trimmed = rawIdOrUrl.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("La ID de Google Sheets no puede estar vacía"))
        }

        // Extract spreadsheet ID if user pasted full URL
        val cleanId = extractSheetId(trimmed)

        syncLogDao.insertLog(SyncLog(action = "SYNC_INICIO", status = "INFO", message = "Conectando con Google Sheets ($cleanId)..."))

        val csvUrl = "https://docs.google.com/spreadsheets/d/$cleanId/export?format=csv"
        try {
            val req = Request.Builder().url(csvUrl).build()
            val resp = httpClient.newCall(req).execute()

            if (!resp.isSuccessful) {
                val err = "No se pudo acceder a la hoja (Código HTTP ${resp.code}). Asegúrate de que el documento tenga permisos de 'Cualquier persona con el enlace puede leer'."
                syncLogDao.insertLog(SyncLog(action = "SYNC_ERROR", status = "ERROR", message = err))
                return@withContext Result.failure(Exception(err))
            }

            val bodyText = resp.body?.string().orEmpty()
            if (bodyText.isBlank()) {
                val err = "La hoja de cálculo está vacía o sin contenido legible."
                syncLogDao.insertLog(SyncLog(action = "SYNC_ERROR", status = "ERROR", message = err))
                return@withContext Result.failure(Exception(err))
            }

            val parsedProducts = parseCsvProducts(bodyText)
            if (parsedProducts.isEmpty()) {
                val err = "No se encontraron columnas válidas de productos. Formato requerido: ID, Nombre, Cantidad, Unidad, Mínimo."
                syncLogDao.insertLog(SyncLog(action = "SYNC_ERROR", status = "ERROR", message = err))
                return@withContext Result.failure(Exception(err))
            }

            productDao.insertProducts(parsedProducts)
            val successMsg = "Sincronización exitosa: ${parsedProducts.size} insumos importados desde Google Sheets"
            syncLogDao.insertLog(SyncLog(action = "SYNC_EXITO", status = "OK", message = successMsg))
            Result.success(parsedProducts.size)
        } catch (e: Exception) {
            val err = "Error de red al sincronizar con Google Sheets: ${e.localizedMessage ?: "Verifica tu conexión y los permisos de la hoja"}"
            syncLogDao.insertLog(SyncLog(action = "SYNC_ERROR", status = "ERROR", message = err))
            Result.failure(Exception(err))
        }
    }

    private fun extractSheetId(input: String): String {
        val regex = Regex("""/spreadsheets/d/([a-zA-Z0-9-_]+)""")
        val match = regex.find(input)
        return match?.groupValues?.get(1) ?: input
    }

    private fun parseCsvProducts(csv: String): List<Product> {
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyList()

        val products = mutableListOf<Product>()
        // Skip header line
        for (i in 1 until lines.size) {
            val row = parseCsvRow(lines[i])
            if (row.size >= 3) {
                val id = if (row.size >= 5) row[0].ifBlank { UUID.randomUUID().toString() } else UUID.randomUUID().toString()
                val name = if (row.size >= 5) row[1] else row[0]
                val qty = (if (row.size >= 5) row[2] else row[1]).replace(",", ".").toDoubleOrNull() ?: 0.0
                val unit = (if (row.size >= 5) row[3] else row[2]).ifBlank { "unid" }
                val min = (if (row.size >= 5) row[4] else if (row.size >= 4) row[3] else "0").replace(",", ".").toDoubleOrNull() ?: 0.0

                if (name.isNotBlank()) {
                    products.add(
                        Product(
                            id = id.trim(),
                            name = name.trim().replace("\"", ""),
                            quantity = qty,
                            unit = unit.trim().replace("\"", ""),
                            minStock = min,
                            lastUpdated = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
        return products
    }

    private fun parseCsvRow(line: String): List<String> {
        val tokens = mutableListOf<String>()
        var sb = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            if (ch == '\"') {
                inQuotes = !inQuotes
            } else if (ch == ',' && !inQuotes) {
                tokens.add(sb.toString().trim())
                sb = StringBuilder()
            } else {
                sb.append(ch)
            }
        }
        tokens.add(sb.toString().trim())
        return tokens
    }
}
