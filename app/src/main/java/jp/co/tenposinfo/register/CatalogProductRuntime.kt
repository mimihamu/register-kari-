package jp.co.tenposinfo.register

import android.content.Context

object CatalogProductRuntime {
    fun visibleProducts(context: Context, products: List<Product>): List<Product> {
        val enabledIds = metadata(context).keys
        if (enabledIds.isEmpty()) return products
        return products.filter { it.id in enabledIds }
    }

    fun metadata(context: Context): Map<String, ProductMasterRecord> = runCatching {
        CatalogMasterStore(context.applicationContext).use { store ->
            store.listProducts(includeDisabled = false).associateBy { it.productId }
        }
    }.getOrDefault(emptyMap())
}
