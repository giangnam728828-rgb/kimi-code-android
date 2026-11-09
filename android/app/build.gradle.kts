plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.kimi.code.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.kimi.code.app"
        minSdk = 26
        targetSdk = 28
        versionCode = 3
        versionName = "2.0.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Lint needs network-only artifacts that are not part of the build itself.
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.systemProperty("kimi.rootfs", System.getProperty("kimi.rootfs") ?: "")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("so", "bin")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
