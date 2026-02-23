plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.explorer.fileexplorer.core.storage"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core:model"))
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.documentfile)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.core)

    // Root access
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.libsu.nio)
}
