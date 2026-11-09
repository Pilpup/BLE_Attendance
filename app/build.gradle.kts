plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.attendance.ble"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.attendance.ble"; minSdk = 24; targetSdk = 34; versionCode = 1; versionName = "1.0"
        // Must match :feature:attendance — filters AAR-provided JNI libs (e.g.
        // CameraX) to phone ABIs; emulator-only x86/x86_64 are excluded.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    // Keystore signing configuration for debug and release builds
    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (keystorePath != null && file(keystorePath).exists()) {
                // Fail fast on a half-configured signing environment: a missing
                // password variable used to surface as an obscure signing
                // failure late in the build instead of at configuration time.
                val storePass = System.getenv("KEYSTORE_PASSWORD")
                val alias = System.getenv("KEY_ALIAS")
                val keyPass = System.getenv("KEY_PASSWORD")
                require(!storePass.isNullOrBlank() && !alias.isNullOrBlank() && !keyPass.isNullOrBlank()) {
                    "Release keystore set via KEYSTORE_PATH but KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD are missing"
                }
                storeFile = file(keystorePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
                enableV1Signing = true
                enableV2Signing = true
            } else {
                // R6 (review P0): release artifacts built on CI must use the
                // real signing identity — silently falling back to the debug
                // keystore would publish a debug-signed artifact whose key is
                // also shared with every developer install. On CI, missing
                // keystore variables are a configuration error, not a fallback
                // condition. Local developers keep the debug fallback so an
                // unattended assembleRelease still builds without shipping
                // secrets through the repository.
                val ciValue = System.getenv("CI")
                val isCi = !ciValue.isNullOrBlank() && ciValue != "false"
                if (isCi) {
                    throw org.gradle.api.GradleException(
                        "Release build on CI requires signing configuration: set KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD environment variables."
                    )
                }
                initWith(getByName("debug"))
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
dependencies {
    // Multi-module graph: :app assembles everyone, features stay isolated from each other
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:ui"))
    implementation(project(":feature:attendance"))
    implementation(project(":feature:library"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui:1.6.0")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.compose.material:material-icons-extended:1.6.0")

    // HTTP client and JSON serialization
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
}

