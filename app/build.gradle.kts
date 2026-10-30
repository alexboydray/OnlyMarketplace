plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.alex.onlymarketplace"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alex.onlymarketplace"
        minSdk = 28
        targetSdk = 35
        versionCode = 85
        versionName = "1.1.8"
    }

    buildFeatures {
        buildConfig = true
    }

    // Permanent key (kept in C:\Users\Alex\Projects\OnlyMarketplace-signing, uploaded as repo
    // secrets). Every build must use the SAME key or Android refuses to update the installed app.
    signingConfigs {
        create("permanent") {
            val store = System.getenv("SIGNING_STORE_FILE")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "onlymarketplace"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("SIGNING_STORE_FILE") != null)
                signingConfigs.getByName("permanent")
            else
                signingConfigs.getByName("debug") // local builds only; CI always has the key
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
