// Shared HTTP/REST infrastructure (Retrofit + OkHttp) backed by the data contracts.
plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.college.core.network"
    compileSdk = 34
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // R6 (review P0): BuildConfig.DEBUG gates OkHttp header logging — tokens
    // must never reach logcat on release devices.
    buildFeatures { buildConfig = true }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core:model"))

    // Reactive session state (StateFlow)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // HTTP client and JSON serialization (okhttp3 explicitly for the Interceptor contract)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Encrypted token storage
    implementation("androidx.security:security-crypto:1.1.0")
}
