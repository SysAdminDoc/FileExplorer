plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.explorer.fileexplorer.core.data"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:storage"))
    implementation(libs.core.ktx)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.documentfile)

    // Root access
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.libsu.nio)

    // Archive handling
    implementation(libs.commons.compress)
    implementation(libs.zip4j)
    implementation(libs.xz)
    implementation(libs.zstd.jni)
}
