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
        versionCode = 1
        versionName = "1.0"
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
