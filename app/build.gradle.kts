plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.explorer.fileexplorer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.explorer.fileexplorer"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:storage"))
    implementation(project(":core:database"))
    implementation(project(":core:ui"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:browser"))
    implementation(project(":feature:transfer"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:search"))
    implementation(project(":core:network"))
    implementation(project(":core:cloud"))
    implementation(project(":feature:network"))
    implementation(project(":feature:cloud"))
    implementation(project(":feature:security"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:apps"))

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
}
