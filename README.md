# Limoo (Android, Xray-based)

Kotlin + Jetpack Compose + `VpnService`. **Not yet compiled or run** - written in a sandbox with no SDK or network access, so expect to fix a few build errors on first open.

## Build
1. Open in Android Studio (AGP 8.5 / Kotlin 2.0 / JDK 17).
2. `./gradlew fetchXrayCore` (runs automatically before build) downloads `libv2ray.aar` from `limoo.coreUrl` in `gradle.properties` into `app/libs/`. Offline? Copy the AAR there yourself. Pin a release tag in the URL for reproducible builds.
3. Run. On first open the app downloads `geoip.dat` / `geosite.dat` automatically.

## Features
- **Protocols/transports:** VLESS, VMess, Trojan, Shadowsocks; TCP, WS, gRPC, XHTTP, HTTPUpgrade; TLS, REALITY
- **Import:** `.limoo` file, `limoo://` link, clipboard, QR scan, `vless/vmess/trojan/ss` links, subscription URLs (refreshed on open)
- **Export/share:** `.limoo` file or link (optional AES-256-GCM password), standard-link QR, `.limoo`-link QR, copy link
- **Servers:** manual add / per-server editor, TCP latency for all, auto-select lowest latency (also optional on connect), real delay test through the running core
- **Routing:** presets (Iran/China/Russia bypass), ad block, always proxy/direct/block lists, raw Xray rules
- **Routing data:** auto-download on first open, weekly refresh, mirror fallback (GitHub then jsDelivr), SHA-256 check when published, choice of Chocolate4U (has Iran lists) or Loyalsoldier, manual update
- **Connection:** VPN or proxy-only mode, MTU, IPv6, DNS, ports, LAN sharing, TLS fragment, mux, sniffing, log level
- **Per-app proxy:** searchable app picker with icons, allow or deny mode
- **Extras:** live speed and session traffic, Quick Settings tile, disconnect action in the notification, Always-on VPN restart, light/dark/dynamic theme, RTL layout flag, launcher icon

## Fixes applied after the first CI run
- `initCoreEnv` needs a **32-byte** XUDP base key (64 hex chars). `ANDROID_ID` was passed and made every
  connect fail with `xray.xudp.basekey: invalid value (BaseKey must be 32 bytes)`. A stable random 32-byte key
  is now generated once per install and persisted (`core/CoreEngine.kt`).
- The release APK was unsigned, so Android refused to install it. `app/build.gradle.kts` now signs release with
  the debug keystore so the CI artifact is installable (replace with your own keystore for real releases).

## Known gaps
- Core binding is reflection-based against the recent `CoreController` API (`Libv2ray.newCoreController`, `startLoop(config, tunFd)`) with Xray's own `tun` inbound. I could not inspect the AAR, so if your AAR version differs, adjust `core/CoreEngine.kt`; errors show on the Home screen.
- Traffic numbers come from Android's per-app counters (approximate), not Xray stats.
- Only English strings (no translations yet). Latency test is TCP-connect; real delay needs an active connection.
- No unit tests; parser/format code is the best first place to add them.

## .limoo spec v1
UTF-8 JSON. Plain: `{"limoo":1,"name","note","expires"(epoch s, 0=never),"servers":[...],"subscriptions":[...]}`.
Protected: `{"limoo":1,"encrypted":true,"kdf":"pbkdf2-sha256","iter":200000,"salt":b64,"iv":b64,"data":b64}`; data = AES-256-GCM(plain JSON), key = PBKDF2-HMAC-SHA256(password, salt, iter).
Link form: `limoo://import?d=` + base64url(gzip(file text)). MIME `application/x-limoo`.
