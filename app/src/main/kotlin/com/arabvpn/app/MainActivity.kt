package com.arabvpn.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arabvpn.app.update.GitHubUpdateClient
import com.arabvpn.app.update.UpdateDownloadReceiver
import com.arabvpn.app.update.UpdateManifest
import com.arabvpn.app.update.UpdateNotifications
import com.vibe.app.BuildConfig
import com.vibe.app.vpn.SingBoxVpnService
import com.vibe.app.vpn.VpnScreen
import com.vibe.app.vpn.VpnViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

private val ArabVpnLightColors = lightColorScheme(
    primary = Color(0xFF5B5BF7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E7FF),
    onPrimaryContainer = Color(0xFF19194F),
    secondary = Color(0xFF00A7A5),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8F7F4),
    onSecondaryContainer = Color(0xFF003735),
    tertiary = Color(0xFFFF7352),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE2DA),
    onTertiaryContainer = Color(0xFF571507),
    background = Color(0xFFF5F7FC),
    onBackground = Color(0xFF141827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141827),
    surfaceVariant = Color(0xFFE8EBF4),
    onSurfaceVariant = Color(0xFF596074),
    outline = Color(0xFF8990A2),
    outlineVariant = Color(0xFFD7DBE7),
    error = Color(0xFFD23F47),
)

private val ArabVpnDarkColors = darkColorScheme(
    primary = Color(0xFF9A9BFF),
    onPrimary = Color(0xFF111147),
    primaryContainer = Color(0xFF2E2E73),
    onPrimaryContainer = Color(0xFFE4E4FF),
    secondary = Color(0xFF5DE0D8),
    onSecondary = Color(0xFF003735),
    secondaryContainer = Color(0xFF0D4B49),
    onSecondaryContainer = Color(0xFFBDF4EF),
    tertiary = Color(0xFFFFA187),
    onTertiary = Color(0xFF5C1707),
    tertiaryContainer = Color(0xFF71301F),
    onTertiaryContainer = Color(0xFFFFDAD0),
    background = Color(0xFF070A12),
    onBackground = Color(0xFFF2F4FA),
    surface = Color(0xFF0D1220),
    onSurface = Color(0xFFF2F4FA),
    surfaceVariant = Color(0xFF1A2030),
    onSurfaceVariant = Color(0xFFB9C0D0),
    outline = Color(0xFF7F8799),
    outlineVariant = Color(0xFF303748),
    error = Color(0xFFFF7B82),
)

/**
 * Arab VPN launcher activity. It is intentionally independent of VibeApp/Hilt so the two
 * applications have separate process identity, storage, permissions, notifications and updates.
 */
class MainActivity : ComponentActivity() {
    private val vpnViewModel: VpnViewModel by viewModels()

    // Incremented on every resume so the visible updater does not depend on Activity recreation.
    private val updateCheckGeneration = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val state = vpnViewModel.uiState.collectAsStateWithLifecycle().value
            val checkGeneration = updateCheckGeneration.collectAsStateWithLifecycle().value
            val darkTheme = isSystemInDarkTheme()
            var availableUpdate by remember { mutableStateOf<UpdateManifest?>(null) }
            var dismissedUpdateVersion by remember { mutableStateOf<Int?>(null) }

            // Check immediately and on every return to the app. If an update exists, surface both
            // an in-app dialog and an Android notification. The notification remains useful when
            // the user chooses "لاحقاً" in the dialog.
            LaunchedEffect(checkGeneration) {
                val update = runCatching {
                    withContext(Dispatchers.IO) {
                        GitHubUpdateClient().fetchLatestManifest()
                            ?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
                    }
                }.getOrNull()

                if (update != null) {
                    UpdateNotifications.showAvailable(this@MainActivity, update)
                    if (dismissedUpdateVersion != update.versionCode) {
                        availableUpdate = update
                    }
                } else {
                    availableUpdate = null
                }
            }

            val vpnPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    vpnViewModel.connectAuthorized()
                } else {
                    vpnViewModel.onVpnPermissionDenied()
                }
            }

            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { /* The in-app update dialog still works if notifications are declined. */ }

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                LaunchedEffect(Unit) {
                    runCatching {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            MaterialTheme(
                colorScheme = if (darkTheme) ArabVpnDarkColors else ArabVpnLightColors,
            ) {
                VpnScreen(
                    state = state,
                    onCountrySelected = vpnViewModel::selectCountry,
                    onConnect = {
                        runCatching {
                            val permissionIntent = vpnViewModel.prepareVpnPermission()
                            if (permissionIntent == null) {
                                vpnViewModel.connectAuthorized()
                            } else {
                                vpnPermissionLauncher.launch(permissionIntent)
                            }
                        }.onFailure {
                            vpnViewModel.onConnectionLaunchFailure(it)
                        }
                    },
                    onDisconnect = vpnViewModel::disconnect,
                    onOpenLocationSettings = {
                        runCatching { startActivity(vpnViewModel.mockLocationSettingsIntent()) }
                    },
                )

                val update = availableUpdate
                if (update != null) {
                    AlertDialog(
                        onDismissRequest = {
                            dismissedUpdateVersion = update.versionCode
                            availableUpdate = null
                        },
                        title = { Text("⬆ تحديث جديد لـ Arab VPN") },
                        text = {
                            Text(
                                "الإصدار ${update.versionName} متاح الآن. يمكنك تنزيل التحديث مباشرة، وسيستخدم التطبيق ملف الفرق فقط عندما يكون أصغر من الحزمة الكاملة."
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    dismissedUpdateVersion = update.versionCode
                                    UpdateDownloadReceiver.enqueue(this@MainActivity)
                                    availableUpdate = null
                                    Toast.makeText(
                                        this@MainActivity,
                                        "بدأ تنزيل التحديث",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            ) {
                                Text("تحديث الآن")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    dismissedUpdateVersion = update.versionCode
                                    availableUpdate = null
                                }
                            ) {
                                Text("لاحقاً")
                            }
                        },
                    )
                } else if (state.showBackgroundPrompt) {
                    AlertDialog(
                        onDismissRequest = vpnViewModel::dismissBackgroundPrompt,
                        title = {
                            Text("هل تريد أن أعمل في الخلفية؟")
                        },
                        text = {
                            Text(
                                "إذا اخترت نعم، سيتم إغلاق واجهة Arab VPN فقط، بينما يبقى VPN والموقع يعملان في الخلفية. يمكنك فتح التطبيق مرة أخرى في أي وقت."
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    vpnViewModel.dismissBackgroundPrompt()
                                    SingBoxVpnService.enterBackgroundMode(this@MainActivity)
                                    finishAndRemoveTask()
                                }
                            ) {
                                Text("✅ نعم")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = vpnViewModel::dismissBackgroundPrompt) {
                                Text("❌ لا")
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateCheckGeneration.value = updateCheckGeneration.value + 1
        runCatching { vpnViewModel.retryLocationSyncIfNeeded() }
    }
}
