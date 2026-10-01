plugins {
    id("com.android.application"); id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization"); id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.limoo"; compileSdk = 34
    defaultConfig {
        applicationId = "app.limoo"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
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
        java.net.URL(coreUrl).openStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        check(tmp.length() > 1_000_000) { "Downloaded core is too small - set limoo.coreUrl in gradle.properties" }
        tmp.renameTo(coreAar)
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn("fetchXrayCore") }
