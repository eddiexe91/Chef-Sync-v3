package com.aistudio.chefsync.xvqp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.chefsync.xvqp.data.local.Product
import com.aistudio.chefsync.xvqp.data.local.SyncLog
import com.aistudio.chefsync.xvqp.data.repository.CloudSyncRepository
import com.aistudio.chefsync.xvqp.data.repository.ProductRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    fun syncData(spreadsheetId: String, accessToken: String) {
        viewModelScope.launch {
            repository.syncWithSheets(spreadsheetId, accessToken)
            cloudRepository.addLog("SYNC_SHEETS", "SUCCESS", "Sincronización manual iniciada")
        }
    }

    fun updateSpreadsheetId(newId: String) {
        viewModelScope.launch {
            cloudRepository.updateSpreadsheetId(newId)
        }
    }
}
