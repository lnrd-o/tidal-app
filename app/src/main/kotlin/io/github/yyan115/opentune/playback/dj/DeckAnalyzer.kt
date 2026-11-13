package io.github.yyan115.opentune.playback.dj

import io.github.yyan115.opentune.core.dj.BarFinder
import io.github.yyan115.opentune.core.dj.Cue
import io.github.yyan115.opentune.core.dj.SectionAccumulator
import io.github.yyan115.opentune.core.dj.SectionDetector
import io.github.yyan115.opentune.core.dj.Swing
import io.github.yyan115.opentune.core.dj.BeatAnalysis
import io.github.yyan115.opentune.core.dj.BeatTracker
import io.github.yyan115.opentune.core.dj.Energy
import io.github.yyan115.opentune.core.dj.KeyDetector
import io.github.yyan115.opentune.core.dj.KeyResult
import io.github.yyan115.opentune.core.dj.OnsetEnvelope
import io.github.yyan115.opentune.core.dj.PcmRing
import io.github.yyan115.opentune.core.dj.PhraseOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reads the ring of one deck while it plays and works out its tempo grid, where its first phrase begins, its key and its energy.
 * It runs on a background thread, takes new frames every quarter of a second, and estimates from the last 20 seconds every two.
 */
class DeckAnalyzer(
    private val slot: Int, private val scope: CoroutineScope, private val songMs: () -> Long = { -1L },
    /** A full estimate is made every so many seconds of audio. A deck that is read fast makes them less often. */
    private val estimateEvery: Float = 2f,
) {
    /** Counts the full estimates made, so that a caller can wait for the next one. */
    @Volatile var estimates = 0
        private set
    @Volatile private var estimateNow = false

    /** Makes a full estimate at the next opportunity, whatever the pace. */
    fun requestEstimate() { estimateNow = true }

    @Volatile var beat: BeatAnalysis? = null
        private set
    @Volatile var key: KeyResult? = null
        private set
    /** The ring frame of the first beat of the first phrase: the first strong drum hit, snapped to the grid. */
    @Volatile var origin: Double? = null
        private set
    /** The energy of the last 16 seconds, from 1 to 10. For the outgoing track this is its outro. */
    @Volatile var energyRecent: Double? = null
        private set
    /** The energy of the first 16 seconds from the first drum hit. For the incoming track this is its intro. */
    @Volatile var energyIntro: Double? = null
        private set
    /** Seconds of audio that were analysed since the last flush. */
    @Volatile var seconds = 0f
        private set
    /** The rate of the ring that was analysed. */
    @Volatile var rate = 0
        private set
    @Volatile private var restartAt = -1L
    /** Where the off-beat eighth falls in the beat: 0.5 for a straight groove, later for a swung one. Null until it has been read. */
    @Volatile var swing: Double? = null
        private set
    private var swingTick = 0

    /** The whole playthrough in slices of half a second, from which the cue points are found. */
    private var sections: SectionAccumulator? = null
    /** The position of the song, in milliseconds, at which the analysis began, or -1 when it is not known. And the ring frame at that moment. */
    @Volatile private var startedAtMs = -1L
    @Volatile private var startedAtFrame = 0L
    /** The ring frame of the first strong drum hit of the track, as the tracker found it. */
    private var firstHit: Double? = null

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.Default) { loop() }
    }

    fun stop() { job?.cancel(); job = null; clearResults() }

    /** Starts the analysis again from [frame] of the ring, for a new track that began without a flush. */
    fun restart(frame: Long) { restartAt = frame }

    private fun clearResults() { sections?.reset(); swing = null; swingTick = 0; firstHit = null; beat = null; key = null; origin = null; energyRecent = null; energyIntro = null; seconds = 0f }

    private suspend fun loop() {
        var attached: PcmRing? = null
        var envelope: OnsetEnvelope? = null
        var keys: KeyDetector? = null
        var read = 0L
        var lastEstimate = 0f
        var frameCounter = 0
        var forcedStart = -1L
        val mono = FloatArray(48_000 * 2)
        while (scope.isActive) {
            val ring = DjMix.streams[slot].ring
            if (ring == null) { delay(250); continue }
            val request = restartAt
            if (request >= 0) { restartAt = -1; attached = null; forcedStart = request }
            if (ring !== attached || ring.discontinuity > read) {
                attached = ring; rate = ring.rate
                read = if (forcedStart >= 0) maxOf(forcedStart, ring.oldest()) else ring.discontinuity
                forcedStart = -1
                startedAtMs = songMs(); startedAtFrame = read
                val heard = SectionAccumulator(ring.rate).also { sections = it }
                envelope = OnsetEnvelope(ring.rate).also { e ->
                    e.reset(read)
                    e.onFrame = { power -> if (++frameCounter % 4 == 0) keys?.add(power); heard.add(power, e.lastFlux) }
                }
                keys = KeyDetector(ring.rate)
                clearResults(); lastEstimate = 0f
            }
            val e = envelope!!
            val written = ring.written
            read = maxOf(read, ring.oldest())
            val began = read
            while (read < written) {
                val n = minOf((written - read).toInt(), mono.size)
                for (i in 0 until n) mono[i] = (ring.left(read + i) + ring.right(read + i)) * 0.5f
                e.feed(mono, n)
                read += n
            }
            seconds = ((read - e.startSample).toFloat() / ring.rate)
            if (seconds >= 10f && (seconds - lastEstimate >= estimateEvery || estimateNow)) {
                estimateNow = false
                lastEstimate = seconds
                estimate(ring, e, keys!!, read)
            }
            if (read == began) delay(250) else delay(150)
        }
    }

    private fun estimate(ring: PcmRing, e: OnsetEnvelope, keys: KeyDetector, read: Long) {
        try { estimateOnce(ring, e, keys, read) } finally { estimates++ }
    }

    private fun estimateOnce(ring: PcmRing, e: OnsetEnvelope, keys: KeyDetector, read: Long) {
        val fps = ring.rate.toDouble() / OnsetEnvelope.HOP
        val (tail, first) = e.tail((fps * 20).toInt())
        BeatTracker.analyze(tail, e.timeOf(first), ring.rate)?.let { tracked ->
            // The first drum hit is found once. The grid is then moved onto the kick, and the bar is read from the kick and the snare, so that
            // the phrase starts on bar one and not on whatever drum came first.
            if (firstHit == null) e.head((fps * 24).toInt())?.let { head -> firstHit = PhraseOrigin.detect(head, e.timeOf(0), tracked, ring.rate) }
            val hit = firstHit
            val found = BarFinder.refine(ring, tracked, hit ?: ring.oldest().toDouble(), hit)
            beat = found
            // The groove is read when it is not known, and now and then after that: it changes little within a song.
            if (swing == null || ++swingTick % 4 == 0) Swing.measure(ring, found, hit ?: ring.oldest().toDouble())?.let { swing = it }
            // The origin is kept on the newest grid, so that the blocks of 32 beats stay true. It waits for the bar to be known, but not for long.
            val existing = origin
            if (existing == null) { if (hit != null && (found.barKnown || seconds >= 24f)) origin = BarFinder.origin(found, hit) }
            else origin = found.anchorSample + Math.round((existing - found.anchorSample) / found.periodSamples) * found.periodSamples
        }
        keys.result()?.let { key = it }
        val recentFrom = read - 16L * ring.rate
        energyRecent = Energy.score(Energy.rmsDb(ring, recentFrom, read), e.meanFlux(recentFrom.toDouble(), read.toDouble()))
        val start = origin
        if (energyIntro == null && start != null && read - start >= 8.0 * ring.rate) {
            val to = minOf(read.toDouble(), start + 16.0 * ring.rate)
            energyIntro = Energy.score(Energy.rmsDb(ring, start.toLong(), to.toLong()), e.meanFlux(start, to))
        }
    }

    /**
     * The cue points of the song that this deck played, found from its whole playthrough, or an empty list when the playthrough is not whole: the analysis
     * must have begun at the start of the song, with no seek since, and have heard all of it but its last [TAIL_MS] milliseconds.
     */
    fun cues(durationMs: Long): List<Cue> {
        val heard = sections ?: return emptyList()
        val grid = beat ?: return emptyList()
        if (startedAtMs !in 0..START_MS || durationMs <= 0 || heard.seconds * 1000 < durationMs - TAIL_MS) return emptyList()
        val origin = origin?.let { (it - startedAtFrame) / rate.toDouble() }?.coerceAtLeast(0.0) ?: 0.0
        return SectionDetector.detect(heard.slices(), grid.bpm, origin, grid.beatsPerBar).map { Cue(it.kind, it.atMs + startedAtMs) }
    }

    private companion object {
        /** A song counts as played from its start when the analysis began within this many milliseconds of it. */
        const val START_MS = 5_000L
        /** The last part of a song that may be missing, because the mix takes it over. */
        const val TAIL_MS = 100_000L
    }
}
