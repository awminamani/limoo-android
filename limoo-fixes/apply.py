#!/usr/bin/env python3
"""Limoo v0.3 kit. Run from the limoo-android repo root:  python3 path/to/limoo-fixes/apply.py
1) copies new / fully rewritten files, 2) makes small anchored edits to files it did not rewrite.
Every edit prints OK or SKIP so nothing fails silently. Safe to re-run (edits are idempotent)."""
import re, shutil, sys, pathlib

KIT = pathlib.Path(__file__).resolve().parent
ROOT = pathlib.Path.cwd()
if not (ROOT / "app/src/main/java/app/limoo").is_dir():
    sys.exit("Run this from the limoo-android repo root.")
problems = []

for src in sorted((KIT / "files").rglob("*")):
    if src.is_file():
        dst = ROOT / src.relative_to(KIT / "files")
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dst); print("wrote", dst.relative_to(ROOT))

def edit(path, desc, fn, done_marker=None):
    p = ROOT / path
    if not p.exists(): problems.append(f"{path}: missing"); print("SKIP", path, "- file missing"); return
    s = p.read_text(encoding="utf-8")
    if done_marker and done_marker in s: print("done", path, "-", desc); return
    n = fn(s)
    if n == s: problems.append(f"{path}: {desc}"); print("SKIP", path, "-", desc, "(anchor not found)")
    else: p.write_text(n, encoding="utf-8"); print("OK  ", path, "-", desc)

def after_line(anchor, add):
    """Insert `add` (list of lines) after the first line containing `anchor`, matching its indentation."""
    def f(s):
        m = re.search(r"^([ \t]*)[^\n]*" + re.escape(anchor) + r"[^\n]*\n", s, re.M)
        if not m: return s
        ind = m.group(1)
        return s[:m.end()] + "".join(ind + a + "\n" for a in add) + s[m.end():]
    return f

def before_line(anchor, add):
    def f(s):
        m = re.search(r"^([ \t]*)[^\n]*" + re.escape(anchor), s, re.M)
        if not m: return s
        ind = m.group(1)
        return s[:m.start()] + "".join(ind + a + "\n" for a in add) + s[m.start():]
    return f

J = "app/src/main/java/app/limoo/"

# ---------- model ----------
edit(J + "model/Models.kt", "new settings: killSwitch, pingTimeoutMs",
     lambda s: s.replace("val realPing: Boolean = true,", "val realPing: Boolean = true,\n    // kill switch: keep the tunnel up and drop traffic if the core fails\n    val killSwitch: Boolean = false,\n    // latency test timeout (TCP connect; the in-tunnel test uses 2x)\n    val pingTimeoutMs: Int = 4000,", 1),
     done_marker="killSwitch")

# ---------- Xray config: outbound counters ----------
edit(J + "core/XrayConfigBuilder.kt", "enable outbound stats policy",
     lambda s: s.replace('put("statsInboundUplink", true); put("statsInboundDownlink", true)',
        'put("statsInboundUplink", true); put("statsInboundDownlink", true)\n'
        '                put("statsOutboundUplink", true); put("statsOutboundDownlink", true)'),
     done_marker="statsOutboundUplink")

# ---------- Store: encrypted prefs, ping timeout, widget sync ----------
edit(J + "LimooApp.kt", "encrypt servers + subscriptions at rest",
     lambda s: s.replace('private val sp = ctx.getSharedPreferences("limoo", Context.MODE_PRIVATE)',
        'private val sp = app.limoo.core.SecurePrefs.wrap(ctx.getSharedPreferences("limoo", Context.MODE_PRIVATE), setOf("servers", "subs"))'),
     done_marker="SecurePrefs.wrap")
edit(J + "LimooApp.kt", "ping timeout setting",
     lambda s: s.replace("Latency.tcp(s)", "Latency.tcp(s, st.pingTimeoutMs)"), done_marker="st.pingTimeoutMs")
edit(J + "LimooApp.kt", "start widget sync", after_line("store = Store(this)", ["app.limoo.widget.WidgetSync.start(this)"]),
     done_marker="WidgetSync.start")

# ---------- Home ----------
NEW_TRAFFIC = '''/**
 * Speed and session totals, straight from LimooVpnService. The service is the only reader of the core's
 * counters (they reset on read) and computes the rates, so Home, widget and notification always agree.
 */
@Composable
fun rememberTraffic(active: Boolean): Pair<Traffic, Boolean> {
    val live by LimooVpnService.traffic.collectAsState()
    val exact by LimooVpnService.trafficExact.collectAsState()
    val c = if (active) live else null
    val t = if (c == null) Traffic() else Traffic(down = c.downRate, up = c.upRate, totalDown = c.down, totalUp = c.up)
    return t to (c != null && exact)
}

'''
H = J + "ui/Home.kt"
edit(H, "rememberTraffic -> service flow",
     lambda s: re.sub(r"/\*\*\s*Speed and session totals.*?(?=/\*\*\s*\n\s*\*\s*The hero)", lambda m: NEW_TRAFFIC, s, count=1, flags=re.S),
     done_marker="LimooVpnService.trafficExact")
