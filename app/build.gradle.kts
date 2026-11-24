plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "no.vardir.varde"
    compileSdk = 35
    defaultConfig {
        applicationId = "no.vardir.varde"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        val clientId = providers.environmentVariable("GH_CLIENT_ID").orElse("").get()
        require(clientId.matches(Regex("[A-Za-z0-9_]*"))) { "Invalid GitHub client ID" }
        buildConfigField("String", "GH_CLIENT_ID", "\"$clientId\"")
    }
    // Public debug-only key: stable across CI runners, never use for release signing.
    val stableDebugKeystore = layout.buildDirectory.file("signing/varde-debug.keystore")
    val prepareDebugKeystore = tasks.register("prepareDebugKeystore") {
        val encoded = layout.projectDirectory.file("debug-keystore.b64")
        inputs.file(encoded)
        outputs.file(stableDebugKeystore)
        doLast {
            val output = stableDebugKeystore.get().asFile
            output.parentFile.mkdirs()
            output.writeBytes(java.util.Base64.getMimeDecoder().decode(encoded.asFile.readText()))
        }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = stableDebugKeystore.get().asFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    tasks.matching { it.name.startsWith("validateSigning") }.configureEach { dependsOn(prepareDebugKeystore) }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
