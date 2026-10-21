// Pure data contracts (User, Role, SessionState...). No Android/runtime dependencies by design.
plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.college.core.model"
    compileSdk = 34
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
// No dependencies: :core:model must stay dependency-free per the module rule