edit(H, "fmtBytes Locale.US",
     lambda s: s.replace('(if (i == 0) "%.0f" else "%.1f").format(v) + " " + u[i]',
                         'String.format(java.util.Locale.US, if (i == 0) "%.0f" else "%.1f", v) + " " + u[i]'), done_marker='Locale.US, if (i == 0)')
edit(H, "fmtUptime Locale.US",
     lambda s: s.replace('"%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)',
                         'String.format(java.util.Locale.US, "%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)'), done_marker='Locale.US, "%02d')
edit(H, "in-tunnel test uses the timeout setting",
     lambda s: s.replace("Latency.viaProxy(st.socksPort, st.testUrl)", "Latency.viaProxy(st.socksPort, st.testUrl, st.pingTimeoutMs * 2)"),
     done_marker="st.pingTimeoutMs * 2")
edit(H, "usage card on Home",
     before_line("// ---- subscription allowance ----", ["Spacer(Modifier.height(Space.compact))", "UsageCard(sel.id)", ""]),
     done_marker="UsageCard(sel.id)")

# ---------- Settings ----------
S = J + "ui/Settings.kt"
edit(S, "kill switch rows",
     before_line('ChoiceRow("Mode", listOf("vpn", "proxy")', [
        "val sysCtx = LocalContext.current",
        'ToggleRow("Kill switch", st.killSwitch, "BLOCK TRAFFIC IF THE CONNECTION DROPS") { v -> upd { it.copy(killSwitch = v) } }; NDivider()',
        'NRow("System kill switch", "ALWAYS-ON VPN + BLOCK CONNECTIONS WITHOUT VPN", { runCatching { sysCtx.startActivity(android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("›", style = NType.body, color = n.muted) }; NDivider()',
     ]), done_marker="killSwitch")
edit(S, "ping timeout row",
     after_line('FieldRow("Test URL", st.testUrl)', ['NumberRow("Ping timeout (ms)", st.pingTimeoutMs) { v -> upd { it.copy(pingTimeoutMs = v.coerceIn(500, 20000)) } }']),
     done_marker="pingTimeoutMs")
edit(S, "real version number",
     lambda s: s.replace('NRow("Limoo", "version 0.3.0") {}',
        'NRow("Limoo", "version " + LocalContext.current.let { c -> runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?" }) {}'),
     done_marker='"version " + LocalContext')

# ---------- theme: "amber" was offered in Settings but never implemented ----------
edit(J + "ui/Nothing.kt", "amber accent",
     after_line('"blue" -> if (dark) Color(0xFF5B8DEF) else Color(0xFF2C5FCC)', ['"amber" -> if (dark) Color(0xFFF5A623) else Color(0xFFB36B00)']),
     done_marker='"amber" ->')

# ---------- manifest: widget receiver ----------
WIDGET = '''        <receiver android:name=".widget.LimooWidgetReceiver" android:exported="true" android:label="Limoo">
            <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE" /></intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/limoo_widget_info" />
        </receiver>
'''
edit("app/src/main/AndroidManifest.xml", "register widget",
     lambda s: s.replace("</application>", WIDGET + "    </application>", 1), done_marker="LimooWidgetReceiver")

# ---------- Gradle ----------
G = "app/build.gradle.kts"
edit(G, "version 0.3.0", lambda s: s.replace('versionCode = 2; versionName = "0.2.0"', 'versionCode = 3; versionName = "0.3.0"'),
     done_marker='versionName = "0.3.0"')
SIGN = '''// Release signing: keystore.properties (local, git-ignored) or LIMOO_* env vars (CI). Falls back to the
    // debug key so a fresh clone still builds an installable APK.
    val ksProps = java.util.Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
    fun ks(k: String, env: String): String? = (ksProps.getProperty(k) ?: System.getenv(env))?.takeIf { it.isNotBlank() }
    signingConfigs {
        val store = ks("storeFile", "LIMOO_KEYSTORE")
        if (store != null) create("release") {
            storeFile = rootProject.file(store)
            storePassword = ks("storePassword", "LIMOO_KEYSTORE_PASSWORD")
            keyAlias = ks("keyAlias", "LIMOO_KEY_ALIAS")
            keyPassword = ks("keyPassword", "LIMOO_KEY_PASSWORD")
        }
    }'''
