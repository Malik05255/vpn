package com.malik.lmai.presentation.ui.h

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.malik.lmai.feature.mcp.PeachMcpClient
import com.malik.lmai.feature.mcp.PeachMcpOAuthCallbackBus
import com.malik.lmai.feature.mcp.PeachMcpOAuthCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class PeachMcpSettingsViewModel @Inject constructor(
    private val oauth: PeachMcpOAuthCoordinator,
    private val client: PeachMcpClient,
) : ViewModel() {
    data class UiState(
        val connected: Boolean = false,
        val busy: Boolean = false,
        val toolCount: Int? = null,
        val message: String? = null,
        val error: String? = null,
    )

    private val mutableState = MutableStateFlow(UiState(connected = oauth.isConnected()))
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private val mutableOpenUrl = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openUrl: SharedFlow<String> = mutableOpenUrl.asSharedFlow()

    init {
        viewModelScope.launch {
            PeachMcpOAuthCallbackBus.callbacks.collect { uri ->
                mutableState.value = mutableState.value.copy(busy = true, error = null, message = null)
                val result = oauth.complete(uri)
                if (result.isSuccess) {
                    client.resetSession()
                    mutableState.value = UiState(
                        connected = true,
                        message = "Peach connected to H",
                    )
                    testConnection()
                } else {
                    mutableState.value = mutableState.value.copy(
                        connected = oauth.isConnected(),
                        busy = false,
                        error = describeError(result.exceptionOrNull(), "Peach authorization failed"),
                    )
                }
            }
        }
    }

    fun connect() {
        if (mutableState.value.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            mutableState.value = mutableState.value.copy(busy = true, error = null, message = null)
            val result = oauth.begin()
            val url = result.getOrNull()
            if (url != null) {
                mutableState.value = mutableState.value.copy(busy = false)
                mutableOpenUrl.emit(url)
            } else {
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    error = describeError(result.exceptionOrNull(), "Could not start Peach authorization"),
                )
            }
        }
    }

    fun testConnection() {
        if (!oauth.isConnected() || mutableState.value.busy) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            val result = client.healthCheck()
            if (result.isSuccess) {
                mutableState.value = mutableState.value.copy(
                    connected = true,
                    busy = false,
                    toolCount = result.getOrNull(),
                    message = "H can access Peach WhatsApp tools",
                )
            } else {
                mutableState.value = mutableState.value.copy(
                    connected = oauth.isConnected(),
                    busy = false,
                    error = describeError(result.exceptionOrNull(), "Peach connection test failed"),
                )
            }
        }
    }

    fun disconnect() {
        oauth.disconnect()
        client.resetSession()
        mutableState.value = UiState(connected = false, message = "Peach disconnected")
    }

    private fun describeError(error: Throwable?, fallback: String): String {
        if (error == null) return fallback
        val message = error.message?.trim().orEmpty()
        return if (message.isNotEmpty()) {
            "$message [${error::class.java.simpleName}]"
        } else {
            "$fallback [${error::class.java.simpleName}]"
        }
    }
}
