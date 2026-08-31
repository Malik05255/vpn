package com.vibe.app.vpn

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VpnViewModel(application: Application) : AndroidViewModel(application) {
    private val automaticVpn = AutomaticVpnManager(application)
    private val mockLocation = MockLocationController(application)

    private val _uiState = MutableStateFlow(initialUiState())
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
                locationSyncStatus = LocationSyncStatus.IDLE,
                locationTarget = null,
                showBackgroundPrompt = false,
            )
        }
    }

    fun prepareVpnPermission(): Intent? = automaticVpn.preparePermissionIntent()

    fun mockLocationSettingsIntent(): Intent = mockLocation.developerOptionsIntent()

    fun onVpnPermissionDenied() {
        _uiState.update {
            it.copy(
                errorMessage = "يجب السماح للتطبيق بإنشاء اتصال VPN حتى يتم تشغيل النفق.",
                noticeMessage = null,
                showBackgroundPrompt = false,
            )
        }
    }

    fun onConnectionLaunchFailure(error: Throwable) {
        _uiState.update {
            it.copy(
                connectionStatus = ConnectionStatus.DISCONNECTED,
                noticeMessage = null,
                errorMessage = error.message ?: "تعذر بدء خدمة VPN على هذا الجهاز.",
                showBackgroundPrompt = false,
            )
        }
    }

    fun dismissBackgroundPrompt() {
        _uiState.update { it.copy(showBackgroundPrompt = false) }
    }

    fun connectAuthorized() {
        val country = _uiState.value.selectedCountry ?: return
        if (_uiState.value.connectionStatus != ConnectionStatus.DISCONNECTED) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    connectionStatus = ConnectionStatus.CONNECTING,
                    errorMessage = null,
                    noticeMessage = "جاري جمع واختبار المسارات والتحقق من دولة الخروج…",
                    ipLocation = null,
                    qualityReport = null,
                    engine = null,
                    protocol = null,
                    nodeName = null,
                    sourceId = null,
                    preflightLatencyMs = null,
                    locationSyncStatus = LocationSyncStatus.SYNCING,
                    locationTarget = null,
                    showBackgroundPrompt = false,
                )
            }

            runCatching {
                automaticVpn.connect(country) { progress ->
                    _uiState.update { state ->
                        if (state.connectionStatus == ConnectionStatus.CONNECTING) {
                            state.copy(noticeMessage = progress)
                        } else state
                    }
                }
            }.onSuccess { result ->
                val report = result.quality
                val locationTarget = CountryLocationResolver.resolve(country, report.ipLocation)
                val locationResult = SingBoxVpnService.syncLocation(getApplication(), locationTarget)
                val locationState = locationResult.toUiStatus()
                val activeLocation = (locationResult as? LocationSyncResult.Active)?.location

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
                        locationSyncStatus = locationState,
                        locationTarget = activeLocation,
                        errorMessage = (locationResult as? LocationSyncResult.Failed)?.reason,
                        noticeMessage = when (locationResult) {
                            is LocationSyncResult.Active -> if (report.performanceAcceptable) {
                                "تم ضبط VPN وIP وDNS والموقع تلقائياً على ${country.displayNameAr}."
                            } else {
                                "تم التحقق من ${country.displayNameAr} وتشغيل VPN والموقع. جودة الخادم المجاني الحالية منخفضة وقد تتغير."
                            }
                            LocationSyncResult.NeedsDeveloperSetup ->
                                "VPN وIP وDNS جاهزة. بقي إعداد Android لمرة واحدة: اختر Arab VPN كتطبيق الموقع الوهمي، ثم ارجع للتطبيق وسيتم ضبط الموقع تلقائياً."
                            is LocationSyncResult.Failed ->
                                "VPN وIP وDNS جاهزة، لكن تعذر مزامنة الموقع حالياً."
                        },
                        showBackgroundPrompt = locationResult is LocationSyncResult.Active,
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
                        locationSyncStatus = LocationSyncStatus.IDLE,
                        locationTarget = null,
                        noticeMessage = null,
                        errorMessage = error.message
                            ?: "تعذر العثور على اتصال مجاني يثبت دولة الخروج المطلوبة.",
                        showBackgroundPrompt = false,
                    )
                }
            }
        }
    }

    fun retryLocationSyncIfNeeded() {
        val state = _uiState.value
        val country = state.selectedCountry ?: return
        val ipLocation = state.ipLocation ?: return
        if (state.connectionStatus != ConnectionStatus.CONNECTED) return
        if (state.locationSyncStatus != LocationSyncStatus.NEEDS_SETUP) return
        if (!mockLocation.isAuthorized()) return

        viewModelScope.launch {
            _uiState.update { it.copy(locationSyncStatus = LocationSyncStatus.SYNCING) }
            val target = CountryLocationResolver.resolve(country, ipLocation)
            when (val result = SingBoxVpnService.syncLocation(getApplication(), target)) {
                is LocationSyncResult.Active -> _uiState.update {
                    it.copy(
                        locationSyncStatus = LocationSyncStatus.ACTIVE,
                        locationTarget = result.location,
                        errorMessage = null,
                        noticeMessage = "تمت مزامنة الموقع تلقائياً مع اتصال ${country.displayNameAr}.",
                        showBackgroundPrompt = true,
                    )
                }
                LocationSyncResult.NeedsDeveloperSetup -> _uiState.update {
                    it.copy(
                        locationSyncStatus = LocationSyncStatus.NEEDS_SETUP,
                        showBackgroundPrompt = false,
                    )
                }
                is LocationSyncResult.Failed -> _uiState.update {
                    it.copy(
                        locationSyncStatus = LocationSyncStatus.FAILED,
                        errorMessage = result.reason,
                        showBackgroundPrompt = false,
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
                    showBackgroundPrompt = false,
                )
            }
            val vpnResult = runCatching { automaticVpn.disconnect() }

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
                    locationSyncStatus = LocationSyncStatus.IDLE,
                    locationTarget = null,
                    noticeMessage = if (vpnResult.isSuccess) "تم فصل الاتصال والموقع." else null,
                    errorMessage = vpnResult.exceptionOrNull()?.message,
                    showBackgroundPrompt = false,
                )
            }
        }
    }

    private fun initialUiState(): VpnUiState = if (SingBoxVpnService.isRunning(getApplication())) {
        VpnUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            noticeMessage = "Arab VPN يعمل حالياً في الخلفية. اضغط فصل الاتصال إذا أردت إيقاف الجلسة.",
        )
    } else {
        VpnUiState()
    }
}

private fun LocationSyncResult.toUiStatus(): LocationSyncStatus = when (this) {
    is LocationSyncResult.Active -> LocationSyncStatus.ACTIVE
    LocationSyncResult.NeedsDeveloperSetup -> LocationSyncStatus.NEEDS_SETUP
    is LocationSyncResult.Failed -> LocationSyncStatus.FAILED
}

data class VpnUiState(
    val selectedCountry: VpnCountry? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val ipLocation: IpLocation? = null,
    val qualityReport: ConnectionQualityReport? = null,
    val engine: ConnectionEngine? = null,
    val protocol: String? = null,
    val nodeName: String? = null,
    val sourceId: String? = null,
    val preflightLatencyMs: Long? = null,
    val locationSyncStatus: LocationSyncStatus = LocationSyncStatus.IDLE,
    val locationTarget: CountryLocation? = null,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
    val showBackgroundPrompt: Boolean = false,
)

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
}

enum class LocationSyncStatus {
    IDLE,
    SYNCING,
    NEEDS_SETUP,
    ACTIVE,
    FAILED,
}
