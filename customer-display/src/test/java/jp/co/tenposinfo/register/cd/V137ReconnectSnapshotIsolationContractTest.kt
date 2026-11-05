package jp.co.tenposinfo.register.cd

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V137ReconnectSnapshotIsolationContractTest {
    @Test
    fun websocketHubDropsStaleSnapshotWhenNewTransportConnects() {
        val text = File("src/main/java/jp/co/tenposinfo/register/cd/CustomerDisplayWebSocketClient.kt").readText()
        val block = text.substringAfter("private fun notifyConnected()").substringBefore("private fun notifySnapshot")
        assertTrue(block.contains("latestSnapshot = null"))
        assertTrue(text.contains("visibility.transportConnected && replaySnapshot != null"))
        assertTrue(text.contains("replaySnapshot = if (visibility.transportConnected) latestSnapshot else null"))
    }

    @Test
    fun uiShowsAmountsOnlyAfterVerifiedBusinessSnapshot() {
        val model = File("src/main/java/jp/co/tenposinfo/register/cd/CustomerDisplayModel.kt").readText()
        val screen = File("src/main/java/jp/co/tenposinfo/register/cd/MainActivity.kt").readText()
        assertTrue(model.contains("connected = false,\n        statusMessage = \"最新の表示データを受信中\""))
        assertTrue(screen.contains("if (!state.connected)"))
        assertTrue(screen.contains("DisconnectedScreen(state.lastError, layoutMode)"))
    }
}
