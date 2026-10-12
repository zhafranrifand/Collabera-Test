package com.expensemail.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-process sync status shared by the foreground service and the visible Compose UI. */
internal data class GmailSyncUiState(
    val isRunning: Boolean = false,
    val message: String = "",
    val fetched: Int = 0,
    val added: Int = 0,
    val batches: Int = 0,
    val targetTotal: Int = 0,
    val processed: Int = 0,
    val revision: Int = 0,
)

internal object GmailSyncRuntime {
    private val mutableState = MutableStateFlow(GmailSyncUiState())
    val state: StateFlow<GmailSyncUiState> = mutableState.asStateFlow()

    fun publish(state: GmailSyncUiState) {
        mutableState.value = state
    }
}
