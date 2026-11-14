plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun env(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.codeassist.agenttrader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.codeassist.agenttrader"
        minSdk = 23
        targetSdk = 36
        // CI injects VERSION_CODE / VERSION_NAME per release tag (workflow "Compute version");
        // local builds fall back to 1 / 1.0.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0"
    }

    // Stable debug signing: the keystore is committed to the repo (debug keys are not
    // secret — every Android dev machine uses the same well-known credentials), so
    // every CI debug APK shares ONE signature and an update installs over the previous
    // build WITHOUT wiping app data. Without this, CI falls back to the runner's
    // ~/.android/debug.keystore which changes with runner images — forcing an
    // uninstall (and a data wipe) on every update.
    val debugKeystore = file("debug.keystore")
    if (debugKeystore.exists()) {
        signingConfigs {
            create("debugStable") {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    // Release signing: CI injects KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD.
    // Without them the release APK stays unsigned (debug APK is always installable).
    env("KEYSTORE_BASE64")?.let { _ ->
        signingConfigs {
            create("release") {
                storeFile = file("agent-trader.keystore")
                storePassword = env("KEYSTORE_PASSWORD")
                keyAlias = env("KEY_ALIAS")
                keyPassword = env("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            if (debugKeystore.exists()) signingConfig = signingConfigs.getByName("debugStable")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            env("KEYSTORE_BASE64")?.let { _ -> signingConfig = signingConfigs.getByName("release") }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.11.2")
    implementation("androidx.compose.foundation:foundation:1.11.2")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.11.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303")
}
