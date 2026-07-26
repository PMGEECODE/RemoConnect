import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.famage.remoconnect"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.famage.remoconnect"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePropFile = rootProject.file("keystore.properties")
            if (keystorePropFile.exists()) {
                val props = Properties().apply { load(keystorePropFile.inputStream()) }
                storeFile = rootProject.file(props.getProperty("storeFile", "release.keystore"))
                storePassword = props.getProperty("storePassword", "")
                keyAlias = props.getProperty("keyAlias", "remoconnect")
                keyPassword = props.getProperty("keyPassword", props.getProperty("storePassword", ""))
            } else {
                val releaseKeystore = rootProject.file("release.keystore")
                if (releaseKeystore.exists()) {
                    storeFile = releaseKeystore
                    storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                    keyAlias = System.getenv("KEY_ALIAS") ?: "remoconnect"
                    keyPassword = System.getenv("KEY_PASSWORD") ?: storePassword
                }
            }
        }
    }

    buildTypes {
        release {
            val relSigning = signingConfigs.getByName("release")
            if (relSigning.storeFile?.exists() == true && !relSigning.storePassword.isNullOrEmpty()) {
                signingConfig = relSigning
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

androidComponents {
    onVariants { variant ->
        val mainOutput = variant.outputs.singleOrNull()
        if (variant.buildType == "release") {
            mainOutput?.outputFileName?.set("RemoConnect.apk")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.google.play.services.cast.framework)
    implementation(libs.androidx.mediarouter)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.nanohttpd)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
