package io.github.lnrdo.mytidal.core.stems

/** Adds up the time that a separation spends in each of its stages, for a report such as "read 1.2 s, transform 3.4 s, …". Safe to share between threads. */
class StageClock(private val names: List<String>) {
    private val nanos = LongArray(names.size)

    @Synchronized fun add(stage: Int, ns: Long) { nanos[stage] += ns }

    /** Runs [work] and adds its time to [stage]. */
    inline fun <T> time(stage: Int, work: () -> T): T { val began = System.nanoTime(); try { return work() } finally { add(stage, System.nanoTime() - began) } }

    fun seconds(stage: Int): Double = synchronized(this) { nanos[stage] / 1e9 }

    @Synchronized override fun toString() = names.indices.joinToString(", ") { "${names[it]} ${"%.1f".format(nanos[it] / 1e9)} s" }
}
