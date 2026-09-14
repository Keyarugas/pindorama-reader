package eu.kanade.tachiyomi.ui.download

/** Reordering visible rows must neither drop nor move hidden downloads. */
internal fun <T> preserveHiddenDownloadOrder(original: List<T>, reordered: List<T>, id: (T) -> Long): List<T> {
    val originalIds = original.map(id).toSet()
    val visible = reordered.distinctBy(id).filter { id(it) in originalIds }
    val visibleIds = visible.map(id).toSet()
    val order = visible.iterator()
    return original.map { if (id(it) in visibleIds) order.next() else it }
}
