package com.vibe.app.vpn

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun VpnScreen(
    state: VpnUiState,
    onCountrySelected: (VpnCountry) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header()

                Text(
                    text = "اختر الدولة فقط. التطبيق يتولى تلقائياً VPN وIP وDNS والتحقق من الجودة ومزامنة الموقع.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                VpnCountry.entries.forEach { country ->
                    CountryCard(
                        country = country,
                        selected = state.selectedCountry == country,
                        enabled = state.connectionStatus == ConnectionStatus.DISCONNECTED,
                        onClick = { onCountrySelected(country) },
                    )
                }

                state.errorMessage?.let { MessageCard(it, isError = true) }
                state.noticeMessage?.let { MessageCard(it, isError = false) }

                if (state.locationSyncStatus == LocationSyncStatus.NEEDS_SETUP) {
                    LocationSetupCard(onOpenLocationSettings)
                }

                state.ipLocation?.let { location ->
                    ConnectionDetails(state, location)
                }

                ConnectionButton(state, onConnect, onDisconnect)
                QualityPolicyNote()
            }
        }
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(modifier = Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Column {
            Text("Arab VPN", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "دولة واحدة · كل شيء تلقائي",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CountryCard(
    country: VpnCountry,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(country.flag, fontSize = 34.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(country.displayNameAr, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "VPN + IP + DNS + موقع تلقائي",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = "محدد", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun LocationSetupCard(onOpenLocationSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.LocationOn, contentDescription = null)
                Text("إعداد الموقع لمرة واحدة", fontWeight = FontWeight.Bold)
            }
            Text(
                "Android يتطلب أن تختار Arab VPN كتطبيق الموقع للاختبار مرة واحدة. بعد العودة للتطبيق ستتم مزامنة الموقع تلقائياً بدون ضغط إضافي.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onOpenLocationSettings) {
                Text("فتح خيارات المطور")
            }
        }
    }
}

@Composable
private fun MessageCard(message: String, isError: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Text(
            message,
            modifier = Modifier.padding(16.dp),
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun ConnectionDetails(state: VpnUiState, location: IpLocation) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("الاتصال موثّق", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            DetailRow("الدولة", state.selectedCountry?.let { "${it.flag} ${it.displayNameAr}" }.orEmpty())
            DetailRow("IP", location.ip.ifBlank { "غير متاح" })
            if (location.city.isNotBlank()) DetailRow("مدينة خروج IP", location.city)
            state.protocol?.let { DetailRow("البروتوكول", it) }
            state.qualityReport?.let { report ->
                DetailRow("Ping", "${report.medianLatencyMs} ms")
                DetailRow("السرعة", "${String.format(Locale.US, "%.1f", report.downloadMbps)} Mbps")
                DetailRow("فحص الدولة", if (report.geoVerified) "متطابق ✓" else "غير مكتمل")
            }
            DetailRow(
                "الموقع",
                when (state.locationSyncStatus) {
                    LocationSyncStatus.ACTIVE -> state.locationTarget?.city?.let { "$it ✓" } ?: "متزامن ✓"
                    LocationSyncStatus.NEEDS_SETUP -> "يحتاج إعداد Android مرة واحدة"
                    LocationSyncStatus.SYNCING -> "جاري الضبط…"
                    LocationSyncStatus.FAILED -> "تعذر الضبط"
                    LocationSyncStatus.IDLE -> "غير نشط"
                },
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}

@Composable
private fun ConnectionButton(state: VpnUiState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val selected = state.selectedCountry
    val busy = state.connectionStatus == ConnectionStatus.CONNECTING ||
        state.connectionStatus == ConnectionStatus.DISCONNECTING
    val connected = state.connectionStatus == ConnectionStatus.CONNECTED

    Button(
        modifier = Modifier.fillMaxWidth().height(58.dp),
        enabled = !busy && (connected || selected != null),
        onClick = if (connected) onDisconnect else onConnect,
        colors = if (connected) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        else ButtonDefaults.buttonColors(),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.size(10.dp))
        } else {
            Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(
            when (state.connectionStatus) {
                ConnectionStatus.CONNECTING -> "جاري تجهيز كل شيء…"
                ConnectionStatus.CONNECTED -> "فصل الاتصال"
                ConnectionStatus.DISCONNECTING -> "جاري الفصل…"
                ConnectionStatus.DISCONNECTED -> if (selected == null) "اختر دولة" else "اتصال بـ ${selected.displayNameAr}"
            },
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun QualityPolicyNote() {
    Text(
        "لا تظهر حالة الاتصال الموثّق إلا بعد تطابق الدولة في فحصين مستقلين، Ping ≤ ${ConnectionQualityClient.MAX_MEDIAN_LATENCY_MS}ms، وسرعة ≥ ${String.format(Locale.US, "%.1f", ConnectionQualityClient.MIN_DOWNLOAD_MBPS)} Mbps. Android يعلّم الموقع المُدخل عبر وضع الاختبار كموقع Mock للنظام.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}
