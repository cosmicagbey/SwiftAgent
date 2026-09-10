package com.momo.swift.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.momo.swift.data.TransactionLogDao
import com.momo.swift.data.TransactionLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.Calendar

enum class SummaryPeriod(val label: String, val days: Int) {
    WEEK("Week", 7),
    MONTH("Month", 30),
    YEAR("Year", 365)
}

enum class TransactionTypeFilter(val label: String) {
    ALL("All"),
    CASH_IN("Cash In"),
    CASH_OUT("Cash Out")
}


class TransactionLogViewModel(private val dao: TransactionLogDao) : ViewModel() {

    private val _selectedPeriod = MutableStateFlow(SummaryPeriod.WEEK)
    val selectedPeriod: StateFlow<SummaryPeriod> = _selectedPeriod

    private val _selectedTypeFilter = MutableStateFlow(TransactionTypeFilter.ALL)
    val selectedTypeFilter: StateFlow<TransactionTypeFilter> = _selectedTypeFilter

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _searchActive = MutableStateFlow(false)
    val searchActive: StateFlow<Boolean> = _searchActive

    val filteredLogs: StateFlow<List<TransactionLogEntry>> = combine(
        dao.getAllFlow(),
        _selectedPeriod,
        _selectedTypeFilter,
        _searchQuery
    ) { logs, period, typeFilter, query ->
        val periodFiltered = filterByPeriod(logs, period)
        val typeFiltered = periodFiltered.filter { entry ->
            when (typeFilter) {
                TransactionTypeFilter.ALL -> true
                TransactionTypeFilter.CASH_IN -> entry.name.contains("Cash In", ignoreCase = true)
                TransactionTypeFilter.CASH_OUT -> entry.name.contains("Cash Out", ignoreCase = true)
            }
        }
        if (query.isBlank()) typeFiltered
        else typeFiltered.filter { entry ->
            entry.name.contains(query, ignoreCase = true) ||
            entry.phone.contains(query, ignoreCase = true) ||
            entry.amount.contains(query, ignoreCase = true) ||
            entry.responseText.contains(query, ignoreCase = true)
        }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /** Total GHS value of successful transactions — computed off the UI thread. */
    val summaryTotal: StateFlow<Double> = filteredLogs
        .map { logs ->
            logs.filter { it.status == com.momo.swift.data.TransactionStatus.SUCCESS }
                .mapNotNull { it.amount.toDoubleOrNull() }
                .sum()
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    /** Count of successful transactions — computed off the UI thread. */
    val summaryCount: StateFlow<Int> = filteredLogs
        .map { logs -> logs.count { it.status == com.momo.swift.data.TransactionStatus.SUCCESS } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setPeriod(period: SummaryPeriod) {
        _selectedPeriod.value = period
    }

    fun setTypeFilter(typeFilter: TransactionTypeFilter) {
        _selectedTypeFilter.value = typeFilter
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSearchActive(active: Boolean) {
        _searchActive.value = active
        if (!active) _searchQuery.value = ""
    }

    private fun filterByPeriod(
        entries: List<TransactionLogEntry>,
        period: SummaryPeriod
    ): List<TransactionLogEntry> {
        val now = Calendar.getInstance()
        val nowYear = now.get(Calendar.YEAR)
        val nowMonth = now.get(Calendar.MONTH)
        val nowWeek = now.get(Calendar.WEEK_OF_YEAR)

        val entryCal = Calendar.getInstance()

        return entries.filter { entry ->
            entryCal.timeInMillis = entry.timestamp
            val entryYear = entryCal.get(Calendar.YEAR)
            val entryMonth = entryCal.get(Calendar.MONTH)
            val entryWeek = entryCal.get(Calendar.WEEK_OF_YEAR)

            when (period) {
                SummaryPeriod.WEEK -> {
                    entryYear == nowYear && entryWeek == nowWeek
                }
                SummaryPeriod.MONTH -> {
                    entryYear == nowYear && entryMonth == nowMonth
                }
                SummaryPeriod.YEAR -> {
                    entryYear == nowYear
                }
            }
        }
    }

    class Factory(private val dao: TransactionLogDao) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(TransactionLogViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return TransactionLogViewModel(dao) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
