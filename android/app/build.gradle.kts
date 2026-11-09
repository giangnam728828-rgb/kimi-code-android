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
        versionCode = 4
        versionName = "2.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    /**
     * A stable signing key keeps every build installable over the previous one.
     * CI provides it through the KEYSTORE_* environment variables; without them
     * the build falls back to the debug key, which cannot overwrite a build
     * signed with a different key.
     */
    signingConfigs {
        create("release") {
            val storePath = System.getenv("KEYSTORE_PATH")
            if (!storePath.isNullOrBlank() && file(storePath).isFile) {
                storeFile = file(storePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEYSTORE_ALIAS") ?: "kimicode"
                keyPassword = System.getenv("KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val storePath = System.getenv("KEYSTORE_PATH")
            val hasReleaseKey = !storePath.isNullOrBlank() && file(storePath).isFile
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        // The rootfs is already gzip-compressed; re-deflating it would slow
        // first launch down for no size win. AGP strips the `.gz` suffix when
        // packaging, so the entry lands as `rootfs-arm64.tar`.
        noCompress += listOf("so", "bin", "tar")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
