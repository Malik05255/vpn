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
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
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
    onImportProfile: () -> Unit,
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
                    text = "اختر الدولة، والتطبيق يبحث تلقائياً عن أفضل خادم مجاني متاح ثم يتحقق منه قبل إعلان الاتصال.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                VpnCountry.entries.forEach { country ->
                    CountryCard(
                        country = country,
                        selected = state.selectedCountry == country,
                        hasPrivateFallback = country in state.configuredCountries,
                        enabled = state.connectionStatus == ConnectionStatus.DISCONNECTED,
                        onClick = { onCountrySelected(country) },
                    )
                }

                state.selectedCountry?.let { selected ->
                    if (state.connectionStatus == ConnectionStatus.DISCONNECTED) {
                        SetupCard(
                            country = selected,
                            hasPrivateFallback = selected in state.configuredCountries,
                            onImportProfile = onImportProfile,
                        )
                    }
                }

                state.errorMessage?.let { MessageCard(it, isError = true) }
                state.noticeMessage?.let { MessageCard(it, isError = false) }

                state.ipLocation?.let { location ->
                    ConnectionDetails(
                        state = state,
                        country = state.selectedCountry,
                        location = location,
                        quality = state.qualityReport,
                    )
                }

                ConnectionButton(
                    state = state,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                )

                QualityPolicyNote()
                PrivacyNote()
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
            Box(
                modifier = Modifier.size(58.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Column {
            Text(
                text = "Arab VPN",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "اختيار تلقائي · فحص دولة · فحص جودة",
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
    hasPrivateFallback: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val border = if (selected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        border = border,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(text = country.flag, fontSize = 34.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = country.displayNameAr,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (hasPrivateFallback) {
                        "بحث تلقائي + WireGuard احتياطي"
                    } else {
                        "بحث تلقائي عن أفضل خادم مجاني"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "الدولة المحددة",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SetupCard(
    country: VpnCountry,
    hasPrivateFallback: Boolean,
    onImportProfile: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = null)
                Text(
                    text = "احتياطي خاص لـ ${country.displayNameAr}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = if (hasPrivateFallback) {
                    "يوجد WireGuard احتياطي محفوظ على الجهاز. سيستخدمه التطبيق فقط إذا لم يجد مساراً عاماً يحقق شروط الجودة."
                } else {
                    "هذا اختياري فقط. البحث المجاني التلقائي يعمل من دون ملف، ويمكنك إضافة WireGuard خاص كمسار احتياطي لاحقاً."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onImportProfile,
            ) {
                Text(if (hasPrivateFallback) "استبدال WireGuard الاحتياطي" else "إضافة WireGuard احتياطي")
            }
        }
    }
}

@Composable
private fun MessageCard(message: String, isError: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            }
        ),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onTertiaryContainer
            },
        )
    }
}

@Composable
private fun ConnectionDetails(
    state: VpnUiState,
    country: VpnCountry?,
    location: IpLocation,
    quality: ConnectionQualityReport?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "الاتصال موثّق",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (quality?.geoVerified == true) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "تم التحقق",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            HorizontalDivider()
            DetailRow("الدولة المطلوبة", country?.let { "${it.flag} ${it.displayNameAr}" }.orEmpty())
            DetailRow("الدولة المكتشفة", location.country.ifBlank { location.countryCode })
            DetailRow("عنوان IP العام", location.ip.ifBlank { "غير متاح" })
            state.protocol?.let { DetailRow("البروتوكول", it) }
            state.engine?.let {
                DetailRow("محرك الاتصال", if (it == ConnectionEngine.SING_BOX) "sing-box" else "WireGuard")
            }
            state.nodeName?.takeIf(String::isNotBlank)?.let { DetailRow("المسار المختار", it.take(42)) }
            state.preflightLatencyMs?.let { DetailRow("استجابة الخادم قبل النفق", "$it ms") }
            quality?.let { report ->
                DetailRow("زمن الاستجابة الفعلي", "${report.medianLatencyMs} ms")
                DetailRow("سرعة التحميل المقاسة", "${String.format(Locale.US, "%.1f", report.downloadMbps)} Mbps")
                DetailRow("التحقق الجغرافي", if (report.geoVerified) "مصدران متطابقان ✓" else "غير مكتمل")
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Dns,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "DNS يمر داخل النفق، ولا تظهر حالة «متصل» إلا بعد اجتياز فحص الجودة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
private fun ConnectionButton(
    state: VpnUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val selected = state.selectedCountry
    val busy = state.connectionStatus == ConnectionStatus.CONNECTING ||
        state.connectionStatus == ConnectionStatus.DISCONNECTING
    val connected = state.connectionStatus == ConnectionStatus.CONNECTED

    Button(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        enabled = !busy && (connected || selected != null),
        onClick = if (connected) onDisconnect else onConnect,
        colors = if (connected) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.size(10.dp))
        } else {
            Icon(
                imageVector = Icons.Rounded.PowerSettingsNew,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.size(8.dp))
        }

        Text(
            text = when (state.connectionStatus) {
                ConnectionStatus.CONNECTING -> "جاري البحث والفحص والاتصال..."
                ConnectionStatus.CONNECTED -> "فصل الاتصال"
                ConnectionStatus.DISCONNECTING -> "جاري الفصل..."
                ConnectionStatus.DISCONNECTED -> if (selected == null) {
                    "اختر دولة أولاً"
                } else {
                    "ابحث واتصل عبر ${selected.displayNameAr}"
                }
            },
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun QualityPolicyNote() {
    Text(
        text = "سياسة الجودة: لا يظهر التطبيق حالة «متصل» إلا إذا تطابقت الدولة في فحصين مستقلين، وكان متوسط الاستجابة ≤ ${ConnectionQualityClient.MAX_MEDIAN_LATENCY_MS} ms، وسرعة التحميل المقاسة ≥ ${String.format(Locale.US, "%.1f", ConnectionQualityClient.MIN_DOWNLOAD_MBPS)} Mbps.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PrivacyNote() {
    Text(
        text = "الـVPN يغيّر مسار الإنترنت وعنوان IP وDNS فقط. لا يغيّر GPS أو شريحة SIM أو المنطقة الزمنية للجهاز.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    )
}
