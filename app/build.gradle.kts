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
    namespace = "jp.co.tenposinfo.register"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.co.tenposinfo.register"
        minSdk = 26
        targetSdk = 36
        versionCode = 47
        versionName = "0.17.0-dev.1"
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

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.work:work-runtime:2.11.2")

    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.11.4")
    implementation("androidx.compose.foundation:foundation:1.11.4")
    implementation("androidx.compose.material3:material3:1.4.0")

    debugImplementation("androidx.compose.ui:ui-tooling:1.11.4")

    testImplementation("junit:junit:4.13.2")
}
