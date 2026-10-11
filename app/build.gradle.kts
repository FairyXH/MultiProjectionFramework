plugins {
    alias(libs.plugins.android.application)
}
android {
    namespace = "com.fairyxh.multiprojection"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        applicationId = "com.fairyxh.multiprojection"
        // Match Vector/LSPatch's Android 9 runtime floor; compileSdk is a build-time requirement.
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    signingConfigs {
        create("releaseEnv") {
            val ksPath = System.getenv("TEMPLATE_KS_PATH")
            val ksPass = System.getenv("TEMPLATE_KS_PASS")
            val alias = System.getenv("TEMPLATE_KEY_ALIAS")
            val keyPass = System.getenv("TEMPLATE_KEY_PASS")
            if (!ksPath.isNullOrBlank() && !ksPass.isNullOrBlank()
                    && !alias.isNullOrBlank() && !keyPass.isNullOrBlank()) {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("boolean", "VERBOSE_LOGS", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "VERBOSE_LOGS", "false")

            val envSigning = signingConfigs.getByName("releaseEnv")
            signingConfig = if (envSigning.storeFile != null) envSigning else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}
dependencies {
    // Vector 2.2 and LSPatch 1.2 provide libxposed API 102 at runtime. Keep the API out of the APK.
    compileOnly(libs.libxposed.api)
    compileOnly(libs.androidx.annotation)
}