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
    private val qualityClient = ConnectionQualityClient()

    private val _uiState = MutableStateFlow(
        VpnUiState(configuredCountries = configuredCountries())
    )
    val uiState: StateFlow<VpnUiState> = _uiState.asStateFlow()

    fun selectCountry(country: VpnCountry) {
        if (_uiState.value.connectionStatus != ConnectionStatus.DISCONNECTED) return
        _uiState.update {
            it.copy(
                selectedCountry = country,
                errorMessage = null,
                noticeMessage = null,
                ipLocation = null,
                qualityReport = null,
            )
        }
    }

    fun prepareVpnPermission(): Intent? = wireGuard.preparePermissionIntent()

    fun onVpnPermissionDenied() {
        _uiState.update {
            it.copy(
                errorMessage = "يجب السماح للتطبيق بإنشاء اتصال VPN حتى يتم تشغيل النفق.",
                noticeMessage = null,
            )
        }
    }

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
                    noticeMessage = "جاري التحقق من الدولة والسرعة وزمن الاستجابة...",
                    ipLocation = null,
                    qualityReport = null,
                )
            }

            runCatching {
                val config = profileStore.load(country)
                val state = wireGuard.connect(country, config)
                check(state == Tunnel.State.UP) { "تعذر رفع نفق WireGuard" }

                // Give Android a short moment to move subsequent sockets to the new VPN network.
                delay(900)

                // Do not trust the tunnel state alone. A successful result here means:
                // 1) two independent geo checks agree on the requested country,
                // 2) the public IP is consistent,
                // 3) median HTTPS latency is inside the quality budget,
                // 4) measured downstream throughput is above the minimum quality floor.
                qualityClient.verify(country)
            }.onSuccess { report ->
                _uiState.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.CONNECTED,
                        ipLocation = report.ipLocation,
                        qualityReport = report,
                        errorMessage = null,
                        noticeMessage = "تم التحقق من الاتصال فعلياً؛ الدولة والسرعة والجودة ضمن الحدود المطلوبة.",
                    )
                }
            }.onFailure { error ->
                runCatching { wireGuard.disconnect() }
                _uiState.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.DISCONNECTED,
                        ipLocation = null,
                        qualityReport = null,
                        noticeMessage = null,
                        errorMessage = error.message ?: "تعذر إنشاء اتصال VPN موثوق وعالي الجودة.",
                    )
                }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    connectionStatus = ConnectionStatus.DISCONNECTING,
                    errorMessage = null,
                    noticeMessage = null,
                )
            }
            runCatching { wireGuard.disconnect() }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            connectionStatus = ConnectionStatus.DISCONNECTED,
                            ipLocation = null,
                            qualityReport = null,
                            noticeMessage = "تم فصل الاتصال.",
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            connectionStatus = ConnectionStatus.DISCONNECTED,
                            ipLocation = null,
                            qualityReport = null,
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

    private fun configuredCountries(): Set<VpnCountry> = VpnCountry.entries
        .filter(profileStore::hasProfile)
        .toSet()
}

data class VpnUiState(
    val selectedCountry: VpnCountry? = null,
    val configuredCountries: Set<VpnCountry> = emptySet(),
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val ipLocation: IpLocation? = null,
    val qualityReport: ConnectionQualityReport? = null,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
)

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
}
