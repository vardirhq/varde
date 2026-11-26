import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "no.vardir.varde"
    compileSdk = 35
    defaultConfig {
        applicationId = "no.vardir.varde"
        minSdk = 26
        targetSdk = 35
        // CI builds count up, so each APK installs as an update of the previous one.
        val build = providers.environmentVariable("GITHUB_RUN_NUMBER").orElse("1").get().toInt()
        // Release builds pass their own, always-increasing code; run numbers restart in each workflow.
        versionCode = providers.environmentVariable("VARDE_VERSION_CODE").orNull?.toInt() ?: build
        // A tagged release (v1.2.3) is named after its tag. Other builds are named after the latest release and their
        // CI run (0.2.0-dev.57), or 0.1.<run> before the first release. Android only shows the name; versionCode orders updates.
        val tag = providers.environmentVariable("GITHUB_REF_NAME").orElse("").get()
        val tagged = providers.environmentVariable("GITHUB_REF_TYPE").orElse("").get() == "tag" && tag.matches(Regex("v?\\d+\\.\\d+\\.\\d+.*"))
        val base = providers.environmentVariable("VARDE_BASE_VERSION").orElse("").get().removePrefix("v")
        versionName = when {
            tagged -> tag.removePrefix("v")
            base.matches(Regex("\\d+\\.\\d+\\.\\d+")) -> "$base-dev.$build"
            else -> "0.1.$build"
        }
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
            output.writeBytes(Base64.getMimeDecoder().decode(encoded.asFile.readText()))
        }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = stableDebugKeystore.get().asFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The release key never lives in the repository. CI decodes it from secrets into a temporary file;
        // without it, release builds are left unsigned and can't be installed.
        val releaseStore = providers.environmentVariable("VARDE_RELEASE_KEYSTORE").orNull
        if (releaseStore != null) create("release") {
            storeFile = file(releaseStore)
            storePassword = providers.environmentVariable("VARDE_RELEASE_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("VARDE_RELEASE_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("VARDE_RELEASE_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    tasks.matching { it.name.startsWith("validateSigning") }.configureEach { dependsOn(prepareDebugKeystore) }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // On-device assistance (optional, off by default). Newer AICore versions (e.g. on Galaxy S26) need a recent client.
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta5")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.commonmark:commonmark:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.22.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
