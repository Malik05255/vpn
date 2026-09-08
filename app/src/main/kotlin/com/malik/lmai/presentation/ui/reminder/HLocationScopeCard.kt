package com.malik.lmai.presentation.ui.reminder

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.malik.lmai.BuildConfig
import com.malik.lmai.R
import com.malik.lmai.feature.reminder.HLocationScope

@Composable
fun HLocationScopeCard(
    viewModel: HRemindersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HLocationScopeCardContent(
        scope = state.locationScope,
        busy = state.locationScopeBusy,
        message = state.locationScopeMessage,
        onPinCurrent = viewModel::pinCurrentLocation,
        onRadiusChange = viewModel::setLocationScopeRadius,
        onClear = viewModel::clearLocationScope,
    )
}

@Composable
private fun HLocationScopeCardContent(
    scope: HLocationScope,
    busy: Boolean,
    message: String?,
    onPinCurrent: () -> Unit,
    onRadiusChange: (Double) -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onPinCurrent()
    }
    val anchor = if (scope.isConfigured) LatLng(scope.anchorLatitude!!, scope.anchorLongitude!!) else null
    val mapsConfigured = BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank()
    val primary = MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.h_location_scope_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.h_location_scope_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                if (scope.isConfigured) {
                    stringResource(
                        R.string.h_location_scope_status_configured,
                        scope.anchorLabel ?: stringResource(R.string.h_location_scope_anchor_fallback),
                        scope.radiusKm.toInt(),
                    )
                } else {
                    stringResource(R.string.h_location_scope_status_unconfigured, scope.radiusKm.toInt())
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HLocationScope.PRESET_RADII_KM.forEach { radius ->
                    FilterChip(
                        selected = scope.radiusKm == radius,
                        onClick = { onRadiusChange(radius) },
                        label = { Text(stringResource(R.string.h_location_scope_km, radius.toInt())) },
                    )
                }
            }

            if (anchor != null && mapsConfigured) {
                val zoom = remember(scope.radiusKm) {
                    when {
                        scope.radiusKm >= 180 -> 6.0f
                        scope.radiusKm >= 90 -> 7.0f
                        scope.radiusKm >= 45 -> 8.0f
                        else -> 9.0f
                    }
                }
                val camera = rememberCameraPositionState {
                    position = CameraPosition.fromLatLngZoom(anchor, zoom)
                }
                GoogleMap(
                    modifier = Modifier.fillMaxWidth().height(190.dp),
                    cameraPositionState = camera,
                    uiSettings = com.google.maps.android.compose.MapUiSettings(
                        zoomControlsEnabled = false,
                        mapToolbarEnabled = false,
                        myLocationButtonEnabled = false,
                    ),
                ) {
                    Marker(
                        state = MarkerState(anchor),
                        title = scope.anchorLabel ?: stringResource(R.string.h_location_scope_anchor_fallback),
                    )
                    Circle(
                        center = anchor,
                        radius = scope.radiusKm * 1000.0,
                        fillColor = primary.copy(alpha = 0.10f),
                        strokeColor = primary.copy(alpha = 0.75f),
                        strokeWidth = 3f,
                    )
                }
            }

            Text(
                stringResource(R.string.h_location_scope_not_geofence),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) onPinCurrent() else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    },
                ) {
                    Icon(Icons.Outlined.MyLocation, contentDescription = null)
                    Text(
                        if (scope.isConfigured) stringResource(R.string.h_location_scope_repin)
                        else stringResource(R.string.h_location_scope_pin),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                if (scope.isConfigured) {
                    OutlinedButton(onClick = onClear, enabled = !busy) {
                        Text(stringResource(R.string.h_location_scope_clear))
                    }
                }
            }
        }
    }
}
