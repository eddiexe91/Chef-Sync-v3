package com.aistudio.chefsync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.chefsync.data.local.Product
import com.aistudio.chefsync.data.local.SyncLog
import com.aistudio.chefsync.data.repository.CloudSyncRepository
import com.aistudio.chefsync.data.repository.ProductRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InventoryViewModel(private val repository: ProductRepository, private val cloudRepository: CloudSyncRepository) : ViewModel() {
    val products: StateFlow<List<Product>> = repository.allProducts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val localLogs: StateFlow<List<SyncLog>> = repository.syncLogs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val spreadsheetId: StateFlow<String?> = cloudRepository.observeSpreadsheetId().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun updateSpreadsheetId(newId: String) {
        viewModelScope.launch { cloudRepository.updateSpreadsheetId(newId) }
    }
}
