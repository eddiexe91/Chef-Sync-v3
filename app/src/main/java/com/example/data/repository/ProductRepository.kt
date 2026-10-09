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

@Serializable
data class SheetPreviewData(
    val sheetId: String,
    val gid: String,
    val totalLinesCount: Int,
    val rawRows: List<List<String>>,
    val columnNames: List<String>,
    val suggestedProductCol: Int,
    val suggestedQuantityCol: Int,
    val suggestedUnitCol: Int,
    val suggestedMinStockCol: Int,
    val suggestedStartRow: Int = 1
)

@Serializable
data class SheetColumnMapping(
    val spreadsheetId: String,
    val gid: String = "0",
    val productColIndex: Int = 0,
    val quantityColIndex: Int = 1,
    val unitColIndex: Int = -1,
    val minStockColIndex: Int = -1,
    val defaultUnit: String = "kg",
    val startRowIndex: Int = 1
)

@Serializable
private data class AiDetectedColumns(
    val productCol: Int = 0,
    val quantityCol: Int = 1,
    val unitCol: Int = -1,
    val minStockCol: Int = -1,
    val startRow: Int = 1
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

    // Active column mapping in memory
    var activeMapping: SheetColumnMapping? = null
        private set

    fun setMapping(mapping: SheetColumnMapping) {
        activeMapping = mapping
    }

    suspend fun parseCommand(voiceText: String): ParsedCommand = withContext(Dispatchers.IO) {
        val trimmed = voiceText.trim()
        if (trimmed.isEmpty()) return@withContext ParsedCommand(action = "UNKNOWN")

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
            Log.w("ProductRepo", "Gemini parsing fallback: ${e.message}")
        }

        fallbackParse(trimmed)
    }

    private fun fallbackParse(text: String): ParsedCommand {
        val lower = text.lowercase()

        val numberRegex = Regex("""(\d+(?:[.,]\d+)?)""")
        val match = numberRegex.find(lower)
        val quantity = match?.value?.replace(",", ".")?.toDoubleOrNull() ?: 1.0

        val unit = when {
            lower.contains("kilo") || lower.contains(" kg") -> "kg"
            lower.contains("litro") || lower.contains(" l") -> "L"
            lower.contains("gramo") || lower.contains(" gr") -> "g"
            lower.contains("caja") -> "cajas"
            lower.contains("paquete") -> "paquetes"
            else -> "unid"
        }

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
            Product(id = "p-5", name = "Leche Entera", quantity = 2.5, unit = "L", minStock = 5.0),
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

    // Fetches sheet preview and detects columns
    suspend fun fetchSheetPreview(rawUrlOrId: String, customGid: String? = null): Result<SheetPreviewData> = withContext(Dispatchers.IO) {
        val trimmed = rawUrlOrId.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Ingresa un enlace o ID válido"))
        }

        val sheetId = extractSheetId(trimmed)
        val extractedGid = customGid?.trim()?.ifBlank { null } ?: extractSheetGid(trimmed) ?: "0"

        val csvUrl = "https://docs.google.com/spreadsheets/d/$sheetId/export?format=csv&gid=$extractedGid"

        try {
            val req = Request.Builder().url(csvUrl).build()
            val resp = httpClient.newCall(req).execute()

            if (!resp.isSuccessful) {
                return@withContext Result.failure(
                    Exception("No se pudo acceder a la hoja (Código HTTP ${resp.code}). Verifica que el documento tenga permisos de 'Cualquier persona con el enlace puede ver'.")
                )
            }

            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) {
                return@withContext Result.failure(Exception("La hoja seleccionada está vacía."))
            }

            val lines = body.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) {
                return@withContext Result.failure(Exception("No se encontraron filas con datos."))
            }

            val parsedRows = lines.take(12).map { parseCsvRow(it) }
            val maxCols = parsedRows.maxOfOrNull { it.size } ?: 0
            if (maxCols == 0) {
                return@withContext Result.failure(Exception("La hoja no contiene columnas legibles."))
            }

            // Determine if row 0 is header
            val firstRow = parsedRows[0]
            val hasHeader = isHeaderRow(firstRow)
            val columnNames = if (hasHeader) {
                firstRow.mapIndexed { idx, name ->
                    val clean = name.trim().replace("\"", "")
                    if (clean.isBlank()) "Columna ${('A' + idx)}" else clean
                }
            } else {
                List(maxCols) { "Columna ${('A' + it)}" }
            }

            val startRow = if (hasHeader) 1 else 0

            // Try AI detection first
            val aiDetection = tryAiDetectColumns(columnNames, parsedRows.drop(startRow).take(3))

            val detectedProductCol = aiDetection?.productCol ?: detectProductCol(columnNames, parsedRows, startRow)
            val detectedQuantityCol = aiDetection?.quantityCol ?: detectQuantityCol(columnNames, parsedRows, startRow, detectedProductCol)
            val detectedUnitCol = aiDetection?.unitCol ?: detectUnitCol(columnNames, parsedRows, startRow)
            val detectedMinStockCol = aiDetection?.minStockCol ?: detectMinStockCol(columnNames, parsedRows, startRow, detectedQuantityCol)

            val preview = SheetPreviewData(
                sheetId = sheetId,
                gid = extractedGid,
                totalLinesCount = lines.size,
                rawRows = parsedRows,
                columnNames = columnNames,
                suggestedProductCol = detectedProductCol.coerceIn(0, maxCols - 1),
                suggestedQuantityCol = detectedQuantityCol.coerceIn(0, maxCols - 1),
                suggestedUnitCol = if (detectedUnitCol in 0 until maxCols) detectedUnitCol else -1,
                suggestedMinStockCol = if (detectedMinStockCol in 0 until maxCols) detectedMinStockCol else -1,
                suggestedStartRow = startRow
            )

            Result.success(preview)
        } catch (e: Exception) {
            Result.failure(Exception("Error al conectar con Google Sheets: ${e.localizedMessage ?: "Verifica la conexión y permisos"}"))
        }
    }

    // Imports all products using the customized column mapping
    suspend fun importSheetWithMapping(mapping: SheetColumnMapping): Result<Int> = withContext(Dispatchers.IO) {
        val csvUrl = "https://docs.google.com/spreadsheets/d/${mapping.spreadsheetId}/export?format=csv&gid=${mapping.gid}"

        try {
            val req = Request.Builder().url(csvUrl).build()
            val resp = httpClient.newCall(req).execute()

            if (!resp.isSuccessful) {
                val err = "Error al descargar hoja (HTTP ${resp.code}). Verifica permisos de lectura."
                syncLogDao.insertLog(SyncLog(action = "SYNC_ERROR", status = "ERROR", message = err))
                return@withContext Result.failure(Exception(err))
            }

            val body = resp.body?.string().orEmpty()
            val lines = body.lines().filter { it.isNotBlank() }

            if (lines.size <= mapping.startRowIndex) {
                val err = "No hay filas de datos a partir de la fila ${mapping.startRowIndex + 1}."
                syncLogDao.insertLog(SyncLog(action = "SYNC_ERROR", status = "ERROR", message = err))
                return@withContext Result.failure(Exception(err))
            }

            val products = mutableListOf<Product>()
            for (i in mapping.startRowIndex until lines.size) {
                val row = parseCsvRow(lines[i])
                if (row.isEmpty()) continue

                val name = row.getOrNull(mapping.productColIndex)?.trim()?.replace("\"", "") ?: ""
                if (name.isBlank()) continue

                val qtyRaw = row.getOrNull(mapping.quantityColIndex)?.trim()?.replace(",", ".")?.replace(Regex("[^0-9.]"), "") ?: "0"
                val qty = qtyRaw.toDoubleOrNull() ?: 0.0

                val unit = if (mapping.unitColIndex >= 0) {
                    row.getOrNull(mapping.unitColIndex)?.trim()?.replace("\"", "")?.ifBlank { mapping.defaultUnit } ?: mapping.defaultUnit
                } else {
                    mapping.defaultUnit
                }

                val minStock = if (mapping.minStockColIndex >= 0) {
                    val minRaw = row.getOrNull(mapping.minStockColIndex)?.trim()?.replace(",", ".")?.replace(Regex("[^0-9.]"), "") ?: "0"
                    minRaw.toDoubleOrNull() ?: 0.0
                } else {
                    0.0
                }

                products.add(
                    Product(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        quantity = qty,
                        unit = unit,
                        minStock = minStock,
                        lastUpdated = System.currentTimeMillis()
                    )
                )
            }

            if (products.isEmpty()) {
                val err = "No se pudieron procesar insumos válidos con la configuración seleccionada."
                syncLogDao.insertLog(SyncLog(action = "SYNC_ERROR", status = "ERROR", message = err))
                return@withContext Result.failure(Exception(err))
            }

            productDao.insertProducts(products)
            activeMapping = mapping

            val successMsg = "¡Sincronización personalizada exitosa! ${products.size} insumos importados de la hoja (gid=${mapping.gid})."
            syncLogDao.insertLog(SyncLog(action = "SYNC_CUSTOM", status = "OK", message = successMsg))

            Result.success(products.size)
        } catch (e: Exception) {
            val err = "Error importando datos: ${e.localizedMessage ?: "Error desconocido"}"
            syncLogDao.insertLog(SyncLog(action = "SYNC_ERROR", status = "ERROR", message = err))
            Result.failure(Exception(err))
        }
    }

    suspend fun syncWithSheets(rawIdOrUrl: String): Result<Int> {
        val mapping = activeMapping
        return if (mapping != null && (extractSheetId(rawIdOrUrl) == mapping.spreadsheetId)) {
            importSheetWithMapping(mapping)
        } else {
            val previewRes = fetchSheetPreview(rawIdOrUrl)
            previewRes.fold(
                onSuccess = { preview ->
                    val defaultMapping = SheetColumnMapping(
                        spreadsheetId = preview.sheetId,
                        gid = preview.gid,
                        productColIndex = preview.suggestedProductCol,
                        quantityColIndex = preview.suggestedQuantityCol,
                        unitColIndex = preview.suggestedUnitCol,
                        minStockColIndex = preview.suggestedMinStockCol,
                        startRowIndex = preview.suggestedStartRow
                    )
                    importSheetWithMapping(defaultMapping)
                },
                onFailure = { Result.failure(it) }
            )
        }
    }

    private suspend fun tryAiDetectColumns(headers: List<String>, sampleRows: List<List<String>>): AiDetectedColumns? {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "dummy") return null

        return try {
            val prompt = """
                Analiza las columnas de esta hoja de cálculo de cocina de un restaurante y determina qué columna corresponde a cada campo.
                Encabezados: $headers
                Muestra de filas: $sampleRows
                
                Responde ÚNICAMENTE en JSON con los índices (0-based):
                {
                  "productCol": índice de la columna con nombre/insumo/artículo,
                  "quantityCol": índice de la columna con stock/cantidad/saldo,
                  "unitCol": índice de la columna con unidad/medida (-1 si no hay),
                  "minStockCol": índice de la columna con stock mínimo/alerta (-1 si no hay),
                  "startRow": 1 si la primera fila es encabezado, o 0 si son datos directos
                }
            """.trimIndent()

            val request = GenerateContentRequest(
                contents = listOf(Content(parts = listOf(Part(text = prompt)))),
                generationConfig = com.example.data.remote.GenerationConfig(responseMimeType = "application/json")
            )
            val resp = RetrofitClient.geminiService.generateContent(apiKey, request)
            val jsonText = resp.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: ""
            json.decodeFromString<AiDetectedColumns>(jsonText)
        } catch (e: Exception) {
            null
        }
    }

    private fun isHeaderRow(row: List<String>): Boolean {
        val headerKeywords = listOf("producto", "insumo", "nombre", "item", "articulo", "artículo", "cantidad", "stock", "unidad", "minimo", "mínimo", "codigo", "código")
        return row.any { cell ->
            val lower = cell.lowercase().trim()
            headerKeywords.any { lower.contains(it) }
        }
    }

    private fun detectProductCol(headers: List<String>, rows: List<List<String>>, startRow: Int): Int {
        val keywords = listOf("producto", "insumo", "nombre", "item", "articulo", "artículo", "descripcion", "descripción", "materia prima")
        headers.forEachIndexed { i, h ->
            val l = h.lowercase()
            if (keywords.any { l.contains(it) }) return i
        }
        // Fallback: check rows for textual column
        for (col in headers.indices) {
            val isMostlyText = rows.drop(startRow).take(5).all { row ->
                val valStr = row.getOrNull(col)?.trim().orEmpty()
                valStr.isNotBlank() && valStr.toDoubleOrNull() == null
            }
            if (isMostlyText) return col
        }
        return 0
    }

    private fun detectQuantityCol(headers: List<String>, rows: List<List<String>>, startRow: Int, productCol: Int): Int {
        val keywords = listOf("cantidad", "stock", "existencia", "existencias", "saldo", "total", "cant", "actual", "disponible")
        headers.forEachIndexed { i, h ->
            val l = h.lowercase()
            if (i != productCol && keywords.any { l.contains(it) }) return i
        }
        // Fallback: column with numbers
        for (col in headers.indices) {
            if (col == productCol) continue
            val hasNumbers = rows.drop(startRow).take(5).any { row ->
                row.getOrNull(col)?.trim()?.replace(",", ".")?.toDoubleOrNull() != null
            }
            if (hasNumbers) return col
        }
        return if (headers.size > 1) 1 else 0
    }

    private fun detectUnitCol(headers: List<String>, rows: List<List<String>>, startRow: Int): Int {
        val keywords = listOf("unidad", "u.m.", "um", "medida", "formato", "presentacion", "presentación")
        headers.forEachIndexed { i, h ->
            val l = h.lowercase()
            if (keywords.any { l.contains(it) }) return i
        }
        // Check unit keywords in cells
        val unitTokens = listOf("kg", "g", "l", "lt", "unid", "unidad", "pz", "caja", "pqte", "paquete")
        for (col in headers.indices) {
            val matches = rows.drop(startRow).take(5).any { row ->
                val v = row.getOrNull(col)?.lowercase()?.trim().orEmpty()
                unitTokens.contains(v)
            }
            if (matches) return col
        }
        return -1
    }

    private fun detectMinStockCol(headers: List<String>, rows: List<List<String>>, startRow: Int, quantityCol: Int): Int {
        val keywords = listOf("minimo", "mínimo", "min", "alerta", "reorden", "seguridad", "min stock", "stock min")
        headers.forEachIndexed { i, h ->
            val l = h.lowercase()
            if (i != quantityCol && keywords.any { l.contains(it) }) return i
        }
        return -1
    }

    fun extractSheetId(input: String): String {
        val regex = Regex("""/spreadsheets/d/([a-zA-Z0-9-_]+)""")
        val match = regex.find(input)
        return match?.groupValues?.get(1) ?: input.trim()
    }

    fun extractSheetGid(input: String): String? {
        val regex = Regex("""[?&#]gid=([0-9]+)""")
        val match = regex.find(input)
        return match?.groupValues?.get(1)
    }

    private fun parseCsvRow(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            if (ch == '\"') {
                inQuotes = !inQuotes
            } else if (ch == ',' && !inQuotes) {
                tokens.add(sb.toString().trim())
                sb.clear()
            } else {
                sb.append(ch)
            }
        }
        tokens.add(sb.toString().trim())
        return tokens
    }
}
