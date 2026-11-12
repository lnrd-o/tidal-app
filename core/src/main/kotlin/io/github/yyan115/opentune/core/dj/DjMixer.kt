package io.github.yyan115.opentune.core.dj

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/** Where a deck gets its audio. The ring can be replaced, for example when the next track has another sample rate. */
fun interface RingSource { fun ring(): PcmRing? }

/**
 * Mixes two decks into one stream at [outRate]. One thread calls [render]; everything else talks to it through commands,
 * so the state of the mixer is never touched by two threads.
 *
 * Both decks are read by ring position, and the transition is defined by positions too, so the two tracks stay aligned to the frame
 * however the decks are scheduled. When a deck has no data (a pause, a stall), both decks wait, and the transition does not advance.
 */
class DjMixer(val outRate: Int = 48_000) {
    private class Deck {
        var source: RingSource? = null
        var ring: PcmRing? = null
        var pos = 0.0
        var gain = 1f
        var level = 0f
        var starving = true
    }

    private val decks = arrayOf(Deck(), Deck())
    private val commands = ConcurrentLinkedQueue<Runnable>()
    private val eq = arrayOf(ThreeBandEq(outRate), ThreeBandEq(outRate))
    /** The filter sweep of a transition: a high-pass on the outgoing track and a low-pass that opens on the incoming one. */
    private val sweepOut = SweepFilter(outRate, highpass = true)
    private val sweepIn = SweepFilter(outRate, highpass = false)
    /** Tone Lab: studio glue, tape and vinyl, applied to everything that is heard. */
    val elzar = ElZar(outRate)

    /** Sets Tone Lab: its switch, the amount and kind of each stage, and the bypass. */
    fun setElZar(config: ElZarConfig) = post { elzar.apply(config) }

    fun setElZar(on: Boolean, glue: Float, tape: Float, vinyl: Float, flavor: Flavor = Flavor.STUDIO) =
        setElZar(ElZarConfig(on = on, glue = glue, tape = tape, vinyl = vinyl, flavor = flavor))

    /** Turns the filter sweep on or off. */
    @Volatile var filters = true
    private val bufA = FloatArray(BLOCK * 2)
    private val bufB = FloatArray(BLOCK * 2)
    private val g0 = DoubleArray(6)
    private val g1 = DoubleArray(6)
    private var plan: MixPlan? = null
    private var mixFrame = -1

    /** The deck that is heard when no transition runs. */
    @Volatile var primary = 0
        private set
    @Volatile var mixing = false
        private set
    /** The progress of the running transition from 0 to 1. */
    @Volatile var progress = 0f
        private set
    /** Counts the transitions that ended, so that a poller sees each one. */
    @Volatile var finishedSeq = 0
        private set
    /** The gains of the last block: outgoing low, mid, high, then incoming low, mid, high. */
    val liveGains = FloatArray(6)
    @Volatile var position0 = 0.0
        private set
    @Volatile var position1 = 0.0
        private set

    fun position(slot: Int) = if (slot == 0) position0 else position1

    private fun post(action: () -> Unit) { commands.add(Runnable(action)) }
    fun setSource(slot: Int, source: RingSource?) = post { val d = decks[slot]; d.source = source; if (source == null) { d.ring = null; d.level = 0f; d.starving = true } }
    fun setGain(slot: Int, gain: Float) = post { decks[slot].gain = gain }
    fun setPrimary(slot: Int) = post { primary = slot }
    fun setPlan(next: MixPlan?) = post { plan = next; mixFrame = -1; mixing = false; progress = 0f }
    /** Ends the plan without finishing it. The deck [primarySlot] is heard afterwards. */
    fun cancel(primarySlot: Int) = post { plan = null; mixFrame = -1; mixing = false; progress = 0f; primary = primarySlot }

    fun render(out: FloatArray, frames: Int) {
        while (true) { (commands.poll() ?: break).run() }
        var done = 0
        while (done < frames) {
            var n = min(BLOCK, frames - done)
            val p = plan
            if (p != null && mixFrame < 0) {
                val a = decks[p.outgoing]
                val ring = refresh(a)
                if (ring != null) {
                    val remain = p.startOutgoing - a.pos
                    if (remain <= 0.0 && !a.starving) startMix(p)
                    else if (!a.starving) n = min(n, max(1, ceil(remain / (ring.rate.toDouble() / outRate)).toInt()))
                }
            }
            val running = plan
            if (running != null && mixFrame >= 0) n = min(n, running.lengthOut - mixFrame)
            renderBlock(out, done * 2, n)
            done += n
        }
    }

