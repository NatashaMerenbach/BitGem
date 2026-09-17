// Single-module app. The layers live as packages under app/src/main/java/com/bitgem/colorcam/:
//
//   domain/   pure Kotlin — models, use cases and the whole colour pipeline (no Android imports)
//   data/     CameraX analyser (ImageProxy → domain frames), repository implementation, Hilt wiring
//   ui/       Compose screens, ViewModel, theme
//
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.bitgem.colorcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bitgem.colorcam"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // ----- domain/ layer: plain Kotlin libraries only -----
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.javax.inject)

    // ----- AndroidX core / lifecycle / activity -----
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // ----- Compose (versions from the BOM) -----
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // ----- CameraX (preview in the UI layer, analysis pipeline in data/) -----
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ----- Coroutines / DI -----
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // ----- JVM unit tests (no device, no Robolectric) -----
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)

    // ----- Instrumented UI tests -----
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
