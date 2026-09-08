package com.malik.lmai.presentation.ui.reminder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.malik.lmai.R

/**
 * Small in-context setup card for the Android permissions H actually needs.
 * Background location is deliberately handled separately on Android 11+, where
 * Android expects the user to choose "Allow all the time" from app settings.
 */
@Composable
fun HReminderPermissionCard(
    needsNotifications: Boolean,
    needsLocation: Boolean,
    onPermissionsChanged: () -> Unit,
) {
    // H's configurable place-search anchor belongs beside location reminder controls.
    // It is shown even when Android permissions are already granted.
    HLocationScopeSettingsCard()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshGeneration by remember { mutableIntStateOf(0) }

    // Reading this state in composition makes permission checks refresh after each launcher result.
    @Suppress("UNUSED_VARIABLE")
    val refreshRead = refreshGeneration

    val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val fineLocationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val backgroundLocationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshGeneration++
        onPermissionsChanged()
    }
    val fineLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshGeneration++
        onPermissionsChanged()
    }
    val backgroundLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshGeneration++
        onPermissionsChanged()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshGeneration++
                onPermissionsChanged()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val missingNotifications = needsNotifications && !notificationsGranted
    val missingFineLocation = needsLocation && !fineLocationGranted
    val missingBackgroundLocation = needsLocation && fineLocationGranted && !backgroundLocationGranted
    if (!missingNotifications && !missingFineLocation && !missingBackgroundLocation) return

    val explanation = when {
        missingNotifications -> stringResource(R.string.h_reminder_permission_notifications)
        missingFineLocation -> stringResource(R.string.h_reminder_permission_location)
        else -> stringResource(R.string.h_reminder_permission_background)
    }

    val needsSettingsForBackground = missingBackgroundLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Outlined.NotificationsActive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.h_reminder_permission_needed),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    when {
                        missingNotifications && Build.VERSION.SDK_INT >= 33 ->
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

                        missingFineLocation ->
                            fineLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)

                        missingBackgroundLocation && Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
                            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

                        missingBackgroundLocation -> {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            )
                            context.startActivity(intent)
                        }
                    }
                },
            ) {
                Text(
                    if (needsSettingsForBackground) {
                        stringResource(R.string.h_reminder_permission_open_settings)
                    } else {
                        stringResource(R.string.h_reminder_permission_enable)
                    }
                )
            }
        }
    }
}
