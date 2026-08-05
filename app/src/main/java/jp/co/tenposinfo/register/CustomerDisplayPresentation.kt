package jp.co.tenposinfo.register

import org.json.JSONObject

enum class CustomerDisplayTheme {
    NAVY,
    LIGHT,
    WARM,
}

data class CustomerDisplayPresentation(
    val theme: CustomerDisplayTheme = CustomerDisplayTheme.NAVY,
    val showLogo: Boolean = true,
    val logoText: String = "つぐ",
    val textScalePercent: Int = 100,
    val maxVisibleRows: Int = 8,
    val rowSpacingDp: Int = 8,
    val showCancelledItems: Boolean = true,
    val showTaxSymbol: Boolean = true,
    val standbyMessage: String = "いらっしゃいませ",
) {
    init {
        require(textScalePercent in 80..150) { "textScalePercent must be between 80 and 150" }
        require(maxVisibleRows in 3..20) { "maxVisibleRows must be between 3 and 20" }
        require(rowSpacingDp in 0..24) { "rowSpacingDp must be between 0 and 24" }
        require(logoText.length <= 12) { "logoText is too long" }
        require(standbyMessage.length <= 80) { "standbyMessage is too long" }
    }

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("theme", theme.name)
        put("showLogo", showLogo)
        put("logoText", logoText)
        put("textScalePercent", textScalePercent)
        put("maxVisibleRows", maxVisibleRows)
        put("rowSpacingDp", rowSpacingDp)
        put("showCancelledItems", showCancelledItems)
        put("showTaxSymbol", showTaxSymbol)
        put("standbyMessage", standbyMessage)
    }

    companion object {
        fun fromJsonObject(json: JSONObject?): CustomerDisplayPresentation {
            if (json == null) return CustomerDisplayPresentation()
            val theme = runCatching {
                CustomerDisplayTheme.valueOf(json.optString("theme", CustomerDisplayTheme.NAVY.name))
            }.getOrDefault(CustomerDisplayTheme.NAVY)
            return CustomerDisplayPresentation(
                theme = theme,
                showLogo = json.optBoolean("showLogo", true),
                logoText = json.optString("logoText", "つぐ").take(12),
                textScalePercent = json.optInt("textScalePercent", 100).coerceIn(80, 150),
                maxVisibleRows = json.optInt("maxVisibleRows", 8).coerceIn(3, 20),
                rowSpacingDp = json.optInt("rowSpacingDp", 8).coerceIn(0, 24),
                showCancelledItems = json.optBoolean("showCancelledItems", true),
                showTaxSymbol = json.optBoolean("showTaxSymbol", true),
                standbyMessage = json.optString("standbyMessage", "いらっしゃいませ").take(80),
            )
        }
    }
}