    private fun startMix(p: MixPlan) {
        mixFrame = 0
        mixing = true
        sweepOut.reset(); sweepIn.reset()
        val b = decks[p.incoming]
        refresh(b)
        b.pos = p.startIncoming
        b.starving = false
    }

    /** Keeps the deck attached to the current ring of its source and moves its position past flushes and overwritten frames. */
    private fun refresh(d: Deck): PcmRing? {
        val r = d.source?.ring()
        if (r !== d.ring) { d.ring = r; if (r != null) { d.pos = r.discontinuity.toDouble(); d.starving = true } }
        if (r != null) {
            if (r.discontinuity > d.pos) { d.pos = r.discontinuity.toDouble(); d.starving = true }
            val oldest = r.oldest()
            if (d.pos < oldest) { d.pos = max(oldest.toDouble(), r.written - CUSHION_SECONDS * r.rate); d.starving = true }
        }
        return r
    }

    private fun ready(d: Deck, r: PcmRing, step: Double, n: Int): Boolean {
        val avail = r.written - d.pos
        val need = n * step + 3.0
        return if (d.starving) avail >= need + CUSHION_SECONDS * r.rate else avail >= need
    }

    /** Reads [n] frames at [step] ring frames per output frame with cubic interpolation. */
    private fun fetch(d: Deck, r: PcmRing, step: Double, n: Int, buf: FloatArray) {
        var pos = d.pos
        for (i in 0 until n) {
            val base = floor(pos).toLong()
            val t = (pos - base).toFloat()
            val i0 = if (base > 0) base - 1 else 0L
            val l0 = r.left(i0); val l1 = r.left(base); val l2 = r.left(base + 1); val l3 = r.left(base + 2)
            val r0 = r.right(i0); val r1 = r.right(base); val r2 = r.right(base + 1); val r3 = r.right(base + 2)
            buf[i * 2] = cubic(l0, l1, l2, l3, t)
            buf[i * 2 + 1] = cubic(r0, r1, r2, r3, t)
            pos += step
        }
        d.pos = pos
    }

    private fun cubic(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val a = -0.5f * p0 + 1.5f * p1 - 1.5f * p2 + 0.5f * p3
        val b = p0 - 2.5f * p1 + 2f * p2 - 0.5f * p3
        val c = -0.5f * p0 + 0.5f * p2
        return ((a * t + b) * t + c) * t + p1
    }

