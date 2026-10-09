package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.Product
import com.example.data.local.SyncLog
import com.example.data.local.Waste
import com.example.data.repository.CloudSyncRepository
import com.example.data.repository.ProductRepository
import com.example.data.repository.SheetColumnMapping
import com.example.data.repository.SheetPreviewData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SyncUiState {
    data object Idle : SyncUiState
    data object Syncing : SyncUiState
    data class Success(val message: String) : SyncUiState
    data class Error(val message: String) : SyncUiState
}

class InventoryViewModel(
    private val repository: ProductRepository,
    private val cloudRepository: CloudSyncRepository
) : ViewModel() {

    val products: StateFlow<List<Product>> = repository.allProducts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val localLogs: StateFlow<List<SyncLog>> = repository.syncLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val spreadsheetId: StateFlow<String?> = cloudRepository.observeSpreadsheetId()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _syncState = MutableStateFlow<SyncUiState>(SyncUiState.Idle)
    val syncState: StateFlow<SyncUiState> = _syncState.asStateFlow()

    private val _sheetPreview = MutableStateFlow<SheetPreviewData?>(null)
    val sheetPreview: StateFlow<SheetPreviewData?> = _sheetPreview.asStateFlow()

    private val _isPreviewLoading = MutableStateFlow(false)
    val isPreviewLoading: StateFlow<Boolean> = _isPreviewLoading.asStateFlow()

    fun updateSpreadsheetId(newId: String) {
        viewModelScope.launch {
            cloudRepository.updateSpreadsheetId(newId)
            syncWithGoogleSheets(newId)
        }
    }

    fun requestSheetPreview(idOrUrl: String, gid: String? = null) {
        if (idOrUrl.isBlank()) {
            _syncState.value = SyncUiState.Error("Ingresa la ID o URL de tu Google Sheet")
            return
        }

        viewModelScope.launch {
            _isPreviewLoading.value = true
            _syncState.value = SyncUiState.Syncing
            val result = repository.fetchSheetPreview(idOrUrl, gid)
            _isPreviewLoading.value = false

            result.fold(
                onSuccess = { preview ->
                    _sheetPreview.value = preview
                    _syncState.value = SyncUiState.Idle
                },
                onFailure = { error ->
                    _syncState.value = SyncUiState.Error(error.message ?: "No se pudo previsualizar la hoja")
                }
            )
        }
    }

    fun applyColumnMappingAndImport(mapping: SheetColumnMapping) {
        viewModelScope.launch {
            _syncState.value = SyncUiState.Syncing
            val result = repository.importSheetWithMapping(mapping)
            _sheetPreview.value = null // Close mapping wizard

            result.fold(
                onSuccess = { count ->
                    _syncState.value = SyncUiState.Success("¡Éxito! $count insumos importados con tu estructura personalizada.")
                    cloudRepository.updateSpreadsheetId(mapping.spreadsheetId)
                    cloudRepository.addLog("SYNC_CUSTOM", "OK", "$count insumos importados de gid=${mapping.gid}")
                },
                onFailure = { error ->
                    _syncState.value = SyncUiState.Error(error.message ?: "Error al importar con el formato indicado")
                }
            )
        }
    }

    fun dismissSheetPreview() {
        _sheetPreview.value = null
    }

    fun syncWithGoogleSheets(idOrUrl: String) {
        if (idOrUrl.isBlank()) {
            _syncState.value = SyncUiState.Error("Ingresa la ID o URL de tu Google Sheet")
            return
        }

        viewModelScope.launch {
            _syncState.value = SyncUiState.Syncing
            val result = repository.syncWithSheets(idOrUrl)
            result.fold(
                onSuccess = { count ->
                    _syncState.value = SyncUiState.Success("¡Sincronizado! $count insumos importados.")
                    cloudRepository.addLog("SYNC_SHEETS", "SUCCESS", "$count productos sincronizados")
                },
                onFailure = { error ->
                    _syncState.value = SyncUiState.Error(error.message ?: "Error al sincronizar con Google Sheets")
                    cloudRepository.addLog("SYNC_SHEETS", "ERROR", error.message ?: "Fallo de conexión")
                }
            )
        }
    }

    fun seedDemoData() {
        viewModelScope.launch {
            _syncState.value = SyncUiState.Syncing
            repository.seedDemoProducts()
            _syncState.value = SyncUiState.Success("Inventario de prueba cargado con éxito")
        }
    }

    fun clearInventory() {
        viewModelScope.launch {
            repository.clearInventory()
            _syncState.value = SyncUiState.Success("Inventario reiniciado")
        }
    }

    fun registerWaste(name: String, quantity: Double, unit: String) {
        viewModelScope.launch {
            val msg = repository.registerWaste(
                Waste(
                    productName = name,
                    quantity = quantity,
                    unit = unit,
                    reason = "Merma de cocina"
                )
            )
            cloudRepository.addLog("MERMA_MANUAL", "OK", msg)
        }
    }

    fun dismissSyncState() {
        _syncState.value = SyncUiState.Idle
    }
}
