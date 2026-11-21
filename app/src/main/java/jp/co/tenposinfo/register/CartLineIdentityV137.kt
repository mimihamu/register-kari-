package jp.co.tenposinfo.register

import java.util.UUID

/**
 * Upgrades only row identifiers; never changes totals, quantities, discount,
 * payment or tax attributes. Idempotent once saved into SQLite.
 */
internal object CartLineIdentityV137 {
    fun needsRepair(rows: List<CartItem>): Boolean {
        val seen = HashSet<String>()
        return rows.any { item ->
            val id = item.lineId
            id.isBlank() || id != id.trim() || !seen.add(id)
        }
    }

    fun normalize(
        rows: List<CartItem>,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): List<CartItem> {
        val seen = HashSet<String>()
        return rows.map { row ->
            val id = row.lineId.trim()
            if (id.isNotBlank() && seen.add(id)) {
                if (row.lineId == id) row else row.copy(lineId = id)
            } else {
                var generated: String
                do {
                    generated = newId().trim()
                } while (generated.isEmpty() || !seen.add(generated))
                row.copy(lineId = generated)
            }
        }
    }
}
