import java.net.URL
import java.util.Properties
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application"); id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization"); id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.limoo"; compileSdk = 34
    defaultConfig {
        applicationId = "app.limoo"; minSdk = 26; targetSdk = 34; versionCode = 7; versionName = "0.7.0"
        // arm64-v8a ONLY. Every extra ABI ships another full copy of the Xray Go runtime, and the
        // release APK was ~120 MB - three runtimes. Restricting to the ABI that actually runs on the
        // phones this app targets brings it to roughly a third of that, which matters for anyone
        // downloading it over a mobile or metered connection. splits below can add the others back
        // for sideloading from a desktop, without inflating the APK a phone installs.
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // Release signing: keystore.properties (local, git-ignored) or LIMOO_* env vars (CI).
    //
    // If LIMOO_KEYSTORE is present but no "release" signingConfig can be built, that is a MISCONFIGURATION
    // and the build must stop. Falling through to the debug key is what produced five releases that Android
    // refused to update over each other: AGP mints a fresh debug key per runner, so every build got a
    // different certificate. The CI job also exports these variables, and Gradle reads signingConfigs during
    // CONFIGURATION, so if the secret arrives after the first ./gradlew call the config is silently absent.
    val ksProps = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
    fun ks(k: String, env: String): String? = (ksProps.getProperty(k) ?: System.getenv(env))?.takeIf { it.isNotBlank() }
    val ksStore = ks("storeFile", "LIMOO_KEYSTORE")
    signingConfigs {
        if (ksStore != null) create("release") {
            val file = rootProject.file(ksStore)
            if (!file.exists()) throw GradleException("LIMOO_KEYSTORE=$ksStore does not exist. The signing key is loaded during configuration, so it must be present BEFORE the first ./gradlew command - see the 'Release keystore' step in .github/workflows/android.yml")
            storeFile = file
            storePassword = ks("storePassword", "LIMOO_KEYSTORE_PASSWORD")
            keyAlias = ks("keyAlias", "LIMOO_KEY_ALIAS")
            keyPassword = ks("keyPassword", "LIMOO_KEY_PASSWORD")
            val alias = keyAlias
            if (alias.isNullOrBlank()) throw GradleException("No key alias: set LIMOO_KEY_ALIAS (or keyAlias in keystore.properties). Without it the APK cannot be signed.")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")   // QR scan (camera) + ZXing core for QR export
    implementation("androidx.glance:glance-appwidget:1.1.0") // home-screen widget
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))  // Xray core AAR (libv2ray.aar)
}

// ---- Xray core: downloaded at build time on your machine when app/libs/libv2ray.aar is missing ----
val coreAar = layout.projectDirectory.file("libs/libv2ray.aar").asFile
val coreUrl = providers.gradleProperty("limoo.coreUrl").get()
val coreSha256 = providers.gradleProperty("limoo.coreSha256").orNull ?: ""
tasks.register("fetchXrayCore") {
    group = "limoo"; description = "Downloads libv2ray.aar (AndroidLibXrayLite) into app/libs if missing"
    onlyIf { !coreAar.exists() }
    doLast {
        coreAar.parentFile.mkdirs()
        val tmp = File(coreAar.parentFile, "libv2ray.aar.tmp")
        URL(coreUrl).openStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        check(tmp.length() > 1_000_000) { "Downloaded core is too small - set limoo.coreUrl in gradle.properties" }
        // Verify the pinned digest. Without this a silently-substituted or truncated AAR builds fine and
        // fails only on a phone; with an unpinned URL this was the real risk, since every build could pull
        // a different core.
        val want = coreSha256.trim()
        if (want.isNotEmpty()) {
            // Named lambda parameter, not `it`: inside a Gradle doLast the implicit receiver makes a
            // bare `it` resolve against the task rather than the byte being formatted.
            val got = java.security.MessageDigest.getInstance("SHA-256")
                .digest(tmp.inputStream().use { s -> s.readBytes() })
                .joinToString("") { b -> "%02x".format(b) }
            if (!got.equals(want, ignoreCase = true)) {
                tmp.delete()
                throw GradleException("libv2ray.aar digest mismatch.\n  expected sha256 $want\n  actual   sha256 $got\nThe pinned core was replaced or the download was corrupted. Refusing to build.")
            }
            println("fetchXrayCore: sha256 verified ($got)")
        } else {
            logger.warn("limoo.coreSha256 is empty - the core is NOT verified. Pin it before shipping a release.")
        }
        tmp.renameTo(coreAar)
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn("fetchXrayCore") }

// ---- Xray core: verify the AAR exposes the API CoreEngine calls by reflection ----
// Reflection means a wrong AAR compiles fine and only fails on a phone. Fail the BUILD instead.
tasks.register("verifyXrayCore") {
    group = "limoo"; description = "Checks libv2ray.aar has the CoreController API Limoo needs"
    dependsOn("fetchXrayCore")
    onlyIf { coreAar.exists() }
    doLast {
        fun entries(bytes: ByteArray): Map<String, ByteArray> {
            val out = HashMap<String, ByteArray>()
            ZipInputStream(bytes.inputStream()).use { z ->
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
