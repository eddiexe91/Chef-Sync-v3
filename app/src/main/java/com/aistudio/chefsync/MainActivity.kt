package com.aistudio.chefsync

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aistudio.chefsync.data.local.Product
import com.aistudio.chefsync.data.local.SyncLog
import com.aistudio.chefsync.data.repository.CloudSyncRepository
import com.aistudio.chefsync.data.repository.ProductRepository
import com.aistudio.chefsync.service.VoiceCommandService
import com.aistudio.chefsync.ui.AuthScreen
import com.aistudio.chefsync.ui.InventoryViewModel
import com.aistudio.chefsync.ui.theme.ChefSyncTheme
import com.google.firebase.auth.FirebaseAuth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        val startupError = try {
            com.google.firebase.FirebaseApp.initializeApp(this)
            null
        } catch (e: Exception) {
            e.localizedMessage ?: "Error desconocido en inicio"
        }

        setContent {
            ChefSyncTheme {
                if (startupError != null) {
                    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("Error de Inicio: $startupError", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    AppContent()
                }
            }
        }
    }
}

@Composable
fun AppContent() {
    val auth = remember {
        try {
            com.google.firebase.auth.FirebaseAuth.getInstance()
        } catch (e: Exception) {
            null
        }
    }
    
    var currentUser by remember { mutableStateOf(auth?.currentUser) }

    if (auth != null) {
        DisposableEffect(auth) {
            val listener = com.google.firebase.auth.FirebaseAuth.AuthStateListener { firebaseAuth ->
                currentUser = firebaseAuth.currentUser
            }
            auth.addAuthStateListener(listener)
            onDispose {
                auth.removeAuthStateListener(listener)
            }
        }
    }

    if (auth == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Error: Firebase no inicializado. Verifique google-services.json")
        }
    } else if (currentUser == null) {
        AuthScreen(onAuthSuccess = { currentUser = auth.currentUser })
    } else {
        MainScreen()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as ChefSyncApp
    val repository = remember {
        val db = app.database
        ProductRepository(db.productDao(), db.wasteDao(), db.syncLogDao())
    }
    val cloudRepository = remember { CloudSyncRepository(context) }
    
    val viewModel: InventoryViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return InventoryViewModel(repository, cloudRepository) as T
        }
    })

    val products by viewModel.products.collectAsStateWithLifecycle()
    val localLogs by viewModel.localLogs.collectAsStateWithLifecycle()
    val cloudSpreadsheetId by viewModel.spreadsheetId.collectAsStateWithLifecycle()

    var isServiceRunning by remember { mutableStateOf(false) }
    var spreadsheetIdInput by remember { mutableStateOf("") }

    LaunchedEffect(cloudSpreadsheetId) {
        if (cloudSpreadsheetId != null) {
            spreadsheetIdInput = cloudSpreadsheetId!!
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (recordAudioGranted) {
            context.startForegroundService(Intent(context, VoiceCommandService::class.java))
            isServiceRunning = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ChefSync") },
                actions = {
                    IconButton(onClick = { 
                        if (spreadsheetIdInput.isNotEmpty()) {
                            viewModel.updateSpreadsheetId(spreadsheetIdInput)
                        }
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "Guardar ID")
                    }
                    IconButton(onClick = { 
                        FirebaseAuth.getInstance().signOut()
                    }) {
                        Icon(Icons.Default.Logout, contentDescription = "Cerrar sesión")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (isServiceRunning) {
                        context.stopService(Intent(context, VoiceCommandService::class.java))
                        isServiceRunning = false
                    } else {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                        )
                    }
                },
                containerColor = if (isServiceRunning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    if (isServiceRunning) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "Control de voz"
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                "Inventario",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = spreadsheetIdInput,
                onValueChange = { spreadsheetIdInput = it },
                label = { Text("Google Sheet ID (Sincronizado en Nube)") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (spreadsheetIdInput != cloudSpreadsheetId) {
                        Icon(Icons.Default.CloudUpload, contentDescription = "Cambios pendientes")
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            if (products.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Configura el ID y sincroniza para ver productos.")
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(products) { product ->
                        ProductItem(product)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Actividad Local Reciente",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(modifier = Modifier.height(150.dp)) {
                items(localLogs) { log ->
                    LogItem(log)
                }
            }
        }
    }
}

@Composable
fun ProductItem(product: Product) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (product.quantity <= product.minStock) 
                MaterialTheme.colorScheme.errorContainer 
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(product.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Mín: ${product.minStock} ${product.unit}", style = MaterialTheme.typography.bodySmall)
            }
            Text("${product.quantity} ${product.unit}", fontWeight = FontWeight.Black, fontSize = 20.sp)
        }
    }
}

@Composable
fun LogItem(log: SyncLog) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (log.status == "SUCCESS" || log.status == "LOCAL_SUCCESS") 
                Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            tint = if (log.status == "SUCCESS" || log.status == "LOCAL_SUCCESS") 
                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(log.message, style = MaterialTheme.typography.bodySmall)
    }
}
