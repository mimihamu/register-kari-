plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val generatedV010Dir = layout.buildDirectory.dir("generated/source/v010/main")
val generateV010Sources = tasks.register<Exec>("generateV010Sources") {
    val script = rootProject.file("tools/generate_v010.py")
    val sourceRoot = file("src/main/java")
    val fragments = rootProject.fileTree("tools/v08")
    inputs.file(script)
    inputs.dir(sourceRoot)
    inputs.files(fragments)
    outputs.dir(generatedV010Dir)
    commandLine(
        "python3",
        script.absolutePath,
        projectDir.absolutePath,
        generatedV010Dir.get().asFile.absolutePath,
    )
    doLast {
        val generatedFile = generatedV010Dir.get().asFile
            .resolve("jp/co/tenposinfo/register/DynamicCatalogRuntime.kt")
        val source = generatedFile.readText()
        generatedFile.writeText(
            source.replace(
                "error(\"${'$'}labelはyyyy-MM-dd形式です\")",
                "error(\"${'$'}{label}はyyyy-MM-dd形式です\")",
            ),
        )
    }
}

android {
    namespace = "jp.co.tenposinfo.register"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.co.tenposinfo.register"
        minSdk = 31
        targetSdk = 36
        versionCode = 11
        versionName = "0.11.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
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

    sourceSets {
        getByName("main") {
            java.setSrcDirs(listOf(generatedV010Dir.get().asFile))
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(generateV010Sources)
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
