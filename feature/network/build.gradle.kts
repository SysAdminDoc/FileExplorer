plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.explorer.fileexplorer.feature.network"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:ui"))
    implementation(project(":core:designsystem"))

    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.gson)
}