    private fun renderBlock(out: FloatArray, offset: Int, n: Int) {
        val p = plan
        val running = p != null && mixFrame >= 0
        val first = if (running) decks[p!!.outgoing] else decks[primary]
        val ringA = refresh(first)
        val second = if (running) decks[p!!.incoming] else null
        val ringB = if (second != null) refresh(second) else null
        val stepA = if (ringA != null) ringA.rate.toDouble() / outRate else 1.0
        var u0 = 0.0; var u1 = 0.0; var speed = 1.0
        if (running) {
            val len = p!!.lengthOut.toDouble()
            u0 = mixFrame / len; u1 = (mixFrame + n) / len
            speed = MixCurves.speed((u0 + u1) / 2, p.speedIncoming, p.swap)
        }
        val stepB = if (ringB != null) ringB.rate.toDouble() / outRate * speed else 1.0

        var okA = ringA != null && ready(first, ringA, stepA, n)
        var okB = true
        if (running) {
            okB = ringB != null && ready(second!!, ringB, stepB, n)
            if (!(okA && okB)) { okA = false; okB = false }
        }
        if (okA) { first.starving = false; fetch(first, ringA!!, stepA, n, bufA) } else first.starving = true
        if (running) { if (okB) { second!!.starving = false; fetch(second, ringB!!, stepB, n, bufB) } else second!!.starving = true }

        val warm = p != null && !running && ringA != null && p.startOutgoing - first.pos < WARM_SECONDS * ringA.rate
        val plain = running && p!!.kind == MixKind.CROSSFADE
        if (plain) { MixCurves.crossfade(u0, g0); MixCurves.crossfade(u1, g1) }
        else if (running) {
            MixCurves.gains(u0, p!!.swap, g0); MixCurves.gains(u1, p.swap, g1)
            val middle = (u0 + u1) / 2
            if (filters) { sweepOut.setCutoff(MixCurves.highpassHz(middle, p.swap)); sweepIn.setCutoff(MixCurves.lowpassHz(middle, p.swap)) }
            else { sweepOut.setCutoff(0.0); sweepIn.setCutoff(1e9) }
        }
        val eqA = eq[if (running) p!!.outgoing else primary]
        val eqB = if (running) eq[p!!.incoming] else null
        val targetA = if (okA) 1f else 0f
        val targetB = if (okB && running) 1f else 0f
        for (i in 0 until n) {
            first.level += (targetA - first.level) * SMOOTH
            var l = 0f; var r = 0f
            if (first.level > 0.0001f || okA) {
                val xl = (if (okA) bufA[i * 2] else 0f) * first.gain * first.level
                val xr = (if (okA) bufA[i * 2 + 1] else 0f) * first.gain * first.level
                if (running) {
                    val f = (i + 1).toDouble() / n
                    if (plain) { val ga = lerp(g0[0], g1[0], f).toFloat(); l += xl * ga; r += xr * ga }
                    else {
                        l += sweepOut.process(0, eqA.processLeft(xl, lerp(g0[0], g1[0], f), lerp(g0[1], g1[1], f), lerp(g0[2], g1[2], f)))
                        r += sweepOut.process(1, eqA.processRight(xr, lerp(g0[0], g1[0], f), lerp(g0[1], g1[1], f), lerp(g0[2], g1[2], f)))
                    }
                } else {
                    if (warm) { eqA.processLeft(xl, 1.0, 1.0, 1.0); eqA.processRight(xr, 1.0, 1.0, 1.0) }
                    l += xl; r += xr
                }
            }
            if (running) {
                second!!.level += (targetB - second.level) * SMOOTH
                val xl = (if (okB) bufB[i * 2] else 0f) * second.gain * second.level
                val xr = (if (okB) bufB[i * 2 + 1] else 0f) * second.gain * second.level
                val f = (i + 1).toDouble() / n
                if (plain) { val gb = lerp(g0[3], g1[3], f).toFloat(); l += xl * gb; r += xr * gb }
                else {
                    l += sweepIn.process(0, eqB!!.processLeft(xl, lerp(g0[3], g1[3], f), lerp(g0[4], g1[4], f), lerp(g0[5], g1[5], f)))
                    r += sweepIn.process(1, eqB.processRight(xr, lerp(g0[3], g1[3], f), lerp(g0[4], g1[4], f), lerp(g0[5], g1[5], f)))
                }
            }
            out[offset + i * 2] = l
            out[offset + i * 2 + 1] = r
        }
        // Tone Lab colours the music, not the silence of a pause, and the limiter keeps the result inside full scale.
        // Without a transition only the first deck can be heard, so a dry deck means silence.
        val heard = if (running) okA || okB else okA
        if (heard && elzar.active) elzar.processBlock(out, offset / 2, n)
        for (k in offset until offset + n * 2) out[k] = limit(out[k])

        if (running && okA && okB) {
            mixFrame += n
            for (k in 0 until 6) liveGains[k] = g1[k].toFloat()
            progress = (mixFrame.toDouble() / p!!.lengthOut).toFloat().coerceIn(0f, 1f)
            if (mixFrame >= p.lengthOut) {
                primary = p.incoming; plan = null; mixFrame = -1; mixing = false; progress = 1f
                decks[p.outgoing].level = 0f
                eq[p.outgoing].reset(); eq[p.incoming].reset(); sweepOut.reset(); sweepIn.reset()
                finishedSeq++
            }
        }
        position0 = decks[0].pos; position1 = decks[1].pos
    }

    private fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f

    /** Keeps the peaks inside full scale without touching anything below 0.98. */
    private fun limit(x: Float): Float {
        val a = if (x < 0) -x else x
        if (a <= KNEE) return x
        val soft = KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE))
        return if (x < 0) -soft else soft
    }

    companion object {
        const val BLOCK = 64
        const val CUSHION_SECONDS = 0.12
        const val WARM_SECONDS = 4.0
        private const val SMOOTH = 0.004f
        private const val KNEE = 0.98f
    }
}