edit(G, "real release signing",
     lambda s: re.sub(r'signingConfigs \{ getByName\("debug"\) \}[^\n]*', lambda m: SIGN, s, count=1), done_marker="LIMOO_KEYSTORE")
edit(G, "release uses release key when present",
     lambda s: s.replace('signingConfig = signingConfigs.getByName("debug")', 'signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")'),
     done_marker='findByName("release")')
edit(G, "unit test options",
     lambda s: s.replace("buildFeatures { compose = true }", "buildFeatures { compose = true }\n    testOptions { unitTests.isIncludeAndroidResources = true }", 1),
     done_marker="testOptions")
edit(G, "glance + test deps", before_line("implementation(fileTree(", [
        'implementation("androidx.glance:glance-appwidget:1.1.0") // home-screen widget',
        'testImplementation("junit:junit:4.13.2")',
        'testImplementation("org.robolectric:robolectric:4.13")',
     ]), done_marker="glance-appwidget")
VERIFY = '''

// ---- Xray core: verify the AAR exposes the API CoreEngine calls by reflection ----
// Reflection means a wrong AAR compiles fine and only fails on a phone. Fail the BUILD instead.
tasks.register("verifyXrayCore") {
    group = "limoo"; description = "Checks libv2ray.aar has the CoreController API Limoo needs"
    dependsOn("fetchXrayCore")
    onlyIf { coreAar.exists() }
    doLast {
        fun entries(bytes: ByteArray): Map<String, ByteArray> {
            val out = HashMap<String, ByteArray>()
            java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
                while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) out[e.name] = z.readBytes() }
            }
            return out
        }
        val aar = entries(coreAar.readBytes())
        val jar = entries(aar["classes.jar"] ?: throw GradleException("libv2ray.aar has no classes.jar"))
        val lib = jar["libv2ray/Libv2ray.class"]?.toString(Charsets.ISO_8859_1) ?: throw GradleException("libv2ray.aar: no libv2ray.Libv2ray class")
        val ctl = jar["libv2ray/CoreController.class"]?.toString(Charsets.ISO_8859_1)
            ?: throw GradleException("libv2ray.aar is too old: no CoreController. Use a recent AndroidLibXrayLite release.")
        val need = mapOf("newCoreController" to lib, "initCoreEnv" to lib, "startLoop" to ctl, "stopLoop" to ctl)
        val missing = need.filter { (m, cls) -> !cls.contains(m) }.keys
        if (missing.isNotEmpty()) throw GradleException("libv2ray.aar is missing $missing - CoreEngine.kt would fail at runtime")
        if (!ctl.contains("queryAllOutboundTrafficStats") && !ctl.contains("queryStats"))
            logger.warn("libv2ray.aar has no traffic stats API: Limoo will fall back to Android's per-app counters")
        println("verifyXrayCore: OK")
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn("verifyXrayCore") }
'''
edit(G, "verifyXrayCore task", lambda s: s.rstrip("\n") + VERIFY, done_marker="verifyXrayCore")

# ---------- preflight: runnable anywhere (was hard-coded to /tmp/work and /opt/android-sdk) ----------
P = "tools/preflight.py"
def preflight(s):
    s = s.replace("import re, sys, glob, zipfile, subprocess", "import os, re, sys, glob, zipfile, subprocess", 1)
    s = s.replace('SDK = "/opt/android-sdk/platforms/android-34/android.jar"',
        'REPO = os.environ.get("LIMOO_REPO", os.getcwd())\n'
        'SDK = os.path.join(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "/opt/android-sdk")), "platforms/android-34/android.jar")', 1)
    s = s.replace('"/tmp/work/app/src/main/java/app/limoo"', 'os.path.join(REPO, "app/src/main/java/app/limoo")')
    s = s.replace('"/tmp/work"', "REPO")
    return s
edit(P, "portable paths", preflight, done_marker="LIMOO_REPO")

# ---------- CI ----------
C = ".github/workflows/android.yml"
edit(C, "preflight step", before_line("- name: Gradle cache", [
        "- name: Preflight",
        "  run: python3 tools/preflight.py",
        "",
     ]), done_marker="tools/preflight.py")
edit(C, "unit tests step", before_line("- name: Assemble release", [
        "- name: Unit tests",
        "  run: ./gradlew :app:testDebugUnitTest --no-daemon",
        "",
        "- name: Release keystore (optional, from secrets)",
        "  env:",
        "    KS_B64: ${{ secrets.LIMOO_KEYSTORE_B64 }}",
        "    KS_PASS: ${{ secrets.LIMOO_KEYSTORE_PASSWORD }}",
        "    KEY_ALIAS: ${{ secrets.LIMOO_KEY_ALIAS }}",
        "    KEY_PASS: ${{ secrets.LIMOO_KEY_PASSWORD }}",
        "  run: |",
        "    if [ -n \"$KS_B64\" ]; then",
        "      echo \"$KS_B64\" | base64 -d > release.jks",
        "      { echo LIMOO_KEYSTORE=release.jks; echo LIMOO_KEYSTORE_PASSWORD=$KS_PASS; echo LIMOO_KEY_ALIAS=$KEY_ALIAS; echo LIMOO_KEY_PASSWORD=$KEY_PASS; } >> $GITHUB_ENV",
        "    else echo 'No release keystore secret: release APK is signed with the debug key'; fi",
        "",
     ]), done_marker="testDebugUnitTest")

