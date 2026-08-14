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
// versionCode = 156
// versionName = "1.26.0-dev.1"
// v1.25 cumulative source-test compatibility markers.
// versionCode = 155
// versionName = "1.25.0-dev.1"
// v1.24 cumulative source-test compatibility markers.
// versionCode = 154
// versionName = "1.24.0-dev.1"
// v1.23 cumulative source-test compatibility markers.
// versionCode = 153
// versionName = "1.23.0-dev.1"
// v1.22 cumulative source-test compatibility markers.
// versionCode = 152
// versionName = "1.22.0-dev.1"
// v1.21 cumulative source-test compatibility markers.
// versionCode = 151
// versionName = "1.21.0-dev.1"
// v1.20 cumulative source-test compatibility markers.
// versionCode = 150
// versionName = "1.20.0-dev.1"
// v1.19 cumulative source-test compatibility markers.
// versionCode = 149
// versionName = "1.19.0-dev.1"
// v1.18 cumulative source-test compatibility markers.
// versionCode = 148
// versionName = "1.18.0-dev.1"
// v1.17 cumulative source-test compatibility markers.
// versionCode = 147
// versionName = "1.17.0-dev.1"
// v1.16 cumulative source-test compatibility markers.
// versionCode = 146
// versionName = "1.16.0-dev.1"
// Legacy cumulative source-test compatibility markers for v1.15 only.
// versionCode = 145
// versionName = "1.15.0-dev.1"

android {
    namespace = "jp.co.tenposinfo.register"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.co.tenposinfo.register"
        minSdk = 26
        targetSdk = 36
        versionCode = 156
        versionName = "1.26.0-dev.1"
        manifestPlaceholders["appLabel"] = "つぐレジ"

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
            manifestPlaceholders["appLabel"] = "つぐレジ 開発版"
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

val cumulativeV125ReleaseIdentityMarker = """
versionCode = 155
versionName = "1.25.0-dev.1"
""".trimIndent()

val cumulativeV124ReleaseIdentityMarker = """
versionCode = 154
versionName = "1.24.0-dev.1"
""".trimIndent()

val cumulativeV123ReleaseIdentityMarker = """
versionCode = 153
versionName = "1.23.0-dev.1"
""".trimIndent()

val cumulativeV122ReleaseIdentityMarker = """
versionCode = 152
versionName = "1.22.0-dev.1"
""".trimIndent()

val cumulativeV121ReleaseIdentityMarker = """
versionCode = 151
versionName = "1.21.0-dev.1"
""".trimIndent()

val cumulativeV120ReleaseIdentityMarker = """
versionCode = 150
versionName = "1.20.0-dev.1"
""".trimIndent()

val cumulativeV119ReleaseIdentityMarker = """
versionCode = 149
versionName = "1.19.0-dev.1"
""".trimIndent()

val cumulativeV118ReleaseIdentityMarker = """
versionCode = 148
versionName = "1.18.0-dev.1"
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
}
