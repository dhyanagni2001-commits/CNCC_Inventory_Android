plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.cnanjappa.inventory"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.cnanjappa.inventory"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Optimised like release but signed with the local debug key: for smoothness testing on
        // emulators/phones only. The shop's release APK must be signed with the shop's own key.
        create("preview") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            optimization {
                enable = true
                keepRules { files.add(file("proguard-rules.pro")) }
            }
            // Real phones are ARM; leaving out the x86 emulator copies of the native scanner/SQLite
            // libraries keeps the APK small. Debug builds keep all ABIs for the emulator.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
        // Separate app id: debug installs and connectedAndroidTest (which uninstalls after running)
        // can never touch the data of the preview/release app on the same phone.
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            optimization {
                enable = true
                keepRules { files.add(file("proguard-rules.pro")) }
            }
            // Real phones are ARM; leaving out the x86 emulator copies of the native scanner/SQLite
            // libraries keeps the APK small. Debug builds keep all ABIs for the emulator.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        animationsDisabled = true
    }
    sourceSets {
        // Exported Room schemas are used by migration tests.
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    implementation(libs.sqlite.bundled)
    implementation(libs.paging.compose)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)
    // Bundled ML Kit model: scans offline, no Google Play services or download needed.
    implementation(libs.mlkit.barcode)
    ksp(libs.room.compiler)
    constraints {
        // room-testing needs 1.8.x; the test classpath is pinned to the app's, so align it here.
        implementation(libs.kotlinx.serialization.core)
    }
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
