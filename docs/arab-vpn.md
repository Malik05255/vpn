# Arab VPN MVP

This branch repurposes the Android launcher experience into a simple WireGuard VPN client for three countries:

- Egypt (`EG`)
- Jordan (`JO`)
- Morocco (`MA`)

## Runtime flow

1. User selects a country.
2. The app requires a valid WireGuard `.conf` profile for that country.
3. Android displays the standard VPN consent prompt on first use.
4. The app starts the WireGuard userspace backend.
5. After the tunnel is up, the app checks the public egress IP and country.
6. If the detected country does not match the selected country, the app disconnects instead of reporting a false success.

## Profile security

WireGuard profiles contain client private keys and **must not be committed to GitHub**.

The app imports each profile at runtime and stores it under app-private storage:

```text
/files/vpn-profiles/egypt.conf
/files/vpn-profiles/jordan.conf
/files/vpn-profiles/morocco.conf
```

Android backup is disabled for the application so these files are not included in normal app backups.

## Required profile properties

A profile must contain at least:

```ini
[Interface]
PrivateKey = <client-private-key>
Address = <client-address>
DNS = <dns-inside-the-tunnel>

[Peer]
PublicKey = <server-public-key>
Endpoint = <country-server-host-or-ip>:51820
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
```

The MVP deliberately requires both `0.0.0.0/0` and `::/0` so IPv4 and IPv6 are captured by the VPN and an IPv6 bypass is not silently accepted.

## What still requires infrastructure

The Android client cannot create an Egyptian, Jordanian, or Moroccan public IP by itself. A real WireGuard endpoint with an egress IP in each country is required. For each country, provision a server and issue a client profile whose `Endpoint` points to that server.

Do not hard-code server private keys or client private keys in the repository.

## Build

```bash
./gradlew assembleDebug
```

The app uses `com.wireguard.android:tunnel` as its userspace WireGuard backend.
