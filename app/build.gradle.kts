import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The ClikaRT license credential embedded at build time, never committed — the official pattern of
// examples-0.6.4/android/hello/build.gradle.kts: local.properties (gitignored) names a file in
// clika.runtimeLicenseFile, and that file's first non-blank line rides BuildConfig.CLIKA_RT_LICENSE.
// A build with no file named embeds "" and the app has to be given the credential another way
// (FofApplication.loadRuntime). Never print this value.
val buildTimeLicense: String = run {
    val properties = Properties()
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use { properties.load(it) }
    val path = properties.getProperty("clika.runtimeLicenseFile")?.trim().orEmpty()
    if (path.isEmpty()) "" else file(path).takeIf { it.exists() }?.readLines()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}

android {
    namespace = "com.chloeyeo.friendorfoe"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.chloeyeo.friendorfoe"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The ClikaRT AAR ships arm64-v8a only; this also keeps every other library's other ABIs out of the APK.
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "CLIKA_RT_LICENSE", "\"" + buildTimeLicense.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        // HttpDetector (laptop `clikart-cli serve`) is a development/contingency path, never in a release build.
        buildConfigField("boolean", "ALLOW_HTTP_DETECTOR", "false")
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ALLOW_HTTP_DETECTOR", "true")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // FriendOrFoe: on-device detection (ClikaRT AAR, local Maven repo), camera, coroutines, HTTP, USB gadget link
    implementation(libs.clika.runtime)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.usb.serial)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
