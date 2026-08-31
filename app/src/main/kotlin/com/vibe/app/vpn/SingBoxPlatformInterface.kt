package com.vibe.app.vpn

import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.OsConstants
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

/** Android implementation used by the embedded sing-box core. */
internal class SingBoxPlatformInterface(
    private val service: VpnService,
) : PlatformInterface {
    private val connectivityManager = service.getSystemService(ConnectivityManager::class.java)
    private var tunFileDescriptor: ParcelFileDescriptor? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null

    fun closeTun() {
        runCatching { tunFileDescriptor?.close() }
        tunFileDescriptor = null
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        check(service.protect(fd)) { "android: failed to protect sing-box outbound socket" }
    }

    override fun openTun(options: TunOptions): Int {
        check(VpnService.prepare(service) == null) { "android: missing VPN permission" }
        val ipv4 = options.inet4Address.toRoutePrefixList()
        val ipv6 = options.inet6Address.toRoutePrefixList()
        val builder = service.Builder()
            .setSession("Arab VPN")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        (ipv4 + ipv6).forEach { prefix -> builder.addAddress(prefix.address(), prefix.prefix()) }

        if (options.autoRoute) {
            if (options.dnsMode.value != io.nekohasekai.libbox.Libbox.DNSModeDisabled) {
                options.dnsServerAddress.toStringList().forEach(builder::addDnsServer)
            }
            builder.applyRoutes(options, ipv4.isNotEmpty(), ipv6.isNotEmpty())
        }

        closeTun()
        val descriptor = builder.establish()
            ?: error("android: VPN tunnel could not be established")
        tunFileDescriptor = descriptor
        return descriptor.fd
    }

    private fun VpnService.Builder.applyRoutes(
        options: TunOptions,
        hasIpv4: Boolean,
        hasIpv6: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val v4Routes = options.inet4RouteAddress.toRoutePrefixList()
            val v6Routes = options.inet6RouteAddress.toRoutePrefixList()
            if (v4Routes.isEmpty() && hasIpv4) addRoute("0.0.0.0", 0)
            else v4Routes.forEach { addRoute(IpPrefix(InetAddress.getByName(it.address()), it.prefix())) }
            if (v6Routes.isEmpty() && hasIpv6) addRoute("::", 0)
            else v6Routes.forEach { addRoute(IpPrefix(InetAddress.getByName(it.address()), it.prefix())) }

            options.inet4RouteExcludeAddress.toRoutePrefixList().forEach {
                excludeRoute(IpPrefix(InetAddress.getByName(it.address()), it.prefix()))
            }
            options.inet6RouteExcludeAddress.toRoutePrefixList().forEach {
                excludeRoute(IpPrefix(InetAddress.getByName(it.address()), it.prefix()))
            }
        } else {
            options.inet4RouteRange.toRoutePrefixList().forEach { addRoute(it.address(), it.prefix()) }
            options.inet6RouteRange.toRoutePrefixList().forEach { addRoute(it.address(), it.prefix()) }
        }
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "android: connection owner lookup requires Android 10"
        }
        val uid = connectivityManager.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        check(uid != Process.INVALID_UID) { "android: connection owner not found" }
        val packages = service.packageManager.getPackagesForUid(uid).orEmpty().toList()
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull().orEmpty()
            setAndroidPackageNames(packages.toLibboxStringIterator())
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        closeDefaultInterfaceMonitor(listener)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateDefaultInterface(listener, network)
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                updateDefaultInterface(listener, network)
            override fun onLost(network: Network) = updateDefaultInterface(listener, connectivityManager.activeNetwork)
        }
        defaultNetworkCallback = callback
        connectivityManager.registerDefaultNetworkCallback(callback)
        updateDefaultInterface(listener, connectivityManager.activeNetwork)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        defaultNetworkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        defaultNetworkCallback = null
    }

    private fun updateDefaultInterface(listener: InterfaceUpdateListener, network: Network?) {
        val interfaceName = network
            ?.let(connectivityManager::getLinkProperties)
            ?.interfaceName
            .orEmpty()
        val index = runCatching { NetworkInterface.getByName(interfaceName)?.index ?: -1 }.getOrDefault(-1)
        listener.updateDefaultInterface(interfaceName, index, false, false)
    }

    @Suppress("DEPRECATION")
    override fun getInterfaces(): NetworkInterfaceIterator {
        val androidNetworks = connectivityManager.allNetworks.mapNotNull { network ->
            val properties = connectivityManager.getLinkProperties(network) ?: return@mapNotNull null
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            properties.interfaceName.orEmpty() to (properties to capabilities)
        }.toMap()

        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { networkInterface ->
            val androidNetwork = androidNetworks[networkInterface.name]
            LibboxNetworkInterface().apply {
                index = networkInterface.index
                name = networkInterface.name
                mtu = runCatching { networkInterface.mtu }.getOrDefault(0)
                addresses = networkInterface.interfaceAddresses
                    .map { address -> address.toLibboxPrefix() }
                    .toLibboxStringIterator()
                flags = networkInterface.toFlags()
                type = androidNetwork?.second?.toLibboxInterfaceType()
                    ?: io.nekohasekai.libbox.Libbox.InterfaceTypeOther
                dnsServer = androidNetwork?.first?.dnsServers.orEmpty()
                    .mapNotNull { address -> address.hostAddress }
                    .toLibboxStringIterator()
                metered = androidNetwork?.second
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    ?.not()
                    ?: false
            }
        }
        return LibboxNetworkInterfaceIterator(interfaces.iterator())
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() = Unit
    override fun localDNSTransport(): LocalDNSTransport = AndroidLocalDnsTransport(connectivityManager)
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell(): Unit = unsupported("platform shell")
    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession = unsupported("platform shell")
    override fun readSystemSSHHostKey(): String = unsupported("system SSH host key")
    override fun lookupSFTPServer(): String = unsupported("SFTP server")
    override fun lookupUser(username: String?): PlatformUser = unsupported("platform user")
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession = unsupported("platform bridge")
    override fun registerMyInterface(name: String?) = Unit

    @Suppress("DEPRECATION")
    override fun readWIFIState(): WIFIState? {
        val wifi = service.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo ?: return null
        val ssid = wifi.ssid.orEmpty().removeSurrounding("\"")
            .takeUnless { it == "<unknown ssid>" }.orEmpty()
        return WIFIState(ssid, wifi.bssid.orEmpty())
    }

    override fun tailscaleHostname(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
    override fun sendNotification(notification: Notification) = Unit
    override fun cancelNotification(identifier: String, typeID: Int) = Unit

    private fun NetworkInterface.toFlags(): Int {
        var value = 0
        if (isUp) value = value or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
        if (isLoopback) value = value or OsConstants.IFF_LOOPBACK
        if (isPointToPoint) value = value or OsConstants.IFF_POINTOPOINT
        if (supportsMulticast()) value = value or OsConstants.IFF_MULTICAST
        return value
    }

    private fun InterfaceAddress.toLibboxPrefix(): String {
        val host = if (address is Inet6Address) {
            Inet6Address.getByAddress(address.address).hostAddress
        } else {
            address.hostAddress
        }
        return "$host/$networkPrefixLength"
    }

    private fun NetworkCapabilities.toLibboxInterfaceType(): Int = when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> io.nekohasekai.libbox.Libbox.InterfaceTypeWIFI
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> io.nekohasekai.libbox.Libbox.InterfaceTypeCellular
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> io.nekohasekai.libbox.Libbox.InterfaceTypeEthernet
        else -> io.nekohasekai.libbox.Libbox.InterfaceTypeOther
    }

    private fun <T> unsupported(feature: String): T =
        throw UnsupportedOperationException("android: $feature is not supported")
}

