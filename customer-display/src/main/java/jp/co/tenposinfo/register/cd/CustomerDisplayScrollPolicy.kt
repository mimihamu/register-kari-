package jp.co.tenposinfo.register.cd

object CustomerDisplayScrollPolicy {
    fun targetIndex(items: List<CustomerDisplayOrderItem>): Int {
        if (items.isEmpty()) return -1
        val latestIndex = items.indexOfLast { it.latest }
        return if (latestIndex >= 0) latestIndex else items.lastIndex
    }
}
