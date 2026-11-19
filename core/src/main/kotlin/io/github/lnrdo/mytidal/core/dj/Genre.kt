package io.github.lnrdo.mytidal.core.dj

/**
 * The broad family of a genre. D'JAI mixes by family: songs of one family go well together, neighbouring families go together with some care, and a jump
 * between distant families gets the short blend. The genre itself ([MusicGenre]) is what the listener sees.
 */
enum class GenreFamily {
    /** House, techno, trance, EDM: a straight four-on-the-floor between 115 and 135. */
    ELECTRONIC,
    /** Drum and bass, dubstep: fast breaks or half-time weight. */
    BASS,
    /** Hip-hop, trap, R&B: slow, often swung, heavy low end. */
    HIPHOP,
    /** Reggaeton, latin, salsa, reggae, dancehall, afrobeats. */
    LATIN,
    POP,
    /** Rock, indie, punk. */
    ROCK,
    /** Soul, funk, disco. */
    SOUL,
    /** Ambient, classical. */
    CHILL,
    /** Heavy metal. */
    METAL,
    /** Country and folk. */
    COUNTRY,
    /** Jazz and blues. */
    JAZZ
}

/**
 * The genres that D'JAI names, each in the family that it mixes with. The listener sees these, can correct them, and the model learns the ones that no
 * network of sounds knows by name (reggaeton, trap, indie...) from those corrections.
 */
enum class MusicGenre(val family: GenreFamily) {
    POP(GenreFamily.POP),
    DISCO(GenreFamily.SOUL),
    FUNK(GenreFamily.SOUL),
    SOUL(GenreFamily.SOUL),
    RNB(GenreFamily.HIPHOP),
    HIPHOP(GenreFamily.HIPHOP),
    TRAP(GenreFamily.HIPHOP),
    REGGAETON(GenreFamily.LATIN),
    LATIN(GenreFamily.LATIN),
    TROPICAL(GenreFamily.LATIN),
    REGGAE(GenreFamily.LATIN),
    DANCEHALL(GenreFamily.LATIN),
    AFROBEATS(GenreFamily.LATIN),
    HOUSE(GenreFamily.ELECTRONIC),
    TECHNO(GenreFamily.ELECTRONIC),
    TRANCE(GenreFamily.ELECTRONIC),
    EDM(GenreFamily.ELECTRONIC),
    DNB(GenreFamily.BASS),
    DUBSTEP(GenreFamily.BASS),
    ROCK(GenreFamily.ROCK),
    INDIE(GenreFamily.ROCK),
    PUNK(GenreFamily.ROCK),
    METAL(GenreFamily.METAL),
    JAZZ(GenreFamily.JAZZ),
    BLUES(GenreFamily.JAZZ),
    COUNTRY(GenreFamily.COUNTRY),
    FOLK(GenreFamily.COUNTRY),
    CLASSICAL(GenreFamily.CHILL),
    AMBIENT(GenreFamily.CHILL);

