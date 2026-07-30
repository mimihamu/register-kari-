package jp.co.tenposinfo.register

import android.content.Context

/**
 * 販売画面のメニュー判定を営業日に固定するv0.11ランタイム。
 * 暦日が変わっても営業終了までは同じ営業日の改定を使用する。
 */
object V11CatalogRuntime {
    fun visibleProducts(context: Context, products: List<Product>): List<Product> = runCatching {
        val appContext = context.applicationContext
        val businessDate = BusinessDateResolver.current(appContext)
        val metadata = CatalogMasterStore(appContext).use { store ->
            store.listProducts(includeDisabled = true).associateBy { it.productId }
        }
        DynamicCatalogStore(appContext).use { store ->
            store.runtimeProducts(products, metadata, businessDate)
        }
    }.getOrElse { products }

    fun status(context: Context): String = runCatching {
        val appContext = context.applicationContext
        val businessDate = BusinessDateResolver.current(appContext)
        CatalogMasterStore(appContext).use { catalog ->
            val profile = catalog.activeProfile()?.name ?: "既定"
            DynamicCatalogStore(appContext).use { dynamic ->
                val revision = dynamic.activeRevision(businessDate)
                if (revision == null) {
                    "営業日 $businessDate / プロファイル：$profile"
                } else {
                    "営業日 $businessDate / 改定：${revision.name}（${revision.effectiveDate}）"
                }
            }
        }
    }.getOrDefault("メニュー読込中")
}
