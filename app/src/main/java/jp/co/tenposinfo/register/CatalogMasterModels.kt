package jp.co.tenposinfo.register

import java.time.LocalTime

data class DepartmentRecord(
    val id: Long,
    val code: String,
    val name: String,
    val enabled: Boolean,
    val displayOrder: Int,
)

data class ProductGroupRecord(
    val id: Long,
    val code: String,
    val name: String,
    val departmentId: Long?,
    val enabled: Boolean,
    val displayOrder: Int,
)

data class ProductMasterRecord(
    val productId: String,
    val name: String,
    val basePrice: Long,
    val baseTaxCategory: TaxCategory,
    val departmentId: Long?,
    val groupId: Long?,
    val enabled: Boolean,
    val buttonColor: String,
    val pageNo: Int,
    val slotNo: Int,
    val displayOrder: Int,
    val kana: String = "",
    val barcode: String = "",
)

data class TaxMasterRecord(
    val systemKey: String,
    val label: String,
    val ratePercent: Int,
    val priceMode: String,
    val reduced: Boolean,
    val enabled: Boolean,
    val validFrom: String,
    val validTo: String,
    val engineSupported: Boolean,
)

data class SalesProfileRecord(
    val id: Long,
    val code: String,
    val name: String,
    val enabled: Boolean,
    val startMinute: Int,
    val endMinute: Int,
    val priority: Int,
    val isDefault: Boolean,
) {
    val timeLabel: String
        get() = if (startMinute == endMinute) "終日" else "${minuteText(startMinute)}～${minuteText(endMinute)}"

    companion object {
        fun minuteText(minute: Int): String = "%02d:%02d".format((minute / 60) % 24, minute % 60)
    }
}

data class ProductProfileOverride(
    val profileId: Long,
    val productId: String,
    val unitPrice: Long?,
    val taxCategory: TaxCategory?,
)

object SalesProfileSelector {
    fun select(profiles: List<SalesProfileRecord>, minuteOfDay: Int): SalesProfileRecord? {
        val minute = minuteOfDay.coerceIn(0, 1439)
        val enabled = profiles.filter { it.enabled }
        val scheduled = enabled
            .filter { matches(it, minute) }
            .sortedWith(compareByDescending<SalesProfileRecord> { it.priority }.thenBy { it.id })
        return scheduled.firstOrNull() ?: enabled
            .filter { it.isDefault }
            .sortedWith(compareByDescending<SalesProfileRecord> { it.priority }.thenBy { it.id })
            .firstOrNull()
    }

    fun matches(profile: SalesProfileRecord, minuteOfDay: Int): Boolean {
        if (!profile.enabled) return false
        if (profile.startMinute == profile.endMinute) return true
        return if (profile.startMinute < profile.endMinute) {
            minuteOfDay in profile.startMinute until profile.endMinute
        } else {
            minuteOfDay >= profile.startMinute || minuteOfDay < profile.endMinute
        }
    }

    fun currentMinute(now: LocalTime = LocalTime.now()): Int = now.hour * 60 + now.minute
}

object CatalogValidation {
    private val codePattern = Regex("[A-Z0-9_-]{1,20}")

    fun normalizeCode(value: String): String = value.trim().uppercase()

    fun requireCode(value: String, label: String): String {
        val code = normalizeCode(value)
        require(codePattern.matches(code)) { "${label}は英数字・_・-で20文字以内です" }
        return code
    }

    fun requireName(value: String, label: String): String {
        val name = value.trim()
        require(name.isNotBlank()) { "${label}を入力してください" }
        require(name.length <= 60) { "${label}は60文字以内です" }
        return name
    }

    fun normalizeKana(value: String): String {
        val kana = value.trim()
        require(kana.length <= 60) { "かなは60文字以内です" }
        return kana
    }

    fun normalizeBarcode(value: String): String {
        val barcode = value.trim()
        require(barcode.length <= 64) { "バーコードは64文字以内です" }
        require(barcode.none { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) {
            "バーコードに空白・制御文字は使用できません"
        }
        return barcode
    }

    fun parseTime(value: String): Int {
        val parts = value.trim().split(":")
        require(parts.size == 2) { "時刻はHH:mm形式で入力してください" }
        val hour = parts[0].toIntOrNull() ?: error("時刻はHH:mm形式で入力してください")
        val minute = parts[1].toIntOrNull() ?: error("時刻はHH:mm形式で入力してください")
        require(hour in 0..23 && minute in 0..59) { "時刻は00:00～23:59で入力してください" }
        return hour * 60 + minute
    }
}

object ButtonLayoutPolicy {
    const val MIN_PAGE = 1
    const val MAX_PAGE = 9
    const val MIN_SLOT = 1
    const val MAX_SLOT = 24

    fun validate(pageNo: Int, slotNo: Int) {
        require(pageNo in MIN_PAGE..MAX_PAGE) { "ページは1～9です" }
        require(slotNo in MIN_SLOT..MAX_SLOT) { "配置位置は1～24です" }
    }

    fun displayOrder(pageNo: Int, slotNo: Int): Int {
        validate(pageNo, slotNo)
        return (pageNo - 1) * MAX_SLOT + slotNo
    }
}

object TaxMasterCompatibility {
    fun supportedCategory(systemKey: String): TaxCategory? =
        TaxCategory.entries.firstOrNull { it.name == systemKey }

    fun mode(category: TaxCategory): String = when {
        !category.taxable -> "NON_TAXABLE"
        category.taxIncluded -> "INCLUDED"
        else -> "EXCLUDED"
    }
}
