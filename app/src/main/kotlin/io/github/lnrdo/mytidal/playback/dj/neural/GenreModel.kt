package io.github.lnrdo.mytidal.playback.dj.neural

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.lnrdo.mytidal.core.dj.GenreFamily
import io.github.lnrdo.mytidal.core.dj.neural.GenreVerdict
import io.github.lnrdo.mytidal.core.dj.neural.Resampler
import io.github.lnrdo.mytidal.core.dj.neural.YamnetFeatures
import io.github.lnrdo.mytidal.core.dj.neural.YamnetGenres
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The genre model: YAMNet (Google, Apache 2.0), a small convolutional network that knows 521 sounds of AudioSet, among them pop, hip-hop, rock, house, techno,
 * drum and bass, reggae, salsa, jazz and classical music. It listens to a whole song in patches of about a second, and the scores of the styles, averaged
 * over the song, are voted into the style families of D'JAI ([YamnetGenres]). It runs on the CPU: it is small enough that a song takes about a second.
 */
object GenreModel {
    private const val TAG = "OpenTuneGenreModel"
    /** At most this many patches of a song are read: one every [YamnetFeatures.PATCH_HOP] frames would be 500 for a long song, and 120 spread over it are enough. */
    private const val MAX_PATCHES = 120

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null
    private var features: YamnetFeatures? = null
    private var table: Array<GenreFamily?> = emptyArray()

    @Synchronized
    private fun open(context: Context): OrtSession {
        session?.let { return it }
        val bytes = context.assets.open("yamnet/yamnet.onnx").use { it.readBytes() }
        val mel = context.assets.open("yamnet/yamnet_mel_257x64.bin").use { it.readBytes() }.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer() }
        features = YamnetFeatures(FloatArray(mel.remaining()).also { mel.get(it) })
        val names = context.assets.open("yamnet/yamnet_classes.json").use { it.readBytes().decodeToString() }
            .let { text -> Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(text).map { it.groupValues[1] }.toList() }
        table = YamnetGenres.table(names)
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        return env.createSession(bytes, options).also { session = it }
    }

    /**
     * The style of a song from its mono audio at [rate]. Returns null when the audio is too short to read. The song is read as the network wants it: 16 kHz, in
     * patches of 96 frames of 64 mel bands.
     */
    @Synchronized
    fun analyze(context: Context, mono: FloatArray, rate: Int): GenreVerdict? {
        val s = open(context)
        val f = features ?: return null
        val began = SystemClock.elapsedRealtime()
        val audio = Resampler.resample(mono, rate, YamnetFeatures.SAMPLE_RATE)
        val resampled = SystemClock.elapsedRealtime()
        val (logMel, frames) = f.logMel(audio)
        val featured = SystemClock.elapsedRealtime()
        val count = f.patchCount(frames)
        if (count == 0) return null
        val step = maxOf(1, count / MAX_PATCHES)
        val sum = FloatArray(521); var read = 0
        var index = 0
        while (index < count) {
            val patch = f.patch(logMel, index)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(patch), longArrayOf(1, YamnetFeatures.PATCH.toLong(), YamnetFeatures.BANDS.toLong())).use { input ->
                s.run(mapOf("patch" to input)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val scores = (result.get(0).value as Array<FloatArray>)[0]
                    for (k in sum.indices) sum[k] += scores[k]
                }
            }
            read++; index += step
        }
        for (k in sum.indices) sum[k] /= read
        val verdict = YamnetGenres.decide(sum, table)
        Log.i(TAG, "the genre of a song of %.0f s: %s (%.2f), from %d patches in %d ms (resampling %d, features %d, network %d); %s".format(mono.size.toDouble() / rate, verdict.family ?: "not sure", verdict.confidence, read,
            SystemClock.elapsedRealtime() - began, resampled - began, featured - resampled, SystemClock.elapsedRealtime() - featured, verdict.scores.entries.sortedByDescending { it.value }.take(3).joinToString { "%s %.2f".format(it.key, it.value) }))
        return verdict
    }
}