# ---------- git ignore ----------
edit(".gitignore", "ignore keystores", lambda s: s.rstrip("\n") + "\nkeystore.properties\n*.jks\n", done_marker="keystore.properties")

# ---------- docs ----------
D = "docs/UI-STYLE.md"
edit(D, "stats gotcha", lambda s: re.sub(r"- Traffic-stat keys are[^\n]*\n",
        "- `queryAllOutboundTrafficStats()` returns **plain text** `tag,uplink|downlink,value;` and **resets the\n"
        " counters on read**. Values are deltas; only `LimooVpnService` may poll it. Count `proxy` + `direct` only\n"
        " (`fragment` is a dialerProxy under `proxy`, counting it doubles bytes).\n"
        "- Format numbers for `DotReadout` with `Locale.US`: Persian/Arabic digits have no glyph and render blank.\n", s, count=1),
     done_marker="resets the")
edit(D, "policy gotcha", lambda s: s.replace("**must declare `stats` and a `policy`**", "**must declare `stats` and a `policy` with `statsOutboundUplink/Downlink`**"), done_marker='statsOutboundUplink/Downlink')
edit(D, "icon rule", lambda s: s.replace("`ic_launcher` (dot \"L\"), `ic_stat` (its status-bar reduction)",
        "`ic_launcher` (the traced Limoo \"L\" mark on OLED black, with a themed-icon monochrome layer), `ic_stat` (the same mark as a flat silhouette)"), done_marker='traced Limoo')
edit(D, "widget rule", after_line("Not every icon may be dots.", [
        "", "### Widget", "",
        "`widget/LimooWidget.kt` (Glance, so no hand-written RemoteViews): `widget_bg` = card surface + hairline,",
        "`widget_ring_on/off` = dashed oval echoing the connect ring. State label in mono micro caps; accent only for error/blocked.",
     ]), done_marker="### Widget")

R = "README.md"
edit(R, "traffic gap", lambda s: s.replace("- Traffic numbers come from Android's per-app counters (approximate), not Xray stats.",
        "- Traffic numbers come from Xray's own counters, with Android's per-app counters as automatic fallback."), done_marker='automatic fallback')
edit(R, "translation gap", lambda s: re.sub(r"- Only English strings \(no translations yet\)\.",
        "- Notification and widget are translated to Persian; the in-app screens are still English.", s, count=1), done_marker='translated to Persian')
V03 = '''## v0.3
- **Live stats fixed:** parses the core's real `tag,dir,value;` format, outbound counters enabled, rates on real elapsed time, Android-counter fallback, Persian-locale digits fixed.
- **Notification:** live down/up speed, session totals, uptime chronometer, Reconnect/Disconnect. Persian strings.
- **Kill switch:** in-app (tunnel held, traffic dropped if the core fails; watchdog detects a dead core) + shortcut to Android's Always-on/lockdown.
- **Data usage history:** per day (90 days) and per server, Home card with today / 7 / 30 days and a 7-day chart.
- **Home-screen widget** (Glance): connect toggle, state, server, live speed.
- **Security:** servers and subscriptions encrypted at rest (Android Keystore AES-256-GCM); real release signing via `keystore.properties` or CI secrets.
- **Build safety:** `verifyXrayCore` fails the build on an incompatible AAR; preflight + unit tests (Robolectric) run in CI.
- **Smaller:** instant disconnect, configurable ping timeout, "amber" accent implemented, version shown from the package, new traced app/notification icon.

'''
edit(R, "v0.3 changelog", before_line("## Known gaps", [l for l in V03.rstrip("\n").split("\n")] + [""]), done_marker="## v0.3")

# ---------- report other locale-sensitive formatting ----------
for f in (ROOT / "app/src/main/java/app/limoo").rglob("*.kt"):
    for i, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
        if re.search(r'"[^"]*%[0-9.]*[df][^"]*"\.format\(', line):
            print(f"note: locale-sensitive format at {f.relative_to(ROOT)}:{i} -> consider String.format(Locale.US, ...)")

print("\nAll edits applied." if not problems else "\nDone, but these need a manual look:\n  " + "\n  ".join(problems))
