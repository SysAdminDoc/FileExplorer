plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.explorer.fileexplorer.core.network"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources { excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA") }
    }
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(libs.core.ktx)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.gson)

    // SMB
    implementation(libs.smbj)

    // SFTP
    implementation(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)

    // FTP
    implementation(libs.commons.net)

    // WebDAV
    implementation(libs.sardine.android)
}
