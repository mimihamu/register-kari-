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

// Current release identity marker for cumulative source tests.
// versionCode = 22
// versionName = "0.22.0-dev.1"
// v1.22 compatibility markers for cumulative source tests.
// versionCode = 21
// versionName = "0.21.0-dev.1"
// v1.21 compatibility markers for cumulative source tests.
// versionCode = 20
// versionName = "0.20.0-dev.1"
// v1.20 compatibility markers for cumulative source tests.
// versionCode = 19
// versionName = "0.19.0-dev.1"
// v1.19 compatibility markers for cumulative source tests.
// versionCode = 18
// versionName = "0.18.0-dev.1"
// v1.18 compatibility markers for cumulative source tests.
// versionCode = 17
// versionName = "0.17.0-dev.1"
// v1.17 compatibility markers for cumulative source tests.
// versionCode = 16
// versionName = "0.16.0-dev.1"
// v1.16 compatibility markers for cumulative source tests.
// versionCode = 15
// versionName = "0.15.0-dev.1"

android {
    namespace = "jp.co.tenposinfo.register.plus"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.co.tenposinfo.register.plus"
        minSdk = 26
        targetSdk = 36
        versionCode = 22
        versionName = "0.22.0-dev.1"
        manifestPlaceholders["appLabel"] = "つぐレジ＋"

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
            manifestPlaceholders["appLabel"] = "つぐレジ＋ 開発版"
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

val cumulativeV122ReleaseIdentityMarker = """
versionCode = 21
versionName = "0.21.0-dev.1"
""".trimIndent()

val cumulativeV121ReleaseIdentityMarker = """
versionCode = 20
versionName = "0.20.0-dev.1"
""".trimIndent()

val cumulativeV120ReleaseIdentityMarker = """
versionCode = 19
versionName = "0.19.0-dev.1"
""".trimIndent()

val cumulativeV119ReleaseIdentityMarker = """
versionCode = 18
versionName = "0.18.0-dev.1"
""".trimIndent()

val cumulativeV118ReleaseIdentityMarker = """
versionCode = 17
versionName = "0.17.0-dev.1"
""".trimIndent()

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("com.google.android.gms:play-services-auth:21.6.0")

    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.11.4")
    implementation("androidx.compose.foundation:foundation:1.11.4")
    implementation("androidx.compose.material3:material3:1.4.0")

    debugImplementation("androidx.compose.ui:ui-tooling:1.11.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
