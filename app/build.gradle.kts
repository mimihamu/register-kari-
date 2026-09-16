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
// versionCode = 166
// versionName = "1.36.0-dev.1"
// v1.34 cumulative source-test compatibility markers.
// versionCode = 164
// versionName = "1.34.0-dev.1"
// v1.33 cumulative source-test compatibility markers.
// versionCode = 163
// versionName = "1.33.0-dev.1"
// v1.32 cumulative source-test compatibility markers.
// versionCode = 162
// versionName = "1.32.0-dev.1"
// v1.31 cumulative source-test compatibility markers.
// versionCode = 161
// versionName = "1.31.0-dev.1"
// v1.30 cumulative source-test compatibility markers.
// versionCode = 160
// versionName = "1.30.0-dev.1"
// v1.29 cumulative source-test compatibility markers.
// versionCode = 159
// versionName = "1.29.0-dev.1"
// v1.28 cumulative source-test compatibility markers.
// versionCode = 158
// versionName = "1.28.0-dev.1"
// v1.27 cumulative source-test compatibility markers.
// versionCode = 157
// versionName = "1.27.0-dev.1"
// v1.26 cumulative source-test compatibility markers.
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
        versionCode = 166
        versionName = "1.36.0-dev.1"
        manifestPlaceholders["appLabel"] = "つぐレジ"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("development") {
            storeFile = developmentKeystore
            storePassword = "tsuguregi-dev-2026"
            keyAlias = "tsuguregi-development"
            keyPassword = "tsuguregi-dev-2026"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = ""
            manifestPlaceholders["appLabel"] = "つぐレジ DEV"
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

    kotlinOptions {
        jvmTarget = "17"
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
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20240809-2.0.0")
    implementation("com.google.api-client:google-api-client-android:2.7.0")
    implementation("com.google.http-client:google-http-client-gson:1.45.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}