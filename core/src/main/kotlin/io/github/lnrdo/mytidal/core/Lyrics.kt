package io.github.lnrdo.mytidal.core

import org.json.JSONObject

data class LyricLine(val timeMs: Long, val text: String)

/** Lyrics for one track. [lines] is empty when the provider has no timing data. */
data class Lyrics(
    val lines: List<LyricLine>,
    val plain: String,
    val provider: String = "",
    val rightToLeft: Boolean = false,
) {
    val synced get() = lines.isNotEmpty()
    val isEmpty get() = lines.isEmpty() && plain.isBlank()

    /** Index of the line that is sung at [positionMs], or -1 before the first line. */
    fun lineAt(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var low = 0
        var high = lines.lastIndex
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lines[middle].timeMs <= positionMs) { found = middle; low = middle + 1 } else high = middle - 1
        }
        return found
    }
}

object LyricsParser {
    private val timestamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(json: JSONObject): Lyrics {
        val lines = parseLrc(json.optString("subtitles").takeIf { it != "null" }.orEmpty())
        val plain = json.optString("lyrics").takeIf { it != "null" }.orEmpty().trim()
        return Lyrics(lines, plain.ifBlank { lines.joinToString("\n") { it.text } },
            json.optString("lyricsProvider").takeIf { it != "null" }.orEmpty(), json.optBoolean("isRightToLeft"))
    }

    /** Parses LRC text. A line may carry several timestamps. Metadata tags such as [ar:Name] are ignored. */
    fun parseLrc(text: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        for (raw in text.lineSequence()) {
            val matches = timestamp.findAll(raw).toList()
            if (matches.isEmpty() || matches.first().range.first != 0) continue
            val content = raw.substring(matches.last().range.last + 1).trim()
            for (match in matches) {
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val fraction = match.groupValues[3]
                val millis = when (fraction.length) { 0 -> 0L; 1 -> fraction.toLong() * 100; 2 -> fraction.toLong() * 10; else -> fraction.take(3).toLong() }
                result.add(LyricLine(minutes * 60_000 + seconds * 1000 + millis, content))
            }
        }
        return result.sortedBy(LyricLine::timeMs)
    }
}

data class Contributor(val name: String, val id: String = "")
data class Credit(val role: String, val people: List<Contributor>)

object CreditsParser {
    fun parse(json: JSONObject): List<Credit> {
        val array = json.optJSONArray("items") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val people = entry.optJSONArray("contributors")?.let { list ->
                (0 until list.length()).mapNotNull { position ->
                    list.optJSONObject(position)?.let { person ->
                        person.optString("name").takeIf(String::isNotBlank)?.let { Contributor(it, person.opt("id")?.toString()?.takeIf { id -> id != "null" }.orEmpty()) }
                    }
                }
            }.orEmpty()
            entry.optString("type").takeIf { it.isNotBlank() && people.isNotEmpty() }?.let { Credit(it, people) }
        }
    }
}

object LyricsCodec {
    fun encode(lyrics: Lyrics): String = JSONObject().apply {
        put("plain", lyrics.plain); put("provider", lyrics.provider); put("rtl", lyrics.rightToLeft)
        put("lines", org.json.JSONArray().apply { lyrics.lines.forEach { put(org.json.JSONArray().put(it.timeMs).put(it.text)) } })
    }.toString()

    fun decode(value: String): Lyrics = JSONObject(value).let { json ->
        val array = json.optJSONArray("lines")
        val lines = if (array == null) emptyList() else (0 until array.length()).map { index -> array.getJSONArray(index).let { LyricLine(it.getLong(0), it.getString(1)) } }
        Lyrics(lines, json.optString("plain"), json.optString("provider"), json.optBoolean("rtl"))
    }
}
