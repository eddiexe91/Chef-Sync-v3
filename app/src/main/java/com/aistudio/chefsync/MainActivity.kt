package com.aistudio.chefsync

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.util.Log
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
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T { return InventoryViewModel(repository, cloudRepository) as T }
    })
    val products by viewModel.products.collectAsStateWithLifecycle()
    val logs by viewModel.localLogs.collectAsStateWithLifecycle()
    val cloudId by viewModel.spreadsheetId.collectAsStateWithLifecycle()
    var inputId by remember { mutableStateOf("") }
    LaunchedEffect(cloudId) { if (cloudId != null) inputId = cloudId!! }
    var isRunning by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.RECORD_AUDIO] == true) {
            context.startForegroundService(Intent(context, VoiceCommandService::class.java))
            isRunning = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("ChefSync") }, actions = {
                IconButton(onClick = { if (inputId.isNotEmpty()) viewModel.updateSpreadsheetId(inputId) }) { Icon(Icons.Default.CloudUpload, null) }
                IconButton(onClick = { FirebaseAuth.getInstance().signOut() }) { Icon(Icons.Default.Logout, null) }
            })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (isRunning) { context.stopService(Intent(context, VoiceCommandService::class.java)); isRunning = false }
                else launcher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            }) { Icon(if (isRunning) Icons.Default.MicOff else Icons.Default.Mic, null) }
        }
    ) { p ->
        Column(Modifier.padding(p).fillMaxSize().padding(16.dp)) {
            OutlinedTextField(value = inputId, onValueChange = { inputId = it }, label = { Text("Google Sheet ID") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.weight(1f)) { items(products) { ProductItem(it) } }
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.height(100.dp)) { items(logs) { Text(it.message, fontSize = 12.sp) } }
        }
    }
}

@Composable
fun ProductItem(p: Product) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = if (p.quantity <= p.minStock) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), Arrangement.SpaceBetween) {
            Column { Text(p.name, fontWeight = FontWeight.Bold); Text("Mín: ${p.minStock}", style = MaterialTheme.typography.bodySmall) }
            Text("${p.quantity} ${p.unit}", fontWeight = FontWeight.Black)
        }
    }
}
