package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.repository.SheetColumnMapping
import com.example.data.repository.SheetPreviewData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetMappingDialog(
    previewData: SheetPreviewData,
    onDismiss: () -> Unit,
    onChangeGid: (newGid: String) -> Unit,
    onConfirmMapping: (mapping: SheetColumnMapping) -> Unit
) {
    var selectedGid by remember { mutableStateOf(previewData.gid) }
    var selectedProductCol by remember { mutableIntStateOf(previewData.suggestedProductCol) }
    var selectedQuantityCol by remember { mutableIntStateOf(previewData.suggestedQuantityCol) }
    var selectedUnitCol by remember { mutableIntStateOf(previewData.suggestedUnitCol) }
    var selectedMinStockCol by remember { mutableIntStateOf(previewData.suggestedMinStockCol) }
    var defaultUnit by remember { mutableStateOf("kg") }
    var startRow by remember { mutableIntStateOf(previewData.suggestedStartRow) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                .clip(RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Mapeador de Columnas",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Adapta ChefSync al formato de tu cocina",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Scrollable Configuration Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    // Specific Tab / Sheet (GID) Section
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "📑 Pestaña específica de la hoja (gid)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Si tu documento tiene varias hojas (ej. 'Cocina', 'Bar', 'Almacén'), indica el 'gid' de la pestaña:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = selectedGid,
                                    onValueChange = { selectedGid = it },
                                    label = { Text("ID de Pestaña (gid)") },
                                    placeholder = { Text("ej. 0 o 184920492") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                OutlinedButton(
                                    onClick = { onChangeGid(selectedGid) },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Cargar")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Table Data Preview (First 5 Rows)
                    Text(
                        text = "👀 Vista previa de tus datos reales:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(10.dp)
                        ) {
                            Column {
                                // Header row
                                Row {
                                    previewData.columnNames.forEachIndexed { i, colName ->
                                        Text(
                                            text = "Col $i: $colName",
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .width(130.dp)
                                                .padding(horizontal = 4.dp, vertical = 2.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                // Sample rows
                                previewData.rawRows.drop(previewData.suggestedStartRow).take(4).forEach { row ->
                                    Row {
                                        previewData.columnNames.indices.forEach { colIdx ->
                                            Text(
                                                text = row.getOrNull(colIdx).orEmpty(),
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                modifier = Modifier
                                                    .width(130.dp)
                                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "🎯 Asigna tus columnas:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Product Column Selector
                    ColumnDropdownSelector(
                        label = "🏷️ Nombre del Insumo / Producto",
                        options = previewData.columnNames,
                        selectedIndex = selectedProductCol,
                        onSelect = { selectedProductCol = it },
                        hint = "Requerido"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Quantity Column Selector
                    ColumnDropdownSelector(
                        label = "🔢 Cantidad / Stock Actual",
                        options = previewData.columnNames,
                        selectedIndex = selectedQuantityCol,
                        onSelect = { selectedQuantityCol = it },
                        hint = "Requerido"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Unit Column Selector (Optional)
                    ColumnDropdownSelector(
                        label = "📏 Unidad de Medida (kg, L, unid)",
                        options = listOf("Ninguna (usar fija abajo)") + previewData.columnNames,
                        selectedIndex = selectedUnitCol + 1,
                        onSelect = { selectedUnitCol = it - 1 },
                        hint = "Opcional"
                    )

                    if (selectedUnitCol < 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = defaultUnit,
                            onValueChange = { defaultUnit = it },
                            label = { Text("Unidad por defecto (ej. kg, unid, L)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Min Stock Column Selector (Optional)
                    ColumnDropdownSelector(
                        label = "⚠️ Stock Mínimo de Alerta",
                        options = listOf("Ninguna (sin alerta mínima)") + previewData.columnNames,
                        selectedIndex = selectedMinStockCol + 1,
                        onSelect = { selectedMinStockCol = it - 1 },
                        hint = "Opcional"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Start Row Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Fila donde inician los datos:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = startRow == 1,
                                onClick = { startRow = 1 },
                                label = { Text("Fila 2 (Hay título)") }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            FilterChip(
                                selected = startRow == 0,
                                onClick = { startRow = 0 },
                                label = { Text("Fila 1 (Directo)") }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Live sample output card
                    val sampleProductRow = previewData.rawRows.getOrNull(startRow)
                    if (sampleProductRow != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "✨ Así se registrará tu primer insumo:",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                val sampleName = sampleProductRow.getOrNull(selectedProductCol)?.replace("\"", "") ?: "Insumo"
                                val sampleQty = sampleProductRow.getOrNull(selectedQuantityCol)?.replace("\"", "") ?: "0"
                                val sampleUnit = if (selectedUnitCol >= 0) sampleProductRow.getOrNull(selectedUnitCol)?.replace("\"", "") ?: defaultUnit else defaultUnit
                                val sampleMin = if (selectedMinStockCol >= 0) sampleProductRow.getOrNull(selectedMinStockCol)?.replace("\"", "") ?: "0" else "0"

                                Text(
                                    text = "Producto: $sampleName • Stock: $sampleQty $sampleUnit • Mínimo: $sampleMin",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                // Footer Actions
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val mapping = SheetColumnMapping(
                                spreadsheetId = previewData.sheetId,
                                gid = selectedGid.ifBlank { "0" },
                                productColIndex = selectedProductCol,
                                quantityColIndex = selectedQuantityCol,
                                unitColIndex = selectedUnitCol,
                                minStockColIndex = selectedMinStockCol,
                                defaultUnit = defaultUnit.ifBlank { "kg" },
                                startRowIndex = startRow
                            )
                            onConfirmMapping(mapping)
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Importar y Vincular Esta Hoja")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnDropdownSelector(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    hint: String
) {
    var expanded by remember { mutableStateOf(false) }
    val displayValue = options.getOrNull(selectedIndex) ?: options.firstOrNull() ?: ""

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(text = hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = displayValue,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                shape = RoundedCornerShape(10.dp),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option,
                                fontWeight = if (index == selectedIndex) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            onSelect(index)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
