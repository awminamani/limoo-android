# Limoo (Android, Xray-based)

Kotlin + Jetpack Compose + `VpnService`. v0.2 UI/UX redesign below was written without an Android SDK, so it has **not been compiled** - expect a few build errors on the first CI run (the v0.1 core path was compiled and fixed by the maintainer).

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

## v0.2 - Nothing-style redesign
**Look:** pure black / paper-white surfaces, hairline borders, pill shapes, mono caps labels, one accent (red by default; lime or mono in Settings). Status and numbers use an in-app **dot-matrix font** drawn on a Canvas (no font files). The connect control is a ring of dots: dim = off, comet = connecting, full = connected, red = error. Haptic ticks on toggles.

**Home:** one-tap ring, uptime, current server card (tap to switch from a searchable sheet; switching while connected reconnects), live speed and session traffic, real-delay test, subscription data/expiry bar, quick tiles (auto-best, route preset, ad block, fragment) that tell you when a reconnect is needed.

**Servers (much easier management):**
- search, filter chips (all / favorites / each group or subscription), sort (manual, latency, name, recently used)
- grouped sections that collapse; favorites pinned first
- swipe right = favorite, swipe left = delete with **Undo**; long-press = multi-select (favorite, ping, share, group, delete)
- per-row latency as a 5-dot meter, ping selected/visible/all, "select best"
- tools: remove duplicates, remove unreachable, delete all (undoable)
- **subscriptions as first-class objects:** name, last update, data usage and expiry from `subscription-userinfo`, per-sub auto-update, update/edit/delete (keep or remove servers). Updates merge by server identity so selection, favorites and pings survive.

**Importing:**
- **Clipboard detection:** when Limoo comes to the front and the clipboard holds a config, a banner offers one-tap import (never automatic; plain URLs are ignored; can be turned off)
- one **Add** sheet: paste from clipboard (shows what was detected), scan QR, file, subscription link, manual entry
- every import shows a **preview**: pick which servers, spot "already added", set a group, see note/expiry, unlock password-protected files, optionally restore settings from a backup
- "Fill from clipboard link" in the server editor

**Sharing/backup:** share with title, note, optional password and expiry (never / 1 / 7 / 30 days) as file or link; QR as standard or `.limoo` link; full backup (servers + subscriptions + settings) and restore through the same preview.

**Settings:** category pages instead of one long scroll; option sheets instead of dropdowns; privacy mode (masks server addresses), auto-connect on app start, haptics, accent and theme.

## Fixes applied after the first CI run
- `initCoreEnv` needs an XUDP base key that is 32 bytes encoded as **unpadded base64url** (43 chars) - Xray runs
  it through `base64.RawURLEncoding.DecodeString` and rejects anything else. `ANDROID_ID` was passed, which made
  every connect fail with `xray.xudp.basekey: invalid value (BaseKey must be 32 bytes)`. A stable random 32-byte
  key is now generated once per install and persisted (`core/CoreEngine.kt`).
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
