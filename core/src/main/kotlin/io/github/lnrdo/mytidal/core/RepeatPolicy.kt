package io.github.lnrdo.mytidal.core

/** Keeps tracks that played a short while ago from coming back soon. */
object RepeatPolicy {
    /** Puts the items that played recently after the others. The order inside each group stays as it is. */
    fun <T> deferRecent(items: List<T>, id: (T) -> String, recent: Set<String>): List<T> {
        if (recent.isEmpty()) return items
        val (old, played) = items.partition { id(it) !in recent }
        return old + played
    }

    /** Leaves out the items that played recently. When nothing else is left, all of them stay, so that a short list never turns empty. */
    fun <T> dropRecent(items: List<T>, id: (T) -> String, recent: Set<String>): List<T> {
        if (recent.isEmpty()) return items
        return items.filter { id(it) !in recent }.ifEmpty { items }
    }
}
