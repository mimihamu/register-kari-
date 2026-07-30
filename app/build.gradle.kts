plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val generatedV08Dir = layout.buildDirectory.dir("generated/source/v08/main")

val generateV08Sources = tasks.register("generateV08Sources") {
    val mainSource = file("src/main/java/jp/co/tenposinfo/register/MainActivity.kt")
    val operationsSource = file("src/main/java/jp/co/tenposinfo/register/AdvancedOperationsActivity.kt")
    inputs.files(mainSource, operationsSource)
    outputs.dir(generatedV08Dir)

    doLast {
        fun replaceRequired(source: String, old: String, new: String, label: String): String {
            check(source.contains(old)) { "v0.8 source generation failed: $label" }
            return source.replace(old, new)
        }

        fun replaceSection(source: String, startMarker: String, endMarker: String, replacement: String, label: String): String {
            val start = source.indexOf(startMarker)
            val end = source.indexOf(endMarker, start + startMarker.length)
            check(start >= 0 && end > start) { "v0.8 source generation failed: $label" }
            return source.substring(0, start) + replacement + "\n\n" + source.substring(end)
        }

        val packageDir = generatedV08Dir.get().asFile.resolve("jp/co/tenposinfo/register")
        packageDir.mkdirs()

        var main = mainSource.readText()
        main = replaceRequired(
            main,
            """
    val database = remember { RegisterDatabase(context.applicationContext) }
    var screen by remember { mutableStateOf(AppScreen.DIAGNOSTIC) }
    var operatorName by remember { mutableStateOf("未選択") }
    val products = remember { database.loadProducts() }
""".trimIndent(),
            """
    val database = remember { RegisterDatabase(context.applicationContext) }
    val authStore = remember { OperatorAuthenticationStore(context.applicationContext) }
    var currentOperator by remember { mutableStateOf(OperatorSessionRegistry.current(context.applicationContext)) }
    var screen by remember { mutableStateOf(if (currentOperator == null) AppScreen.DIAGNOSTIC else AppScreen.SALES) }
    var operatorName by remember { mutableStateOf(currentOperator?.name ?: "未選択") }
    var loginMessage by remember { mutableStateOf<String?>(null) }
    var accessMessage by remember { mutableStateOf<String?>(null) }
    val products = remember { database.loadProducts() }
""".trimIndent(),
            "MainActivity state",
        )
        main = replaceRequired(
            main,
            """
            AppScreen.LOGIN -> LoginScreen(
                onLogin = {
                    operatorName = it
                    screen = AppScreen.SALES
                },
            )
""".trimIndent(),
            """
            AppScreen.LOGIN -> LoginScreen(
                operators = authStore.listEnabledOperators(),
                message = loginMessage,
                onLogin = { operatorId, pin ->
                    val result = authStore.authenticate(operatorId, pin)
                    val authenticated = result.getOrNull()
                    if (authenticated == null) {
                        loginMessage = result.exceptionOrNull()?.message ?: "ログインに失敗しました"
                    } else {
                        OperatorSessionRegistry.login(context.applicationContext, authenticated)
                        currentOperator = authenticated
                        operatorName = authenticated.name
                        loginMessage = null
                        accessMessage = null
                        (context as? android.app.Activity)?.recreate()
                    }
                },
            )
""".trimIndent(),
            "MainActivity login call",
        )
        main = replaceRequired(
            main,
            "                onTickets = { screen = AppScreen.TICKETS },",
            """
                onTickets = {
                    if (currentOperator?.allows(RegisterPermission.HOLD_TICKET) == true) {
                        accessMessage = null
                        screen = AppScreen.TICKETS
                    } else {
                        accessMessage = "保留伝票の権限がありません"
                    }
                },
""".trimIndent(),
            "ticket permission",
        )
        main = replaceRequired(
            main,
            """
                onHold = {
                    if (cart.isNotEmpty()) {
                        val sequence = database.listHeldTickets().size + 1
                        database.holdCart("伝票$sequence", operatorName, cart.toList())
                        replaceCart(emptyList())
                    }
                },
""".trimIndent(),
            """
                onHold = {
                    if (currentOperator?.allows(RegisterPermission.HOLD_TICKET) != true) {
                        accessMessage = "保留伝票の権限がありません"
                    } else if (cart.isNotEmpty()) {
                        accessMessage = null
                        val sequence = database.listHeldTickets().size + 1
                        database.holdCart("伝票$sequence", operatorName, cart.toList())
                        replaceCart(emptyList())
                    }
                },
""".trimIndent(),
            "hold permission",
        )
        main = replaceRequired(
            main,
            """
                onSalesHistory = { screen = AppScreen.SALES_HISTORY },
                onPrintQueue = { screen = AppScreen.PRINT_QUEUE },
            )
""".trimIndent(),
            """
                onSalesHistory = {
                    if (currentOperator?.allows(RegisterPermission.VIEW_SALES) == true) {
                        accessMessage = null
                        screen = AppScreen.SALES_HISTORY
                    } else {
                        accessMessage = "売上確認の権限がありません"
                    }
                },
                onPrintQueue = {
                    if (currentOperator?.allows(RegisterPermission.VIEW_SALES) == true) {
                        accessMessage = null
                        screen = AppScreen.PRINT_QUEUE
                    } else {
                        accessMessage = "印刷キュー確認の権限がありません"
                    }
                },
                accessMessage = accessMessage,
                onLogout = {
                    OperatorSessionRegistry.logout(context.applicationContext)
                    currentOperator = null
                    operatorName = "未選択"
                    accessMessage = null
                    (context as? android.app.Activity)?.recreate()
                },
            )
""".trimIndent(),
            "sales permission callbacks",
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
        main = replaceRequired(
            main,
            """
        Row(
            Modifier.fillMaxWidth().height(38.dp).background(Color.White).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("店舗：サンプル居酒屋  |  担当：$operatorName", color = Navy, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("SQLite保存・オフライン販売", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
        }
""".trimIndent(),
            """
        Row(
            Modifier.fillMaxWidth().height(48.dp).background(Color.White).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("店舗：サンプル居酒屋  |  担当：$operatorName", color = Navy, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (accessMessage != null) {
                Text(accessMessage, color = Danger, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
            } else {
                Text("SQLite保存・オフライン販売", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
            }
            OutlinedButton(onClick = onLogout, modifier = Modifier.height(40.dp)) { Text("担当者切替") }
        }
""".trimIndent(),
            "SalesScreen header",
        )
        val loginReplacement = """
@Composable
private fun LoginScreen(
    operators: List<OperatorRecord>,
    message: String?,
    onLogin: (Long, String) -> Unit,
) {
    var selectedId by remember(operators) { mutableStateOf(operators.firstOrNull()?.id) }
    var pin by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize()) {
        Header("SCR-010", "担当者選択／ログイン")
        Row(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("担当者を選択", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(18.dp))
                if (operators.isEmpty()) {
                    Text("有効な担当者が登録されていません", color = Danger, fontWeight = FontWeight.Bold)
                }
                for (rowStart in operators.indices step 3) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (index in rowStart until minOf(rowStart + 3, operators.size)) {
                            val operator = operators[index]
                            OutlinedButton(
                                onClick = { selectedId = operator.id; pin = "" },
                                modifier = Modifier.weight(1f).height(82.dp),
                                border = BorderStroke(
                                    if (selectedId == operator.id) 3.dp else 1.dp,
                                    if (selectedId == operator.id) Danger else Border,
                                ),
                            ) {
                                Text(
                                    "${operator.name}\n${operator.role.displayName}",
                                    fontSize = 19.sp,
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Bold,
                                    color = Navy,
                                )
                            }
                        }
                        for (unused in minOf(rowStart + 3, operators.size) until rowStart + 3) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
            CardPanel(Modifier.width(390.dp).fillMaxHeight()) {
                Text("PIN入力", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(12.dp))
                ValueBox(if (pin.isEmpty()) "PINを入力" else "●".repeat(pin.length))
                if (message != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(message, color = Danger, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(14.dp))
                NumberPad(
                    onDigit = { if (pin.length < 8) pin += it },
                    onClear = { pin = "" },
                    bottomActionLabel = "ログイン",
                    onBottomAction = { selectedId?.let { onLogin(it, pin) } },
                )
            }
        }
    }
}
""".trimIndent()
        main = replaceSection(
            main,
            "@Composable\nprivate fun LoginScreen(",
            "@OptIn(ExperimentalFoundationApi::class)",
            loginReplacement,
            "LoginScreen section",
        )
        packageDir.resolve("MainActivityGeneratedV08.kt").writeText(main)

        var operations = operationsSource.readText()
        operations = replaceRequired(
            operations,
            """
    val store = remember { AdvancedOperationsStore(context.applicationContext) }
    val registerDatabase = remember { RegisterDatabase(context.applicationContext) }
    var screen by remember { mutableStateOf(AdvancedScreen.MENU) }
""".trimIndent(),
            """
    val store = remember { AdvancedOperationsStore(context.applicationContext) }
    val registerDatabase = remember { RegisterDatabase(context.applicationContext) }
    val currentOperator = remember { OperatorSessionRegistry.current(context.applicationContext) }
    val permissions = currentOperator?.permissions.orEmpty()
    var screen by remember { mutableStateOf(AdvancedScreen.MENU) }
""".trimIndent(),
            "AdvancedOperations state",
        )
        operations = replaceRequired(
            operations,
            """
                onBusiness = { message = null; screen = AdvancedScreen.BUSINESS },
                onDaily = { message = null; screen = AdvancedScreen.DAILY },
                onSettlement = { message = null; screen = AdvancedScreen.SETTLEMENT },
                onCash = { message = null; screen = AdvancedScreen.CASH },
                onReversal = { message = null; screen = AdvancedScreen.REVERSAL },
                onPrintQueue = { message = null; screen = AdvancedScreen.PRINT_QUEUE },
""".trimIndent(),
            """
                onBusiness = {
                    if (RegisterPermission.SETTLEMENT in permissions) { message = null; screen = AdvancedScreen.BUSINESS }
                    else message = "営業開始・終了の権限がありません"
                },
                onDaily = {
                    if (RegisterPermission.VIEW_SALES in permissions) { message = null; screen = AdvancedScreen.DAILY }
                    else message = "売上確認の権限がありません"
                },
                onSettlement = {
                    if (RegisterPermission.SETTLEMENT in permissions) { message = null; screen = AdvancedScreen.SETTLEMENT }
                    else message = "点検・精算の権限がありません"
                },
                onCash = {
                    if (RegisterPermission.CASH_MOVEMENT in permissions) { message = null; screen = AdvancedScreen.CASH }
                    else message = "入出金の権限がありません"
                },
                onReversal = {
                    if (RegisterPermission.REVERSAL in permissions) { message = null; screen = AdvancedScreen.REVERSAL }
                    else message = "返品・取消の権限がありません"
                },
                onPrintQueue = {
                    if (permissions.any { it == RegisterPermission.VIEW_SALES || it == RegisterPermission.SETTLEMENT || it == RegisterPermission.REVERSAL }) {
                        message = null; screen = AdvancedScreen.PRINT_QUEUE
                    } else message = "印刷キュー確認の権限がありません"
                },
""".trimIndent(),
            "AdvancedOperations permissions",
        )
        operations = operations.replace(
            "require(pin == \"0000\") { \"責任者PINが違います（テストPIN：0000）\" }",
            "require(OperatorSessionRegistry.verifyManagerPin(context.applicationContext, pin)) { \"責任者PINが違います\" }",
        )
        operations = operations.replace("責任者PIN（テスト：0000）", "責任者PIN")
        operations = operations.replace(
            "var operator by remember { mutableStateOf(\"責任者\") }",
            "var operator by remember { mutableStateOf(OperatorSessionRegistry.lastKnownName() ?: \"責任者\") }",
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
            java.exclude("jp/co/tenposinfo/register/MainActivity.kt")
            java.exclude("jp/co/tenposinfo/register/AdvancedOperationsActivity.kt")
            java.srcDir(generatedV08Dir)
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
