plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val generatedV08Dir = layout.buildDirectory.dir("generated/source/v08/main")
val v08FragmentsDir = rootProject.file("tools/v08")

val generateV08Sources = tasks.register("generateV08Sources") {
    val sourceRoot = file("src/main/java")
    val mainSource = sourceRoot.resolve("jp/co/tenposinfo/register/MainActivity.kt")
    val operationsSource = sourceRoot.resolve("jp/co/tenposinfo/register/AdvancedOperationsActivity.kt")
    val fragments = fileTree(v08FragmentsDir)
    inputs.dir(sourceRoot)
    inputs.files(fragments)
    outputs.dir(generatedV08Dir)

    doLast {
        fun fragment(name: String): String = v08FragmentsDir.resolve(name).readText().trimEnd()

        fun replaceInclusive(
            source: String,
            startMarker: String,
            endMarker: String,
            replacement: String,
            label: String,
        ): String {
            val start = source.indexOf(startMarker)
            val endStart = source.indexOf(endMarker, start + startMarker.length)
            check(start >= 0 && endStart >= start) { "v0.8 source generation failed: $label" }
            val end = endStart + endMarker.length
            return source.substring(0, start) + replacement + source.substring(end)
        }

        fun replaceBefore(
            source: String,
            startMarker: String,
            nextMarker: String,
            replacement: String,
            label: String,
        ): String {
            val start = source.indexOf(startMarker)
            val end = source.indexOf(nextMarker, start + startMarker.length)
            check(start >= 0 && end > start) { "v0.8 source generation failed: $label" }
            return source.substring(0, start) + replacement + "\n\n" + source.substring(end)
        }

        fun replaceRequired(source: String, old: String, new: String, label: String): String {
            check(source.contains(old)) { "v0.8 source generation failed: $label" }
            return source.replace(old, new)
        }

        val generatedRoot = generatedV08Dir.get().asFile
        generatedRoot.deleteRecursively()
        generatedRoot.mkdirs()
        sourceRoot.walkTopDown()
            .filter { it.isFile }
            .filterNot { it == mainSource || it == operationsSource }
            .forEach { sourceFile ->
                val destination = generatedRoot.resolve(sourceFile.relativeTo(sourceRoot).path)
                destination.parentFile.mkdirs()
                sourceFile.copyTo(destination, overwrite = true)
            }

        val packageDir = generatedRoot.resolve("jp/co/tenposinfo/register")
        packageDir.mkdirs()

        var main = mainSource.readText()
        main = replaceInclusive(
            main,
            "    val database = remember { RegisterDatabase(context.applicationContext) }",
            "    val products = remember { database.loadProducts() }",
            fragment("main_state.ktfrag"),
            "MainActivity state",
        )
        main = replaceBefore(
            main,
            "            AppScreen.LOGIN -> LoginScreen(",
            "            AppScreen.SALES -> SalesScreen(",
            fragment("main_login_case.ktfrag"),
            "MainActivity login case",
        )
        main = replaceBefore(
            main,
            "            AppScreen.SALES -> SalesScreen(",
            "            AppScreen.LINE_EDIT -> {",
            fragment("main_sales_case.ktfrag"),
            "MainActivity sales case",
        )
        main = replaceBefore(
            main,
            "@Composable\nprivate fun LoginScreen(",
            "@OptIn(ExperimentalFoundationApi::class)",
            fragment("login_screen.ktfrag"),
            "LoginScreen",
        )
        main = replaceRequired(
            main,
            """
    onPayment: () -> Unit,
    onSalesHistory: () -> Unit,
    onPrintQueue: () -> Unit,
) {
""".trimIndent(),
            """
    onPayment: () -> Unit,
    onSalesHistory: () -> Unit,
    onPrintQueue: () -> Unit,
    accessMessage: String?,
    onLogout: () -> Unit,
) {
""".trimIndent(),
            "SalesScreen signature",
        )
        main = replaceBefore(
            main,
            """
        Row(
            Modifier.fillMaxWidth().height(38.dp).background(Color.White).padding(horizontal = 18.dp),
""".trimIndent(),
            "        Row(Modifier.weight(1f).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {",
            fragment("sales_header.ktfrag"),
            "SalesScreen header",
        )
        main = replaceRequired(
            main,
            """
                    val saleId = database.saveSale(operatorName, cart.toList(), paymentState, receiptPaper.widthMm)
                    lastSaleId = saleId
""".trimIndent(),
            """
                    val saleId = database.saveSale(operatorName, cart.toList(), paymentState, receiptPaper.widthMm)
                    AutomaticPrintScheduler.enqueueNow(context.applicationContext)
                    lastSaleId = saleId
""".trimIndent(),
            "sale immediate print",
        )
        main = replaceRequired(
            main,
            """
                            database.enqueueReprint(detail.summary.id, receiptPaper.widthMm)
                            queueMessage = "再印字をキューへ登録しました"
""".trimIndent(),
            """
                            database.enqueueReprint(detail.summary.id, receiptPaper.widthMm)
                            AutomaticPrintScheduler.enqueueNow(context.applicationContext)
                            queueMessage = "再印字をキューへ登録しました"
""".trimIndent(),
            "reprint immediate work",
        )
        packageDir.resolve("MainActivityGeneratedV08.kt").writeText(main)

        var operations = operationsSource.readText()
        operations = replaceInclusive(
            operations,
            "    val store = remember { AdvancedOperationsStore(context.applicationContext) }",
            "    var screen by remember { mutableStateOf(AdvancedScreen.MENU) }",
            fragment("advanced_state.ktfrag"),
            "AdvancedOperations state",
        )
        operations = replaceBefore(
            operations,
            "            AdvancedScreen.MENU -> AdvancedMenuScreen(",
            "            AdvancedScreen.BUSINESS -> BusinessDayScreen(",
            fragment("advanced_menu_case.ktfrag"),
            "AdvancedOperations menu",
        )
        val fixedPin = "require(pin == \"0000\") { \"責任者PINが違います（テストPIN：0000）\" }"
        check(operations.contains(fixedPin)) { "v0.8 source generation failed: fixed manager PIN" }
        operations = operations.replace(
            fixedPin,
            "require(OperatorSessionRegistry.verifyManagerPin(context.applicationContext, pin)) { \"責任者PINが違います\" }",
        )
        operations = operations.replace("責任者PIN（テスト：0000）", "責任者PIN")
        operations = operations.replace(
            "var operator by remember { mutableStateOf(\"責任者\") }",
            "var operator by remember { mutableStateOf(OperatorSessionRegistry.lastKnownName() ?: \"責任者\") }",
        )
        operations = replaceRequired(
            operations,
            "store.recordSettlement(type, actualCash, operator, paperWidth)",
            "store.recordSettlement(type, actualCash, operator, paperWidth).also { AutomaticPrintScheduler.enqueueNow(context.applicationContext) }",
            "settlement immediate print",
        )
        operations = replaceRequired(
            operations,
            "store.createReversal(saleId, type, quantities, reason, operator, paperWidth)",
            "store.createReversal(saleId, type, quantities, reason, operator, paperWidth).also { AutomaticPrintScheduler.enqueueNow(context.applicationContext) }",
            "reversal immediate print",
        )
        packageDir.resolve("AdvancedOperationsActivityGeneratedV08.kt").writeText(operations)
    }
}

android {
    namespace = "jp.co.tenposinfo.register"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.co.tenposinfo.register"
        minSdk = 31
        targetSdk = 36
        versionCode = 8
        versionName = "0.8.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
            java.setSrcDirs(listOf(generatedV08Dir))
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(generateV08Sources)
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
