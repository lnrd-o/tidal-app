package io.github.lnrdo.mytidal.core

/** One page of a list from TIDAL: its items and, when the answer says so, how many the whole list holds. */
class PageOf<T>(val items: List<T>, val total: Int?)

/**
 * Reads a list page by page until it ends. A page can hold fewer items than were asked for while more follow (TIDAL leaves out some items of a page),
 * so a short page does not end the list: it ends when a page is empty or when the total that TIDAL reported is reached. [limit] guards against an endless list.
 */
suspend fun <T> collectPages(limit: Int = 2_000, page: suspend (offset: Int) -> PageOf<T>): List<T> {
    val result = ArrayList<T>()
    var offset = 0
    repeat(limit) {
        val next = page(offset)
        if (next.items.isEmpty()) return result
        result.addAll(next.items)
        offset += next.items.size
        if (next.total != null && offset >= next.total) return result
    }
    return result
}
