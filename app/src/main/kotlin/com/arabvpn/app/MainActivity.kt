package com.arabvpn.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibe.app.vpn.VpnScreen
import com.vibe.app.vpn.VpnViewModel

/**
 * Arab VPN launcher activity. It is intentionally independent of VibeApp/Hilt so the two
 * applications have separate process identity, storage, permissions, notifications and updates.
 */
class MainActivity : ComponentActivity() {
    private val vpnViewModel: VpnViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val state = vpnViewModel.uiState.collectAsStateWithLifecycle().value
            val darkTheme = isSystemInDarkTheme()

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
            ) { /* Update checks continue even if notifications are declined. */ }

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    runCatching {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            MaterialTheme(
                colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
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

                if (state.showBackgroundPrompt) {
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
        runCatching { vpnViewModel.retryLocationSyncIfNeeded() }
    }
}