    companion object {
        // The order matters: the first genre whose word is in the name wins, so the more precise words come first.
        private val WORDS = listOf(
            REGGAETON to listOf("reggaeton", "reggaetón", "dembow", "urbano", "latin urban", "perreo"),
            DNB to listOf("drum & bass", "drum and bass", "drum'n'bass", "dnb", "jungle", "liquid funk"),
            DUBSTEP to listOf("dubstep", "brostep", "riddim", "bass music", "grime"),
            METAL to listOf("metal", "hardcore", "deathcore", "thrash", "doom"),
            PUNK to listOf("punk", "emo", "pop punk", "ska"),
            INDIE to listOf("indie", "alternative", "shoegaze", "dream pop", "britpop"),
            COUNTRY to listOf("country", "bluegrass", "americana", "honky", "western"),
            FOLK to listOf("folk", "singer-songwriter", "acoustic"),
            BLUES to listOf("blues"),
            JAZZ to listOf("jazz", "swing", "bebop", "big band"),
            TRAP to listOf("trap", "drill", "cloud rap", "phonk"),
            RNB to listOf("r&b", "rnb", "r'n'b", "rhythm and blues", "neo soul", "neo-soul"),
            HIPHOP to listOf("hip hop", "hip-hop", "hiphop", "rap"),
            DANCEHALL to listOf("dancehall", "ragga"),
            REGGAE to listOf("reggae", "dub", "roots"),
            AFROBEATS to listOf("afro", "amapiano", "african", "highlife", "kizomba"),
            TROPICAL to listOf("salsa", "bachata", "cumbia", "merengue", "vallenato", "tropical", "mambo", "bossa", "samba", "brazil", "forró", "forro"),
            LATIN to listOf("latin", "latino", "flamenco", "tango", "mariachi", "regional mexican", "corrido"),
            TECHNO to listOf("techno", "minimal", "industrial", "hard techno"),
            TRANCE to listOf("trance", "psytrance", "goa"),
            HOUSE to listOf("house", "deep house", "tech house", "garage", "disco house"),
            EDM to listOf("edm", "electro", "dance", "club", "electronic", "hardstyle", "big room", "synthwave", "electronica"),
            DISCO to listOf("disco", "nu-disco", "boogie"),
            FUNK to listOf("funk", "motown"),
            SOUL to listOf("soul", "gospel"),
            ROCK to listOf("rock", "grunge", "hard rock", "classic rock", "progressive"),
            AMBIENT to listOf("ambient", "new age", "chill", "lounge", "lo-fi", "lofi", "downtempo", "meditation"),
            CLASSICAL to listOf("classical", "opera", "orchestral", "piano", "baroque", "soundtrack", "film", "score", "symphon"),
            POP to listOf("pop", "k-pop", "j-pop", "schlager", "variety", "top 40"),
        )

        /** The genre of a name as a catalogue gives it, or null when the name says nothing that D'JAI knows. */
        fun fromName(name: String?): MusicGenre? {
            val n = name?.lowercase()?.trim().orEmpty()
            if (n.isEmpty()) return null
            for ((genre, words) in WORDS) if (words.any { it in n }) return genre
            return null
        }

        /**
         * A guess from the sound alone: tempo, groove and energy. Only clear cases are guessed, and anything else is left unknown, so that a wrong guess does
         * not push the choice around. [swing] is where the off-beat falls (0.5 straight), [energy] is from 1 to 10.
         */
        fun guess(bpm: Double?, swing: Double?, energy: Double?): MusicGenre? {
            if (energy != null && energy < 3.2) return AMBIENT
            if (bpm == null || energy == null) return null
            val straight = swing == null || swing < 0.55
            val swung = swing != null && swing >= 0.57
            return when {
                bpm in 165.0..182.0 && energy >= 6.0 -> DNB
                bpm in 82.0..91.0 && energy >= 7.0 && straight -> DNB // drum and bass read at half
                bpm in 118.0..136.0 && straight && energy >= 6.0 -> HOUSE
                swung && bpm in 68.0..102.0 -> HIPHOP
                else -> null
            }
        }

        /**
         * How naturally a set moves from genre [a] to genre [b], from 0 to 1: 1 inside a genre, 0.85 inside a family, then the distance between the families
         * (about 0.7 between neighbours such as house and pop or hip-hop and latin, 0.35 for a jump between worlds). Null when either is not known.
         */
        fun affinity(a: MusicGenre?, b: MusicGenre?): Double? {
            if (a == null || b == null) return null
            if (a == b) return 1.0
            if (a.family == b.family) return 0.85
            return familyAffinity(a.family, b.family)
        }
    }
}

/** How naturally a set moves between two families, from 0 to 1. */
fun familyAffinity(a: GenreFamily, b: GenreFamily): Double {
    if (a == b) return 1.0
    return NEIGHBOURS[a to b] ?: 0.35
}

// The pairs of families that sit next to each other, in no particular order: 1 inside a family, these between neighbours, 0.35 for the rest.
private val NEIGHBOURS: Map<Pair<GenreFamily, GenreFamily>, Double> = buildMap {
    fun near(a: GenreFamily, b: GenreFamily, v: Double) { put(a to b, v); put(b to a, v) }
    near(GenreFamily.ELECTRONIC, GenreFamily.BASS, 0.6); near(GenreFamily.ELECTRONIC, GenreFamily.HIPHOP, 0.45); near(GenreFamily.ELECTRONIC, GenreFamily.LATIN, 0.55)
    near(GenreFamily.ELECTRONIC, GenreFamily.POP, 0.7); near(GenreFamily.ELECTRONIC, GenreFamily.SOUL, 0.65)
    near(GenreFamily.BASS, GenreFamily.HIPHOP, 0.55); near(GenreFamily.BASS, GenreFamily.METAL, 0.45)
    near(GenreFamily.HIPHOP, GenreFamily.LATIN, 0.75); near(GenreFamily.HIPHOP, GenreFamily.POP, 0.7); near(GenreFamily.HIPHOP, GenreFamily.SOUL, 0.75)
    near(GenreFamily.LATIN, GenreFamily.POP, 0.7); near(GenreFamily.LATIN, GenreFamily.SOUL, 0.55)
    near(GenreFamily.POP, GenreFamily.ROCK, 0.7); near(GenreFamily.POP, GenreFamily.SOUL, 0.7); near(GenreFamily.POP, GenreFamily.CHILL, 0.5); near(GenreFamily.POP, GenreFamily.COUNTRY, 0.6)
    near(GenreFamily.ROCK, GenreFamily.SOUL, 0.55); near(GenreFamily.ROCK, GenreFamily.CHILL, 0.45); near(GenreFamily.ROCK, GenreFamily.METAL, 0.8)
    near(GenreFamily.ROCK, GenreFamily.COUNTRY, 0.6); near(GenreFamily.ROCK, GenreFamily.JAZZ, 0.45)
    near(GenreFamily.SOUL, GenreFamily.CHILL, 0.6); near(GenreFamily.SOUL, GenreFamily.JAZZ, 0.8)
    near(GenreFamily.JAZZ, GenreFamily.CHILL, 0.6); near(GenreFamily.COUNTRY, GenreFamily.CHILL, 0.65); near(GenreFamily.COUNTRY, GenreFamily.JAZZ, 0.45)
}