private class LibboxNetworkInterfaceIterator(
    private val values: Iterator<LibboxNetworkInterface>,
) : NetworkInterfaceIterator {
    override fun hasNext(): Boolean = values.hasNext()
    override fun next(): LibboxNetworkInterface = values.next()
}

private class LibboxStringIterator(
    private val values: Iterator<String>,
    private val size: Int,
) : StringIterator {
    override fun len(): Int = size
    override fun hasNext(): Boolean = values.hasNext()
    override fun next(): String = values.next()
}

private fun List<String>.toLibboxStringIterator(): StringIterator =
    LibboxStringIterator(iterator(), size)

private fun io.nekohasekai.libbox.RoutePrefixIterator.toRoutePrefixList(): List<io.nekohasekai.libbox.RoutePrefix> =
    buildList {
        while (this@toRoutePrefixList.hasNext()) add(this@toRoutePrefixList.next())
    }

private fun StringIterator.toStringList(): List<String> = buildList {
    while (this@toStringList.hasNext()) add(this@toStringList.next())
}

@Suppress("DEPRECATION")
private class AndroidLocalDnsTransport(
    private val connectivityManager: ConnectivityManager,
) : LocalDNSTransport {
    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun exchange(context: ExchangeContext, message: ByteArray) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "android: raw DNS requires Android 10" }
        exchangeRaw(context, message)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun exchangeRaw(context: ExchangeContext, message: ByteArray) = runBlocking {
        val network = connectivityManager.activeNetwork ?: error("android: default network unavailable")
        suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            context.onCancel(cancellation::cancel)
            DnsResolver.getInstance().rawQuery(
                network,
                message,
                DnsResolver.FLAG_NO_RETRY,
                Dispatchers.IO.asExecutor(),
                cancellation,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        if (rcode == 0) context.rawSuccess(answer) else context.errorCode(rcode)
                        continuation.resume(Unit)
                    }
                    override fun onError(error: DnsResolver.DnsException) {
                        val cause = error.cause
                        if (cause is ErrnoException) {
                            context.errnoCode(cause.errno)
                            continuation.resume(Unit)
                        } else continuation.resumeWithException(error)
                    }
                },
            )
        }
    }

    override fun lookup(context: ExchangeContext, network: String, domain: String) = runBlocking {
        val defaultNetwork = connectivityManager.activeNetwork ?: error("android: default network unavailable")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            suspendCancellableCoroutine { continuation ->
                val cancellation = CancellationSignal()
                context.onCancel(cancellation::cancel)
                val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
                    override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                        if (rcode == 0) {
                            context.success(answer.mapNotNull { address -> address.hostAddress }.joinToString("\n"))
                        } else context.errorCode(rcode)
                        continuation.resume(Unit)
                    }
                    override fun onError(error: DnsResolver.DnsException) {
                        val cause = error.cause
                        if (cause is ErrnoException) {
                            context.errnoCode(cause.errno)
                            continuation.resume(Unit)
                        } else continuation.resumeWithException(error)
                    }
                }
                val queryType = when {
                    network.endsWith("4") -> DnsResolver.TYPE_A
                    network.endsWith("6") -> DnsResolver.TYPE_AAAA
                    else -> null
                }
                if (queryType == null) {
                    DnsResolver.getInstance().query(
                        defaultNetwork, domain, DnsResolver.FLAG_NO_RETRY,
                        Dispatchers.IO.asExecutor(), cancellation, callback,
                    )
                } else {
                    DnsResolver.getInstance().query(
                        defaultNetwork, domain, queryType, DnsResolver.FLAG_NO_RETRY,
                        Dispatchers.IO.asExecutor(), cancellation, callback,
                    )
                }
            }
        } else {
            val answer = try {
                defaultNetwork.getAllByName(domain)
            } catch (_: UnknownHostException) {
                context.errorCode(3)
                return@runBlocking
            }
            context.success(answer.mapNotNull { address -> address.hostAddress }.joinToString("\n"))
        }
    }
}
