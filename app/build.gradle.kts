import java.net.URL

plugins {
    id("com.android.application"); id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization"); id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.limoo"; compileSdk = 34
    defaultConfig {
        applicationId = "app.limoo"; minSdk = 26; targetSdk = 34; versionCode = 3; versionName = "0.3.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    // Release signing: keystore.properties (local, git-ignored) or LIMOO_* env vars (CI). Falls back to the
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
tasks.register("fetchXrayCore") {
    group = "limoo"; description = "Downloads libv2ray.aar (AndroidLibXrayLite) into app/libs if missing"
    onlyIf { !coreAar.exists() }
    doLast {
        coreAar.parentFile.mkdirs()
        val tmp = File(coreAar.parentFile, "libv2ray.aar.tmp")
        URL(coreUrl).openStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        check(tmp.length() > 1_000_000) { "Downloaded core is too small - set limoo.coreUrl in gradle.properties" }
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
