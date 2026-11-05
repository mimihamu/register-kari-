package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The native updater must never render a light strip around the header. */
class V164NativeUpdatePinnedHeaderTest {
    private val pos = File("src/main/java/jp/co/tenposinfo/register/AppDistributionUpdateActivityV146.kt")
    private val plus = File("../management-app/src/main/java/jp/co/tenposinfo/register/plus/AppDistributionUpdateActivityV146.kt")

    @Test fun bothUpdaterScreensPinFullWidthNavyHeaderOutsideScroll() {
        for (file in listOf(pos, plus)) {
            val s = file.readText()
            assertTrue(file.path, s.contains("screen.addView(header, LinearLayout.LayoutParams(-1, dp(62)))"))
            assertTrue(file.path, s.contains("screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))"))
            assertTrue(file.path, s.contains("ViewCompat.setOnApplyWindowInsetsListener(screen)"))
            assertTrue(file.path, s.contains("ViewCompat.requestApplyInsets(screen)"))
            assertTrue(file.path, s.contains("setBackgroundColor(Color.rgb(23, 63, 107))"))
            assertTrue(file.path, s.contains("contentDescription = \"アプリ更新画面を閉じる\""))
            assertTrue(file.path, s.contains("isFillViewport = true"))
            assertFalse(file.path, s.contains("root.addView(TextView(this).apply {\n            text = \"アプリの更新\""))
            assertFalse(file.path, s.contains("ViewCompat.setOnApplyWindowInsetsListener(scroll)"))
            assertFalse(file.path, s.contains("setContentView(scroll)"))
        }
    }

    @Test fun updateVerificationRemainsIndependentOfToolbarRefactor() {
        for (file in listOf(pos, plus)) {
            val s = file.readText()
            assertTrue(s.contains("if (digest != item.sha256)"))
            assertTrue(s.contains("verifyApk(partial, item)"))
            assertTrue(s.contains("if (parsed.packageName != packageName"))
            assertTrue(s.contains("if (incomingVersion != item.code"))
            assertTrue(s.contains("if (incoming.isNullOrEmpty()"))
        }
    }
}
