package jp.co.tenposinfo.register

data class PrinterSoakTestFinishResolution(
    val shouldFinalize: Boolean,
    val completedCount: Int,
    val summary: String,
)

object PrinterSoakTestFinishPolicy {
    fun resolve(
        currentStatus: PrinterSoakTestRunStatus,
        currentCompletedCount: Int,
        currentSummary: String,
        requestedCompletedCount: Int,
        requestedSummary: String,
    ): PrinterSoakTestFinishResolution =
        if (currentStatus == PrinterSoakTestRunStatus.RUNNING) {
            PrinterSoakTestFinishResolution(
                shouldFinalize = true,
                completedCount = requestedCompletedCount.coerceAtLeast(0),
                summary = requestedSummary.take(1_000),
            )
        } else {
            PrinterSoakTestFinishResolution(
                shouldFinalize = false,
                completedCount = currentCompletedCount,
                summary = currentSummary,
            )
        }
}
