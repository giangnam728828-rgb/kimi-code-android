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
        // Must stay strictly above whatever is installed on the device:
        // Android refuses to overwrite an APK with a lower/equal versionCode.
        // main was 2.6.0 / 9; raised to 20 with headroom so the same APK can
        // upgrade any 2.6.x / 2.7.x build. Verify against the installed
        // value before shipping if your baseline differs.
        versionCode = 20
        versionName = "2.8.0"
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
            val storePasswordEnv = System.getenv("KEYSTORE_PASSWORD")
            if (!storePath.isNullOrBlank() && file(storePath).isFile) {
                // A keystore without its password fails deep inside packaging,
                // where the message hides the real cause, so say it here.
                check(!storePasswordEnv.isNullOrBlank()) {
                    "KEYSTORE_PATH is set but KEYSTORE_PASSWORD is empty; " +
                        "every release must keep the same signing key."
                }
                storeFile = file(storePath)
                storePassword = storePasswordEnv
                keyAlias = System.getenv("KEYSTORE_ALIAS") ?: "kimicode"
                keyPassword = System.getenv("KEY_PASSWORD") ?: storePasswordEnv
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
