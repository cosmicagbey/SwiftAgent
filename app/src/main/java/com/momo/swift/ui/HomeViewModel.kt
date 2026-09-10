package com.momo.swift.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.momo.swift.data.SavedContactDao
import com.momo.swift.data.SavedContactSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeViewModel(private val contactDao: SavedContactDao) : ViewModel() {

    private val searchQuery = MutableStateFlow("")

    /**
     * Live list of contact suggestions matching the current typed prefix.
     * Only emits when the user has typed at least 5 digits.
     */
    val matchingSuggestions: StateFlow<List<SavedContactSuggestion>> = searchQuery
        .flatMapLatest { query ->
            if (query.length >= 5) {
                contactDao.searchFlow(query)
            } else {
                flowOf(emptyList())
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    class Factory(private val contactDao: SavedContactDao) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(contactDao) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
