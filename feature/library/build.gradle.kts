// Library feature (catalogue search, staff circulation, dev diagnostics). Never depends on :feature:attendance.
plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.college.feature.library"
    compileSdk = 34
    defaultConfig { minSdk = 24 }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:ui"))

    // Compose stack for this feature's screens & dashboard card
    implementation("androidx.compose.ui:ui:1.6.0")
    implementation("androidx.compose.foundation:foundation:1.6.0")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.compose.material:material-icons-extended:1.6.0")

    // ViewModel plumbing (composable viewModel() acquisition, viewModelScope)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Network stack for this feature's own Retrofit service. Mirrors :core:network
    // (whose artifacts are implementation-scoped, not exported).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.google.code.gson:gson:2.8.9")
}
