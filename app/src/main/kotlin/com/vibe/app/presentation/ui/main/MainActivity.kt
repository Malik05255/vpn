package com.vibe.app.presentation.ui.main

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibe.app.vpn.VpnScreen
import com.vibe.app.vpn.VpnViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val vpnViewModel: VpnViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

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

            MaterialTheme(
                colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
            ) {
                VpnScreen(
                    state = state,
                    onCountrySelected = vpnViewModel::selectCountry,
                    onConnect = {
                        val permissionIntent = vpnViewModel.prepareVpnPermission()
                        if (permissionIntent == null) {
                            vpnViewModel.connectAuthorized()
                        } else {
                            vpnPermissionLauncher.launch(permissionIntent)
                        }
                    },
                    onDisconnect = vpnViewModel::disconnect,
                    onOpenLocationSettings = {
                        runCatching { startActivity(vpnViewModel.mockLocationSettingsIntent()) }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vpnViewModel.retryLocationSyncIfNeeded()
    }
}
