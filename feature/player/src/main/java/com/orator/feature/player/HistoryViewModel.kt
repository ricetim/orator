package com.orator.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.HistoryDao
import com.orator.core.database.HistoryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(historyDao: HistoryDao) : ViewModel() {

    val rows: StateFlow<List<HistoryEntity>> = historyDao.observeRecent(limit = 100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
