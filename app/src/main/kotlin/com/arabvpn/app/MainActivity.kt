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
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arabvpn.app.update.GitHubUpdateClient
import com.arabvpn.app.update.UpdateDownloadReceiver
import com.arabvpn.app.update.UpdateManifest
import com.vibe.app.BuildConfig
import com.vibe.app.vpn.SingBoxVpnService
import com.vibe.app.vpn.VpnScreen
import com.vibe.app.vpn.VpnViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            var availableUpdate by remember { mutableStateOf<UpdateManifest?>(null) }

            // Do not depend on WorkManager/notification delivery for the visible update prompt.
            // Every app launch checks GitHub directly and surfaces an in-app dialog immediately.
            LaunchedEffect(Unit) {
                availableUpdate = runCatching {
                    withContext(Dispatchers.IO) {
                        GitHubUpdateClient().fetchLatestManifest()
                            ?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
                    }
                }.getOrNull()
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

                val update = availableUpdate
                if (update != null) {
                    AlertDialog(
                        onDismissRequest = { availableUpdate = null },
                        title = { Text("تحديث جديد لـ Arab VPN") },
                        text = {
                            Text(
                                "الإصدار ${update.versionName} متاح. اضغط تحديث الآن لتنزيل فرق التحديث فقط عندما يكون متوفراً، ثم تثبيته فوق النسخة الحالية."
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
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
                            TextButton(onClick = { availableUpdate = null }) {
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
        runCatching { vpnViewModel.retryLocationSyncIfNeeded() }
    }
}
