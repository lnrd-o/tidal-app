package io.github.lnrdo.mytidal.core.dj.neural

import io.github.lnrdo.mytidal.core.dj.MusicGenre
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreHeadTest {
    private val dim = 1024
    private val random = Random(5)

    /** Embeddings that cluster by family: each family lights its own stretch of the 1024 numbers, with noise. */
    private fun song(family: MusicGenre): FloatArray {
        val at = MusicGenre.entries.indexOf(family) * 35
        return FloatArray(dim) { j -> (if (j in at until at + 30) 1.0f else 0.05f) + 0.25f * random.nextFloat() }
    }

    private fun examples(counts: Map<MusicGenre, Int>) = counts.flatMap { (f, n) -> (0 until n).map { GenreExample("$f$it", song(f), f, it % 2 == 0) } }

    @Test fun itSaysNothingUntilItHasSeenEnough() {
        val head = GenreHead(dim)
        assertNull(head.predict(song(MusicGenre.POP)))
        head.train(examples(mapOf(MusicGenre.POP to 5, MusicGenre.ROCK to 4)))
        assertTrue("nine songs are too few", !head.ready)
        assertNull(head.predict(song(MusicGenre.POP)))
        head.train(examples(mapOf(MusicGenre.POP to 12)))
        assertTrue("one family is not enough", !head.ready)
    }

    @Test fun itLearnsTheStylesOfALibraryAndPredictsNewSongs() {
        val head = GenreHead(dim)
        head.train(examples(mapOf(MusicGenre.HIPHOP to 10, MusicGenre.HOUSE to 10, MusicGenre.ROCK to 10, MusicGenre.LATIN to 10)))
        assertTrue(head.ready)
        var right = 0; var total = 0
        for (f in listOf(MusicGenre.HIPHOP, MusicGenre.HOUSE, MusicGenre.ROCK, MusicGenre.LATIN)) repeat(10) {
            total++
            val p = head.predict(song(f))
            if (p != null && p.first == f) right++
        }
        println("right: $right of $total")
        assertTrue("at least 90 percent of new songs: $right of $total", right >= 0.9 * total)
        // A family that it was never shown is not invented.
        val unseen = head.predict(song(MusicGenre.AMBIENT))
        assertTrue("a style it has not seen is not claimed with confidence: $unseen", unseen == null || unseen.first != MusicGenre.AMBIENT)
    }

    @Test fun aNewAnswerIsTakenInWithoutStartingOver() {
        val head = GenreHead(dim)
        val all = examples(mapOf(MusicGenre.POP to 8, MusicGenre.SOUL to 8)).toMutableList()
        head.train(all)
        all.addAll(examples(mapOf(MusicGenre.DNB to 6)))
        head.train(all, warm = true)
        assertEquals(22, head.examples); assertEquals(3, head.classes)
        assertEquals(MusicGenre.DNB, head.predict(song(MusicGenre.DNB))?.first)
        assertEquals(MusicGenre.POP, head.predict(song(MusicGenre.POP))?.first)
    }

    @Test fun itIsSavedAndReadBack() {
        val head = GenreHead(dim)
        head.train(examples(mapOf(MusicGenre.POP to 12, MusicGenre.SOUL to 12, MusicGenre.DNB to 6)))
        val again = GenreHead.fromJson(head.toJson())
        assertNotNull(again)
        assertTrue(again!!.ready)
        val s = song(MusicGenre.SOUL)
        assertEquals(head.predict(s)?.first, again.predict(s)?.first)
        assertNull(GenreHead.fromJson("{\"weights\":[[1,2]]}"))
    }
}
