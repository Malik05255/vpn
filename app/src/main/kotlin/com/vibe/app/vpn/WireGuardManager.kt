package com.vibe.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WireGuardManager(context: Context) {
    private val appContext = context.applicationContext
    private val backend by lazy { GoBackend(appContext) }
    private var activeTunnel: CountryTunnel? = null

    fun preparePermissionIntent(): Intent? = VpnService.prepare(appContext)

    suspend fun connect(country: VpnCountry, config: Config): Tunnel.State = withContext(Dispatchers.IO) {
        val existing = activeTunnel
        if (existing != null && existing.country != country) {
            backend.setState(existing, Tunnel.State.DOWN, null)
        }

        val tunnel = if (existing?.country == country) existing else CountryTunnel(country)
        val state = backend.setState(tunnel, Tunnel.State.UP, config)
        activeTunnel = tunnel
        state
    }

    suspend fun disconnect(): Tunnel.State = withContext(Dispatchers.IO) {
        val tunnel = activeTunnel ?: return@withContext Tunnel.State.DOWN
        val state = backend.setState(tunnel, Tunnel.State.DOWN, null)
        activeTunnel = null
        state
    }

    suspend fun currentState(): Tunnel.State = withContext(Dispatchers.IO) {
        val tunnel = activeTunnel ?: return@withContext Tunnel.State.DOWN
        backend.getState(tunnel)
    }

    private class CountryTunnel(val country: VpnCountry) : Tunnel {
        @Volatile
        var state: Tunnel.State = Tunnel.State.DOWN
            private set

        override fun getName(): String = country.tunnelName

        override fun onStateChange(newState: Tunnel.State) {
            state = newState
        }
    }
}
