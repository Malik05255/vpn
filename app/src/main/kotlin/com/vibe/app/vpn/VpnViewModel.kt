package com.vibe.app.vpn

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VpnViewModel(application: Application) : AndroidViewModel(application) {
    private val profileStore = VpnProfileStore(application)
    private val wireGuard = WireGuardManager(application)
    private val ipClient = IpLocationClient()

    private val _uiState = MutableStateFlow(
        VpnUiState(configuredCountries = configuredCountries())
    )
    val uiState: StateFlow<VpnUiState> = _uiState.asStateFlow()

    fun selectCountry(country: VpnCountry) {
        if (_uiState.value.connectionStatus != ConnectionStatus.DISCONNECTED) return
        _uiState.update { it.copy(selectedCountry = country, errorMessage = null, noticeMessage = null) }
    }

    fun prepareVpnPermission(): Intent? = wireGuard.preparePermissionIntent()

    fun connectAuthorized() {
        val country = _uiState.value.selectedCountry ?: return
        if (!profileStore.hasProfile(country)) {
            _uiState.update {
                it.copy(errorMessage = "يلزم إعداد خادم ${country.displayNameAr} أولاً بملف WireGuard صالح.")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    connectionStatus = ConnectionStatus.CONNECTING,
                    errorMessage = null,
                    noticeMessage = null,
                    ipLocation = null,
                )
            }

            runCatching {
                val config = profileStore.load(country)
                val state = wireGuard.connect(country, config)
                check(state == Tunnel.State.UP) { "تعذر رفع نفق WireGuard" }

                // Give Android a short moment to move subsequent sockets to the new VPN network.
                delay(900)
                val location = ipClient.check()
                check(location.countryCode.equals(country.code, ignoreCase = true)) {
                    "تم إنشاء النفق لكن عنوان الخروج ظهر من ${location.country.ifBlank { location.countryCode.ifBlank { "دولة أخرى" } }} وليس ${country.displayNameAr}."
                }
                location
            }.onSuccess { location ->
                _uiState.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.CONNECTED,
                        ipLocation = location,
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                runCatching { wireGuard.disconnect() }
                _uiState.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.DISCONNECTED,
                        ipLocation = null,
                        errorMessage = error.message ?: "تعذر الاتصال بالـVPN.",
                    )
                }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            _uiState.update { it.copy(connectionStatus = ConnectionStatus.DISCONNECTING, errorMessage = null) }
            runCatching { wireGuard.disconnect() }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            connectionStatus = ConnectionStatus.DISCONNECTED,
                            ipLocation = null,
                            noticeMessage = "تم فصل الاتصال.",
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            connectionStatus = ConnectionStatus.DISCONNECTED,
                            ipLocation = null,
                            errorMessage = error.message ?: "تعذر فصل الاتصال بصورة سليمة.",
                        )
                    }
                }
        }
    }

    fun importProfile(uri: Uri) {
        val country = _uiState.value.selectedCountry ?: return
        viewModelScope.launch {
            runCatching {
                val resolver = getApplication<Application>().contentResolver
                resolver.openInputStream(uri)?.use { profileStore.import(country, it) }
                    ?: error("تعذر قراءة الملف المختار")
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        configuredCountries = configuredCountries(),
                        errorMessage = null,
                        noticeMessage = "تم حفظ إعداد خادم ${country.displayNameAr} داخل التطبيق.",
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        errorMessage = "ملف WireGuard غير صالح: ${error.message ?: "خطأ غير معروف"}",
                        noticeMessage = null,
                    )
                }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, noticeMessage = null) }
    }

    private fun configuredCountries(): Set<VpnCountry> = VpnCountry.entries
        .filter(profileStore::hasProfile)
        .toSet()
}

data class VpnUiState(
    val selectedCountry: VpnCountry? = null,
    val configuredCountries: Set<VpnCountry> = emptySet(),
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val ipLocation: IpLocation? = null,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
)

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
}
