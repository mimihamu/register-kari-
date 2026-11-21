package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The legacy 61-item release list must remain complete and must not be mistaken for acceptance. */
class V161InitialReleaseAuditIndexTest {
    private val root = File(System.getProperty("user.dir")).let {
        if (File(it, "docs").isDirectory) it else it.parentFile
    }

    @Test fun everyMasterIssueRowIsAccountedForWithoutCallingItAccepted() {
        val document = File(root, "docs/V1.61_INITIAL_RELEASE_61_EVIDENCE_INDEX.md").readText()
        val ids = document.lines().mapNotNull { line ->
            Regex("^\\|\\s*(\\d+)\\s*\\|").find(line)?.groupValues?.get(1)?.toIntOrNull()
        }
        assertEquals((1..61).toList(), ids)
        assertTrue(document.contains("正式受入は未証明"))
        assertTrue(document.contains("17行が「実機確認待ち」、44行が「将来版監査のみ」"))
        assertTrue(document.contains("正式仕様書v2.5の原本"))
        assertTrue(document.contains("V161RegisterMinusReopenSafetyTest"))
    }
}
