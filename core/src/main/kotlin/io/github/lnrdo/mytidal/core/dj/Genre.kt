package io.github.lnrdo.mytidal.core.dj

/**
 * The broad family of a song's style. D'JAI does not need the exact genre: it needs to know which songs live in the same world, so that a set stays
 * coherent and a jump between worlds is made with care. The family comes from a catalogue when one names the genre, and otherwise it is guessed from what
 * was heard, only when the sound leaves little doubt.
 */
enum class GenreFamily {
    /** House, techno, trance, EDM: a straight four-on-the-floor between 115 and 135. */
    ELECTRONIC,
    /** Drum and bass, jungle, dubstep, breakbeat: fast breaks or half-time weight around 170. */
    BASS,
    /** Hip-hop, rap, trap, R&B: slow, often swung, heavy low end. */
    HIPHOP,
    /** Reggaeton, latin, afro, dancehall, reggae, Brazilian music. */
    LATIN,
    POP,
    /** Rock, metal, alternative, indie, punk. */
    ROCK,
    /** Soul, funk, disco, jazz, blues, gospel. */
    SOUL,
    /** Ambient, classical, acoustic, folk, country, soundtracks, lounge. */
    CHILL;

    companion object {
        // The order matters: the first family whose word is in the name wins, so the more precise words come first.
        private val WORDS = listOf(
            BASS to listOf("drum & bass", "drum and bass", "drum'n'bass", "dnb", "jungle", "dubstep", "breakbeat", "bass music", "grime"),
            HIPHOP to listOf("hip hop", "hip-hop", "hiphop", "rap", "trap", "r&b", "rnb", "drill"),
            LATIN to listOf("reggaeton", "latin", "salsa", "bachata", "cumbia", "dembow", "afro", "dancehall", "reggae", "brazil", "funk carioca", "baile", "merengue", "amapiano"),
            ELECTRONIC to listOf("house", "techno", "trance", "edm", "electro", "dance", "club", "garage", "electronic", "hardstyle"),
            SOUL to listOf("soul", "funk", "disco", "jazz", "blues", "gospel", "motown"),
            ROCK to listOf("rock", "metal", "alternative", "indie", "punk", "grunge", "emo"),
            CHILL to listOf("ambient", "classical", "acoustic", "folk", "country", "soundtrack", "film", "lounge", "chill", "new age", "piano", "singer", "instrumental"),
            POP to listOf("pop", "k-pop", "j-pop", "schlager", "variety"),
        )

        /** The family of a genre as a catalogue names it, or null when the name says nothing that D'JAI knows. */
        fun fromName(name: String?): GenreFamily? {
            val n = name?.lowercase()?.trim().orEmpty()
            if (n.isEmpty()) return null
            for ((family, words) in WORDS) if (words.any { it in n }) return family
            return null
        }

        /**
         * A guess from the sound alone: tempo, groove and energy. Only clear cases are guessed, and anything else is left unknown, so that a wrong guess
         * does not push the choice around. [swing] is where the off-beat falls (0.5 straight), [energy] is from 1 to 10.
         */
        fun guess(bpm: Double?, swing: Double?, energy: Double?): GenreFamily? {
            if (energy != null && energy < 3.2) return CHILL
            if (bpm == null || energy == null) return null
            val straight = swing == null || swing < 0.55
            val swung = swing != null && swing >= 0.57
            return when {
                bpm in 165.0..182.0 && energy >= 6.0 -> BASS
                bpm in 82.0..91.0 && energy >= 7.0 && straight -> BASS // drum and bass read at half
                bpm in 118.0..136.0 && straight && energy >= 6.0 -> ELECTRONIC
                swung && bpm in 68.0..102.0 -> HIPHOP
                else -> null
            }
        }

        /**
         * How naturally a set moves from family [a] to family [b], from 0 to 1: 1 within a family, about 0.7 between neighbours (house and disco, hip-hop
         * and R&B and latin, pop with most), and 0.35 for a jump between worlds. Null when either is not known.
         */
        fun affinity(a: GenreFamily?, b: GenreFamily?): Double? {
            if (a == null || b == null) return null
            if (a == b) return 1.0
            val pair = if (a.ordinal < b.ordinal) a to b else b to a
            return NEIGHBOURS[pair] ?: 0.35
        }

        private val NEIGHBOURS = mapOf(
            (ELECTRONIC to BASS) to 0.6, (ELECTRONIC to HIPHOP) to 0.45, (ELECTRONIC to LATIN) to 0.55, (ELECTRONIC to POP) to 0.7, (ELECTRONIC to SOUL) to 0.65,
            (BASS to HIPHOP) to 0.55,
            (HIPHOP to LATIN) to 0.75, (HIPHOP to POP) to 0.7, (HIPHOP to SOUL) to 0.75,
            (LATIN to POP) to 0.7, (LATIN to SOUL) to 0.55,
            (POP to ROCK) to 0.7, (POP to SOUL) to 0.7, (POP to CHILL) to 0.5,
            (ROCK to SOUL) to 0.55, (ROCK to CHILL) to 0.45,
            (SOUL to CHILL) to 0.6,
        )
    }
}
