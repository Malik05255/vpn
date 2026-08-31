package com.vibe.app.vpn

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VpnViewModel(application: Application) : AndroidViewModel(application) {
    private val profileStore = VpnProfileStore(application)
    private val automaticVpn = AutomaticVpnManager(application)

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
                engine = null,
                protocol = null,
                nodeName = null,
                sourceId = null,
                preflightLatencyMs = null,
            )
        }
    }

    fun prepareVpnPermission(): Intent? = automaticVpn.preparePermissionIntent()

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
        if (_uiState.value.connectionStatus != ConnectionStatus.DISCONNECTED) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    connectionStatus = ConnectionStatus.CONNECTING,
                    errorMessage = null,
                    noticeMessage = "جاري البحث عن أفضل مسار مجاني والتحقق منه…",
                    ipLocation = null,
                    qualityReport = null,
                    engine = null,
                    protocol = null,
                    nodeName = null,
                    sourceId = null,
                    preflightLatencyMs = null,
                )
            }

            runCatching {
                automaticVpn.connect(country) { progress ->
                    _uiState.update { state ->
                        if (state.connectionStatus == ConnectionStatus.CONNECTING) {
                            state.copy(noticeMessage = progress)
                        } else {
                            state
                        }
                    }
                }
            }.onSuccess { result ->
                val report = result.quality
                _uiState.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.CONNECTED,
                        ipLocation = report.ipLocation,
                        qualityReport = report,
                        engine = result.mode,
                        protocol = result.protocol,
                        nodeName = result.nodeName,
                        sourceId = result.sourceId,
                        preflightLatencyMs = result.preflightLatencyMs,
                        errorMessage = null,
                        noticeMessage = "تم اختيار مسار اجتاز فحص الدولة والسرعة والجودة فعلياً.",
                    )
                }
            }.onFailure { error ->
                runCatching { automaticVpn.disconnect() }
                _uiState.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.DISCONNECTED,
                        ipLocation = null,
                        qualityReport = null,
                        engine = null,
                        protocol = null,
                        nodeName = null,
                        sourceId = null,
                        preflightLatencyMs = null,
                        noticeMessage = null,
                        errorMessage = error.message
                            ?: "تعذر العثور على اتصال مجاني يحقق معايير الدولة والسرعة والجودة.",
                    )
                }
            }
        }
    }

    fun disconnect() {
        if (_uiState.value.connectionStatus == ConnectionStatus.DISCONNECTING) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    connectionStatus = ConnectionStatus.DISCONNECTING,
                    errorMessage = null,
                    noticeMessage = null,
                )
            }
            runCatching { automaticVpn.disconnect() }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            connectionStatus = ConnectionStatus.DISCONNECTED,
                            ipLocation = null,
                            qualityReport = null,
                            engine = null,
                            protocol = null,
                            nodeName = null,
                            sourceId = null,
                            preflightLatencyMs = null,
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
                            engine = null,
                            protocol = null,
                            nodeName = null,
                            sourceId = null,
                            preflightLatencyMs = null,
                            errorMessage = error.message ?: "تعذر فصل الاتصال بصورة سليمة.",
                        )
                    }
                }
        }
    }

    fun importProfile(uri: Uri) {
        val country = _uiState.value.selectedCountry ?: return
        if (_uiState.value.connectionStatus != ConnectionStatus.DISCONNECTED) return

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
                        noticeMessage = "تم حفظ WireGuard الاحتياطي لـ ${country.displayNameAr} داخل الجهاز.",
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

    override fun onCleared() {
        // Do not disconnect a live VPN merely because Android recreated the UI/ViewModel.
        super.onCleared()
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
    val engine: ConnectionEngine? = null,
    val protocol: String? = null,
    val nodeName: String? = null,
    val sourceId: String? = null,
    val preflightLatencyMs: Long? = null,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
)

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
}
