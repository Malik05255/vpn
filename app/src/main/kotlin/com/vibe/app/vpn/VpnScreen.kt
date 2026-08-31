package com.vibe.app.vpn

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibe.app.R
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
        val background = MaterialTheme.colorScheme.background
        val tint = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(tint, background, background),
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(state)
                ConnectionHub(state)
                CountrySelector(state, onCountrySelected)

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
private fun Header(state: VpnUiState) {
    val connected = state.connectionStatus == ConnectionStatus.CONNECTED
    val statusText = if (connected) "جلسة محمية" else "جاهز للاتصال"
    val statusColor = if (connected) MaterialTheme.colorScheme.secondary else Color.White.copy(alpha = 0.82f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.88f),
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.88f),
                    )
                )
            )
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    modifier = Modifier.size(60.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.16f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arab_vpn),
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = Color.Unspecified,
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Arab VPN",
                        color = Color.White,
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        "اتصال ذكي · تحقق فعلي · تشغيل تلقائي",
                        color = Color.White.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.16f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Text(
                            statusText,
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Text(
                "اختر الدولة فقط، والباقي علينا: أفضل مسار متاح، IP وDNS، فحص الجودة، ثم مزامنة الموقع.",
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun ConnectionHub(state: VpnUiState) {
    val connected = state.connectionStatus == ConnectionStatus.CONNECTED
    val busy = state.connectionStatus == ConnectionStatus.CONNECTING ||
        state.connectionStatus == ConnectionStatus.DISCONNECTING

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(78.dp),
                    shape = CircleShape,
                    color = when {
                        connected -> MaterialTheme.colorScheme.secondaryContainer
                        busy -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    border = BorderStroke(
                        6.dp,
                        when {
                            connected -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.24f)
                            busy -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        },
                    ),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_status_lock),
                                contentDescription = null,
                                modifier = Modifier.size(34.dp),
                                tint = if (connected) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    when (state.connectionStatus) {
                        ConnectionStatus.CONNECTING -> "نبني الاتصال الآمن…"
                        ConnectionStatus.CONNECTED -> "أنت متصل ومحمي"
                        ConnectionStatus.DISCONNECTING -> "جاري إنهاء الجلسة…"
                        ConnectionStatus.DISCONNECTED -> "اختر وجهتك وابدأ"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    when {
                        connected && state.selectedCountry != null ->
                            "الخروج الآن عبر ${state.selectedCountry.flag} ${state.selectedCountry.displayNameAr}"
                        busy -> "نختبر المسارات ونرفض أي اتصال دون معايير الجودة."
                        else -> "لا ملفات إعداد ولا اختيار سيرفرات يدوي."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun CountrySelector(
    state: VpnUiState,
    onCountrySelected: (VpnCountry) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("اختر الدولة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            Text(
                "3 وجهات",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VpnCountry.entries.forEach { country ->
                CountryTile(
                    modifier = Modifier.weight(1f),
                    country = country,
                    selected = state.selectedCountry == country,
                    enabled = state.connectionStatus == ConnectionStatus.DISCONNECTED,
                    onClick = { onCountrySelected(country) },
                )
            }
        }
    }
}

@Composable
private fun CountryTile(
    modifier: Modifier,
    country: VpnCountry,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        tonalElevation = if (selected) 3.dp else 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(country.flag, fontSize = 27.sp)
                }
            }
            Text(
                country.displayNameAr,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
            )
            Text(
                if (selected) "مختارة ✓" else "اختيار",
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun LocationSetupCard(onOpenLocationSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(modifier = Modifier.size(38.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondary) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_status_location),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
                Column {
                    Text("خطوة واحدة للموقع", fontWeight = FontWeight.ExtraBold)
                    Text(
                        "إعداد Android لمرة واحدة فقط",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "اختر Arab VPN كتطبيق الموقع للاختبار، ثم ارجع. سيكمل التطبيق مزامنة الموقع تلقائياً.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(16.dp),
                onClick = onOpenLocationSettings,
            ) {
                Text("فتح خيارات المطور", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MessageCard(message: String, isError: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                if (isError) "!" else "✓",
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.tertiary,
            )
            Text(
                message,
                modifier = Modifier.weight(1f),
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun ConnectionDetails(state: VpnUiState, location: IpLocation) {
    val report = state.qualityReport
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("الجلسة الحالية", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "تم التحقق من المسار والوجهة",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        "موثّق ✓",
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(
                    modifier = Modifier.weight(1f),
                    icon = R.drawable.ic_status_globe,
                    label = "IP",
                    value = location.ip.ifBlank { "—" },
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    icon = R.drawable.ic_status_speed,
                    label = "Ping",
                    value = report?.let { "${it.medianLatencyMs} ms" } ?: "—",
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    icon = R.drawable.ic_status_speed,
                    label = "السرعة",
                    value = report?.let { String.format(Locale.US, "%.1fM", it.downloadMbps) } ?: "—",
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

            DetailRow("الدولة", state.selectedCountry?.let { "${it.flag} ${it.displayNameAr}" }.orEmpty())
            if (location.city.isNotBlank()) DetailRow("مدينة الخروج", location.city)
            state.protocol?.let { DetailRow("البروتوكول", it.uppercase(Locale.US)) }
            DetailRow(
                "الموقع",
                when (state.locationSyncStatus) {
                    LocationSyncStatus.ACTIVE -> state.locationTarget?.city?.let { "$it ✓" } ?: "متزامن ✓"
                    LocationSyncStatus.NEEDS_SETUP -> "يحتاج إعداد Android"
                    LocationSyncStatus.SYNCING -> "جاري الضبط…"
                    LocationSyncStatus.FAILED -> "تعذر الضبط"
                    LocationSyncStatus.IDLE -> "غير نشط"
                },
            )
        }
    }
}

@Composable
private fun MetricTile(modifier: Modifier, icon: Int, label: String, value: String) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Surface(modifier = Modifier.size(30.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
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
        Text(value, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    }
}

@Composable
private fun ConnectionButton(state: VpnUiState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val selected = state.selectedCountry
    val busy = state.connectionStatus == ConnectionStatus.CONNECTING ||
        state.connectionStatus == ConnectionStatus.DISCONNECTING
    val connected = state.connectionStatus == ConnectionStatus.CONNECTED

    Button(
        modifier = Modifier.fillMaxWidth().height(62.dp),
        enabled = !busy && (connected || selected != null),
        onClick = if (connected) onDisconnect else onConnect,
        shape = RoundedCornerShape(21.dp),
        colors = if (connected) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        },
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(23.dp),
                strokeWidth = 2.4.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.size(11.dp))
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_status_lock),
                contentDescription = null,
                modifier = Modifier.size(23.dp),
                tint = if (connected) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.size(9.dp))
        }
        Text(
            when (state.connectionStatus) {
                ConnectionStatus.CONNECTING -> "نبحث عن أفضل اتصال…"
                ConnectionStatus.CONNECTED -> "فصل الجلسة الآمنة"
                ConnectionStatus.DISCONNECTING -> "جاري الفصل…"
                ConnectionStatus.DISCONNECTED -> if (selected == null) "اختر دولة أولاً" else "اتصال آمن بـ ${selected.displayNameAr}"
            },
            fontWeight = FontWeight.ExtraBold,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun QualityPolicyNote() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Text(
            "نعتبر الاتصال موثّقاً فقط بعد تطابق الدولة في فحصين مستقلين، Ping ≤ ${ConnectionQualityClient.MAX_MEDIAN_LATENCY_MS}ms، وسرعة ≥ ${String.format(Locale.US, "%.1f", ConnectionQualityClient.MIN_DOWNLOAD_MBPS)} Mbps. Android يعلّم الموقع المُدخل عبر وضع الاختبار كموقع Mock للنظام.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}
