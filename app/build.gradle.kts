plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mbnetwork.filemanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mbnetwork.filemanager"
        minSdk = 21          // Android 5.0 Lollipop
        targetSdk = 34
        versionCode = 40
        versionName = "2.9.0.020"
    }

    // One fixed key for every build, so new versions install as UPDATES over old ones.
    // The key comes from GitHub Secrets (never committed). Without it, the debug key is used.
    val ksPath = System.getenv("MBFM_KEYSTORE")
    val hasKey = ksPath != null && file(ksPath).exists()
    signingConfigs {
        create("mbfm") {
            if (hasKey) {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("MBFM_STORE_PASSWORD")
                keyAlias = System.getenv("MBFM_KEY_ALIAS")
                keyPassword = System.getenv("MBFM_KEY_PASSWORD")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            if (hasKey) signingConfig = signingConfigs.getByName("mbfm")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasKey) "mbfm" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // MANAGE_EXTERNAL_STORAGE / QUERY_ALL_PACKAGES are intentional for a file & app manager.
        abortOnError = false
        checkReleaseBuilds = false
        // used by the optional CI step "Android 5 compatibility" (report only)
        checkOnly += "NewApi"
        textReport = true
        textOutput = file("stdout")
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("me.jahnen.libaums:core:0.10.0")
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
