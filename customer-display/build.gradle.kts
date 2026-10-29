import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val developmentKeystoreSource = rootProject.file("ci/tsuguregi-development.jks.b64")
val developmentKeystore = layout.buildDirectory.file("signing/tsuguregi-development.jks").get().asFile
require(developmentKeystoreSource.isFile) { "開発版署名鍵が見つかりません" }
developmentKeystore.parentFile.mkdirs()
developmentKeystore.writeBytes(Base64.getMimeDecoder().decode(developmentKeystoreSource.readText()))

android {
    namespace = "jp.co.tenposinfo.register.cd"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.co.tenposinfo.register.cd"
        minSdk = 26
        targetSdk = 36
        // Previous CD development version, retained for cumulative compatibility gates.
        // versionCode = 7
        // versionName = "0.14.0-dev.1"
        // Previously published CD v0.15.0 development APK for cumulative audit.
        // versionCode = 8
        // versionName = "0.15.0-dev.1"
        // Previous verified development APK (v0.15.1); retained for source gates.
        // versionCode = 9
        // versionName = "0.15.1-dev.1"
        // Previous verified CD development version; kept for cumulative gates.
        // versionCode = 10
        // versionName = "0.15.2-dev.1"
        // Previous verified development release:
        // versionCode = 11
        // versionName = "0.15.3-dev.1"
        // Previous verified development version:
        // versionCode = 12
        // versionName = "0.15.4-dev.1"
        // Previous verified CD v0.15.5 release for cumulative compatibility tests.
        // versionCode = 13
        // versionName = "0.15.5-dev.1"
        // Previous verified v1.45 CD version; retained for audit.
        // versionCode = 14
        // versionName = "0.15.6-dev.1"
        // versionCode = 15
        // versionName = "0.15.7-dev.1"
        // v1.47 signed CI build retained for upgrade history.
        // versionCode = 16
        // versionName = "0.15.8-dev.1"
        // v1.48 verified build
        // versionCode = 17
        // versionName = "0.15.9-dev.1"
        // v1.49 verified build
        // versionCode = 18
        // versionName = "0.15.10-dev.1"
        // v1.50 verified
        // versionCode = 19
        // versionName = "0.15.11-dev.1"
        // v1.51 verified
        // versionCode = 20
        // versionName = "0.15.12-dev.1"
        // v1.52 verified
        // versionCode = 21
        // versionName = "0.15.13-dev.1"
        // v1.53 verified
        // versionCode = 22
        // versionName = "0.15.14-dev.1"
        // v1.54 verified
        // versionCode = 23
        // versionName = "0.15.15-dev.1"
        // v1.57: versionCode 25, versionName 0.15.17-dev.1
        // v1.58: versionCode 26, versionName 0.15.18-dev.1
        // v1.59: versionCode 27, versionName 0.15.19-dev.1
        // v1.60: versionCode 28, versionName 0.15.20-dev.1
        // v1.61: versionCode 29, versionName 0.15.21-dev.1
        // v1.62 versionCode 30 / 0.15.22-dev.1
        versionCode = 31
        versionName = "0.15.23-dev.1"
        manifestPlaceholders["appLabel"] = "つぐレジ CD"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("development") {
            storeFile = developmentKeystore
            storePassword = "tsuguregi-dev"
            keyAlias = "tsuguregi-dev"
            keyPassword = "tsuguregi-dev"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "つぐレジ CD 開発版"
            signingConfig = signingConfigs.getByName("development")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.11.4")
    implementation("androidx.compose.foundation:foundation:1.11.4")
    implementation("androidx.compose.material3:material3:1.4.0")

    debugImplementation("androidx.compose.ui:ui-tooling:1.11.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
