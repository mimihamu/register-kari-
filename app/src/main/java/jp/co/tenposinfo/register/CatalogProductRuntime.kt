package jp.co.tenposinfo.register

import android.content.Context

object CatalogProductRuntime {
    fun visibleProducts(context: Context, products: List<Product>): List<Product> {
        val enabledIds = runCatching {
            CatalogMasterStore(context.applicationContext).use { store ->
                store.listProducts(includeDisabled = false).mapTo(linkedSetOf()) { it.productId }
            }
        }.getOrElse { return products }
        return products.filter { it.id in enabledIds }
    }
}
