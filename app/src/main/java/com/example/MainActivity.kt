package com.example

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.Product
import com.example.data.local.SyncLog
import com.example.data.repository.CloudSyncRepository
import com.example.data.repository.ProductRepository
import com.example.service.VoiceCommandService
import com.example.ui.AuthScreen
import com.example.ui.ChefSyncTutorialDialog
import com.example.ui.InventoryViewModel
import com.example.ui.ManualWasteDialog
import com.example.ui.SheetMappingDialog
import com.example.ui.SyncUiState
import com.example.ui.theme.ChefSyncTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val error = try { FirebaseApp.initializeApp(this); null } catch (e: Exception) { e.message }
        setContent {
            ChefSyncTheme {
                if (error != null) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Error: $error") }
                } else {
                    AppContent()
                }
            }
        }
    }
}

@Composable
fun AppContent() {
    val auth = remember { try { FirebaseAuth.getInstance() } catch (e: Exception) { null } }
    var user by remember { mutableStateOf(auth?.currentUser) }
    if (auth != null) {
        DisposableEffect(auth) {
            val l = FirebaseAuth.AuthStateListener { user = it.currentUser }
            auth.addAuthStateListener(l)
            onDispose { auth.removeAuthStateListener(l) }
        }
    }
    if (auth == null) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Firebase Error") }
    else if (user == null) AuthScreen { user = auth.currentUser }
    else MainScreen()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as ChefSyncApp
    val repository = remember { ProductRepository(app.database.productDao(), app.database.wasteDao(), app.database.syncLogDao()) }
    val cloudRepository = remember { CloudSyncRepository(context) }
    val viewModel: InventoryViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return InventoryViewModel(repository, cloudRepository) as T
        }
    })

    val products by viewModel.products.collectAsStateWithLifecycle()
    val logs by viewModel.localLogs.collectAsStateWithLifecycle()
    val cloudId by viewModel.spreadsheetId.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val sheetPreview by viewModel.sheetPreview.collectAsStateWithLifecycle()
    val isPreviewLoading by viewModel.isPreviewLoading.collectAsStateWithLifecycle()

    var inputId by remember { mutableStateOf("") }
    LaunchedEffect(cloudId) { if (cloudId != null && inputId.isEmpty()) inputId = cloudId!! }

    var isRunning by remember { mutableStateOf(false) }
    var showTutorial by remember { mutableStateOf(false) }
    var manualWasteProduct by remember { mutableStateOf<String?>(null) }
    var showLogsDialog by remember { mutableStateOf(false) }

    // If products is empty on start, suggest tutorial
    LaunchedEffect(products) {
        if (products.isEmpty() && cloudId == null) {
            showTutorial = true
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            context.startForegroundService(Intent(context, VoiceCommandService::class.java))
            isRunning = true
            Toast.makeText(context, "Micrófono activado. Habla para registrar mermas.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Se requiere permiso de micrófono para la voz.", Toast.LENGTH_SHORT).show()
        }
    }

    val criticalCount = remember(products) { products.count { it.quantity <= it.minStock } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ChefSync", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            text = if (isRunning) "🎙️ Escuchando comandos..." else "Inventario en tiempo real",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showTutorial = true }) {
                        Icon(Icons.AutoMirrored.Filled.Help, contentDescription = "Guía y Tutorial")
                    }
                    IconButton(onClick = { viewModel.seedDemoData() }) {
                        Icon(Icons.Default.Fastfood, contentDescription = "Cargar Datos Demo")
                    }
                    IconButton(onClick = { FirebaseAuth.getInstance().signOut() }) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Cerrar sesión")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (isRunning) {
                        context.stopService(Intent(context, VoiceCommandService::class.java))
                        isRunning = false
                        Toast.makeText(context, "Micrófono pausado", Toast.LENGTH_SHORT).show()
                    } else {
                        launcher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                    }
                },
                containerColor = if (isRunning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (isRunning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = if (isRunning) "Detener micrófono" else "Iniciar comandos de voz"
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Google Sheets Sync Input Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📊 Vincular Google Sheet",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Soporta cualquier formato y pestaña",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Pega el enlace de tu hoja (ej. .../edit#gid=0) o pulsa 'Mapear' para señalar qué columnas procesar:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputId,
                        onValueChange = { inputId = it },
                        placeholder = { Text("Pega enlace o ID de Google Sheets", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Open Column Mapping Assistant
                        Button(
                            onClick = {
                                if (inputId.isNotBlank()) {
                                    viewModel.requestSheetPreview(inputId)
                                } else {
                                    Toast.makeText(context, "Ingresa primero el enlace o ID de tu hoja", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !isPreviewLoading,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isPreviewLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Analizando...", fontSize = 13.sp)
                            } else {
                                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Mapear Columnas", fontSize = 13.sp)
                            }
                        }

                        // Quick Sync Button
                        OutlinedButton(
                            onClick = {
                                if (inputId.isNotBlank()) {
                                    viewModel.updateSpreadsheetId(inputId)
                                } else {
                                    Toast.makeText(context, "Ingresa primero el enlace o ID", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = syncState !is SyncUiState.Syncing,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (syncState is SyncUiState.Syncing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = "Sincronizar", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Sincronizar", fontSize = 13.sp)
                            }
                        }
                    }

                    // Sync State Message
                    AnimatedVisibility(visible = syncState !is SyncUiState.Idle) {
                        when (val s = syncState) {
                            is SyncUiState.Success -> {
                                Text(
                                    text = "✅ ${s.message}",
                                    color = Color(0xFF2E7D32),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                            is SyncUiState.Error -> {
                                Text(
                                    text = "⚠️ ${s.message}",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                            is SyncUiState.Syncing -> {
                                Text(
                                    text = "⏳ Sincronizando datos con Google Sheets...",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                            else -> Unit
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Inventory Summary Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Insumos (${products.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (criticalCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        SuggestionChip(
                            onClick = {},
                            label = { Text("⚠️ $criticalCount en stock crítico", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
                        )
                    }
                }

                Row {
                    TextButton(onClick = { showTutorial = true }) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Tutorial", fontSize = 12.sp)
                    }
                    TextButton(onClick = { showLogsDialog = true }) {
                        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Logs (${logs.size})", fontSize = 12.sp)
                    }
                }
            }

            // Products list or Empty State
            if (products.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Restaurant,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Tu inventario está vacío",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Para comenzar a usar los comandos de voz de ChefSync, carga los productos de ejemplo o sincroniza tu Google Sheet.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = { viewModel.seedDemoData() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Cargar Inventario de Prueba")
                            }
                            if (inputId.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { viewModel.requestSheetPreview(inputId) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Tune, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Mapear Columnas de mi Hoja")
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showTutorial = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Ver Guía Paso a Paso")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(products, key = { it.id }) { product ->
                        ProductItem(
                            p = product,
                            onRegisterWasteClick = { manualWasteProduct = product.name }
                        )
                    }
                }
            }

            // Quick live voice banner
            if (isRunning) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(10.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Escuchando: Di \"Merma 2 kilos de tomates\" o \"Stock de leche\"",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }

    // Tutorial Dialog
    if (showTutorial) {
        ChefSyncTutorialDialog(
            onDismiss = { showTutorial = false },
            onLoadDemoData = {
                viewModel.seedDemoData()
            }
        )
    }

    // Column Mapping Assistant Dialog
    sheetPreview?.let { preview ->
        SheetMappingDialog(
            previewData = preview,
            onDismiss = { viewModel.dismissSheetPreview() },
            onChangeGid = { newGid -> viewModel.requestSheetPreview(inputId, newGid) },
            onConfirmMapping = { mapping -> viewModel.applyColumnMappingAndImport(mapping) }
        )
    }

    // Manual Waste Dialog
    manualWasteProduct?.let { prodName ->
        ManualWasteDialog(
            initialProductName = prodName,
            onDismiss = { manualWasteProduct = null },
            onConfirm = { name, qty, unit ->
                viewModel.registerWaste(name, qty, unit)
            }
        )
    }

    // Logs Dialog
    if (showLogsDialog) {
        AlertDialog(
            onDismissRequest = { showLogsDialog = false },
            title = { Text("Historial de Operaciones (Logs)") },
            text = {
                if (logs.isEmpty()) {
                    Text("No hay registros aún.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                        items(logs) { log ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(text = log.message, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = "${log.action} • ${log.status}",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogsDialog = false }) {
                    Text("Cerrar")
                }
            }
        )
    }
}

@Composable
fun ProductItem(p: Product, onRegisterWasteClick: () -> Unit) {
    val isCritical = p.quantity <= p.minStock
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCritical) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCritical) {
                        Text("⚠️ ", fontSize = 14.sp)
                    }
                    Text(
                        text = p.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = if (isCritical) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isCritical) "¡Stock Crítico! Mínimo: ${p.minStock} ${p.unit}" else "Mínimo: ${p.minStock} ${p.unit}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCritical) MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${p.quantity} ${p.unit}",
                        fontWeight = FontWeight.Black,
                        fontSize = 17.sp,
                        color = if (isCritical) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onRegisterWasteClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Merma",
                        tint = if (isCritical) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
