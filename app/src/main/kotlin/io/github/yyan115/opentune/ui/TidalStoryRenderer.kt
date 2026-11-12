package io.github.yyan115.opentune.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import io.github.yyan115.opentune.R
import io.github.yyan115.opentune.core.Credit
import io.github.yyan115.opentune.core.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The twelve layouts of the story card. The last three tell the credits of the song. */
enum class StoryLayout(val titleRes: Int, val credits: Boolean = false) {
    CENTERED(R.string.story_layout_1), AURA(R.string.story_layout_2), NOIR(R.string.story_layout_3),
    MINI(R.string.story_layout_4), BACKDROP(R.string.story_layout_5), CARD(R.string.story_layout_6),
    GLOW(R.string.story_layout_7), SLATE(R.string.story_layout_8), SPLIT(R.string.story_layout_9),
    LINER(R.string.story_layout_10, true), SLEEVE(R.string.story_layout_11, true), POSTER(R.string.story_layout_12, true)
}

/** What the credit layouts need besides the track: its release date and who made it. */
class StoryDetails(val releaseDate: String? = null, val credits: List<Credit> = emptyList()) {
    val isEmpty: Boolean get() = releaseDate == null && credits.isEmpty()
}

/**
 * Draws story cards and shares them. Rendering never throws: a missing cover gives a plain card, and a hardware bitmap is copied before it is drawn.
 *
 * Every layout is a vertical stack of blocks inside a safe zone, because Instagram covers the top and the bottom of a story with its own controls.
 * The text is measured and shrinks to fit its width, and the cover takes whatever height is left, so nothing can overlap or run off the card.
 */
object TidalStoryRenderer {
    const val STORY_WIDTH = 1080
    const val STORY_HEIGHT = 1920
    private const val W = STORY_WIDTH.toFloat()
    private const val H = STORY_HEIGHT.toFloat()
    private const val SIDE = 96f
    private const val CONTENT = W - 2 * SIDE
    private const val TOP = 250f
    private const val BOTTOM = 1670f
    private const val GOLD = 0xFFE3C07A.toInt()
    private const val GRAY = 0xFF9A9A9A.toInt()

    private class Ctx(
        val context: Context, val canvas: Canvas, val art: Bitmap?, val track: Track, val tint: Int, val chips: List<String>, val maxLabel: String,
        val face: Typeface, val mono: Typeface, val details: StoryDetails,
    )

    /** A piece of a row, with a size and a way to draw itself at a position. */
    private class Part(val width: Float, val height: Float, val draw: (x: Float, y: Float) -> Unit)
    /** A piece of a vertical stack. [gapAfter] is the space below it, which the last block does not use. */
    private class Block(val height: Float, val gapAfter: Float, val draw: (top: Float) -> Unit)

    suspend fun generateStoryCard(context: Context, track: Track, quality: String, layout: StoryLayout = StoryLayout.CENTERED, details: StoryDetails = StoryDetails()): Bitmap =
        withContext(Dispatchers.Default) {
            val bitmap = Bitmap.createBitmap(STORY_WIDTH, STORY_HEIGHT, Bitmap.Config.ARGB_8888)
            val art = loadArtworkBitmap(context, track.artwork.orEmpty())
            val readout = SourceReadout.from(quality)
            val chips = when (qualityTier(quality)) {
                QualityTier.MAX -> listOf(context.getString(R.string.badge_max), context.getString(R.string.badge_hifi))
                QualityTier.HIFI -> listOf(context.getString(R.string.badge_hifi))
                QualityTier.LOSSY -> listOf(readout.codec)
                QualityTier.UNKNOWN -> emptyList()
            }
            val base = ResourcesCompat.getFont(context, R.font.dm_sans) ?: Typeface.SANS_SERIF
            val mono = ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
            val c = Ctx(context, Canvas(bitmap), art, track, art?.let(::dominant) ?: 0xFF3A3A3A.toInt(), chips, context.getString(R.string.badge_max), base, mono, details)
            when (layout) {
                StoryLayout.CENTERED -> centered(c); StoryLayout.AURA -> aura(c); StoryLayout.NOIR -> noir(c)
                StoryLayout.MINI -> mini(c); StoryLayout.BACKDROP -> backdrop(c); StoryLayout.CARD -> card(c)
                StoryLayout.GLOW -> glow(c); StoryLayout.SLATE -> slate(c); StoryLayout.SPLIT -> split(c)
                StoryLayout.LINER -> liner(c); StoryLayout.SLEEVE -> sleeve(c); StoryLayout.POSTER -> poster(c)
            }
            bitmap
        }

    // ---- Layouts ----

    private fun centered(c: Ctx) {
        c.canvas.drawColor(0xFF0B0B0C.toInt())
        c.art?.let { backdropImage(c.canvas, it, .5f, 0f, 1100f) }
        gradient(c.canvas, 760f, 1300f, 0x000B0B0C, 0xFF0B0B0C.toInt())
        val title = fit(c, c.track.title, 66f, 700, Color.WHITE, CONTENT, Layout.Alignment.ALIGN_CENTER, 2)
        val artist = fit(c, c.track.artist, 42f, 400, GRAY, CONTENT, Layout.Alignment.ALIGN_CENTER, 1)
        val blocks = coverStack(TOP, BOTTOM, 380f, 760f) { size -> listOf(
            coverBlock(c, size, 64f, center = true, radius = 6f, shadow = false),
            textBlock(c, title, 16f), textBlock(c, artist, 52f),
            rowBlock(c, listOf(chipsPart(c)), 20f, Align.CENTER, 60f), rowBlock(c, listOf(logoPart(c)), 0f, Align.CENTER, 0f)) }
        stack(blocks, TOP, BOTTOM)
    }

    private fun aura(c: Ctx) {
        c.canvas.drawColor(shade(c.tint, .18f))
        c.art?.let { backdropImage(c.canvas, it, .95f, 0f, H) }
        gradient(c.canvas, 0f, H, 0x66000000, 0x66000000)
        gradient(c.canvas, 900f, H, 0x00000000, 0xF2000000.toInt())
        val line = fit(c, c.chips.joinToString("   |   "), 30f, 700, 0xFFE8D9B0.toInt(), CONTENT, Layout.Alignment.ALIGN_CENTER, 1, spacing = .12f)
        val title = fit(c, c.track.title, 72f, 700, 0xFFE6E6E6.toInt(), CONTENT, Layout.Alignment.ALIGN_CENTER, 2)
        val artist = fit(c, c.track.artist, 44f, 400, 0xFFA0A0A0.toInt(), CONTENT, Layout.Alignment.ALIGN_CENTER, 1)
        val blocks = coverStack(TOP, BOTTOM, 400f, 800f) { size ->
            (if (c.chips.isNotEmpty()) listOf(textBlock(c, line, 56f)) else emptyList()) +
                listOf(coverBlock(c, size, 60f, center = true, radius = 8f, shadow = true), textBlock(c, title, 14f), textBlock(c, artist, 56f),
                    rowBlock(c, listOf(logoPart(c)), 0f, Align.CENTER, 0f)) }
        stack(blocks, TOP, BOTTOM)
    }

    private fun noir(c: Ctx) {
        c.canvas.drawColor(0xFF08040A.toInt())
        gradient(c.canvas, 0f, 900f, (shade(c.tint, .22f) and 0x00FFFFFF) or 0x55000000, 0x00000000)
        val title = fit(c, c.track.title, 62f, 700, 0xFFD0D0D0.toInt(), CONTENT, Layout.Alignment.ALIGN_CENTER, 2)
        val artist = fit(c, c.track.artist, 42f, 400, 0xFF8C8C8C.toInt(), CONTENT, Layout.Alignment.ALIGN_CENTER, 1)
        val blocks = coverStack(TOP, BOTTOM, 380f, 760f) { size -> listOf(
            coverBlock(c, size, 64f, center = true, radius = 6f, shadow = true), textBlock(c, title, 14f), textBlock(c, artist, 48f),
            rowBlock(c, listOfNotNull(c.chips.firstOrNull()?.let { pillPart(c, it, toneOf(c, it)) }), 0f, Align.CENTER, 56f),
            rowBlock(c, listOf(logoPart(c)), 0f, Align.CENTER, 0f)) }
        stack(blocks, TOP, BOTTOM)
    }

    private fun mini(c: Ctx) {
        c.canvas.drawColor(0xFF050B0C.toInt())
        gradient(c.canvas, 0f, 1000f, 0x22000000 or (shade(c.tint, .5f) and 0x00FFFFFF), 0x00000000)
        val title = fit(c, c.track.title, 56f, 700, 0xFFCFCFCF.toInt(), CONTENT, Layout.Alignment.ALIGN_CENTER, 2)
        val artist = fit(c, c.track.artist, 38f, 400, 0xFF8A8A8A.toInt(), CONTENT, Layout.Alignment.ALIGN_CENTER, 1)
        val bottom = rowBlock(c, listOfNotNull(logoPart(c), c.chips.firstOrNull()?.let { chipPart(c, it, toneOf(c, it), false) }), 56f, Align.CENTER, 0f)
        val areaBottom = BOTTOM - bottom.height - 70f
        val blocks = coverStack(TOP, areaBottom, 300f, 460f) { size -> listOf(coverBlock(c, size, 56f, center = true, radius = 4f, shadow = true), textBlock(c, title, 12f), textBlock(c, artist, 0f)) }
        stack(blocks, TOP, areaBottom)
        bottom.draw(BOTTOM - bottom.height)
    }

    private fun backdrop(c: Ctx) {
        c.canvas.drawColor(0xFF060606.toInt())
        c.art?.let { backdropImage(c.canvas, it, 1f, 0f, 1450f) }
        gradient(c.canvas, 0f, 1450f, 0x33000000, 0x22000000)
        gradient(c.canvas, 1000f, 1700f, 0x00060606, 0xFF060606.toInt())
        val title = fit(c, c.track.title, 88f, 700, Color.WHITE, CONTENT, Layout.Alignment.ALIGN_CENTER, 2)
        val artist = fit(c, c.track.artist, 52f, 400, GRAY, CONTENT, Layout.Alignment.ALIGN_CENTER, 1)
        val badges = listOfNotNull(c.chips.lastOrNull()?.let { chipPart(c, it, Color.WHITE, true) }, diamondPart(c))
        val blocks = coverStack(TOP, BOTTOM, 480f, 800f) { size -> listOf(
            rowBlock(c, badges, 18f, Align.CENTER, 56f), coverBlock(c, size, 60f, center = true, radius = 6f, shadow = true), textBlock(c, title, 14f), textBlock(c, artist, 0f)) }
        stack(blocks, TOP, BOTTOM)
    }

    private fun card(c: Ctx) {
        gradient(c.canvas, 0f, H, 0xFF2B2B2D.toInt(), 0xFF19191B.toInt())
        val half = 600f
        val title = fit(c, c.track.title, 76f, 700, 0xFFDADADA.toInt(), half, Layout.Alignment.ALIGN_NORMAL, 3)
        val artist = fit(c, c.track.artist, 48f, 400, 0xFF8E8E90.toInt(), half, Layout.Alignment.ALIGN_NORMAL, 2)
        val titleRow = Block(title.height.toFloat(), 14f) { y ->
            c.canvas.save(); c.canvas.translate(SIDE, y); title.draw(c.canvas); c.canvas.restore()
            val logo = logoStackedPart(c); logo.draw(W - SIDE - logo.width, y + 6f)
        }
        val blocks = coverStack(TOP, BOTTOM, 420f, CONTENT) { size -> listOf(
            coverBlock(c, size, 56f, center = false, radius = 14f, shadow = true), titleRow, textBlock(c, artist, 48f, x = SIDE),
            rowBlock(c, listOfNotNull(if (c.chips.isNotEmpty()) chipPart(c, c.chips.joinToString(" • "), 0xFFBFBFC2.toInt(), true, grey = true) else null), 0f, Align.LEFT, 0f)) }
        stack(blocks, TOP, BOTTOM)
    }

    private fun glow(c: Ctx) {
        c.canvas.drawColor(0xFF0A0505.toInt())
        c.art?.let { backdropImage(c.canvas, it, .9f, 0f, H) }
        gradient(c.canvas, 0f, H, 0x44000000, 0x99000000.toInt())
        gradient(c.canvas, 1000f, H, 0x00000000, 0xEE000000.toInt())
        val title = fit(c, c.track.title, 66f, 700, Color.WHITE, CONTENT, Layout.Alignment.ALIGN_CENTER, 2)
        val artist = fit(c, c.track.artist, 44f, 400, 0xFFA8A8A8.toInt(), CONTENT, Layout.Alignment.ALIGN_CENTER, 1)
        val blocks = coverStack(TOP, BOTTOM, 400f, 740f) { size -> listOf(
            coverBlock(c, size, 64f, center = true, radius = 8f, shadow = true), textBlock(c, title, 14f), textBlock(c, artist, 56f),
            rowBlock(c, listOfNotNull(logoPart(c), c.chips.firstOrNull()?.let { chipPart(c, it, toneOf(c, it), false) }), 48f, Align.CENTER, 0f)) }
        stack(blocks, TOP, BOTTOM)
    }

    private fun slate(c: Ctx) {
        gradient(c.canvas, 0f, H, 0xFF273034.toInt(), 0xFF171C1F.toInt())
        val title = fit(c, c.track.title, 78f, 700, 0xFFE8E8E8.toInt(), CONTENT, Layout.Alignment.ALIGN_CENTER, 2)
        val artist = fit(c, c.track.artist, 48f, 400, 0xFF9AA2A6.toInt(), CONTENT, Layout.Alignment.ALIGN_CENTER, 1)
        val blocks = coverStack(TOP, BOTTOM, 420f, 780f) { size -> listOf(
            rowBlock(c, listOf(logoPart(c)), 0f, Align.CENTER, 64f), coverBlock(c, size, 64f, center = true, radius = 6f, shadow = true),
            textBlock(c, title, 14f), textBlock(c, artist, 52f),
            rowBlock(c, listOfNotNull(c.chips.lastOrNull()?.let { chipPart(c, it, 0xFFE0E0E0.toInt(), true, grey = true) }), 0f, Align.CENTER, 0f)) }
        stack(blocks, TOP, BOTTOM)
    }

    private fun split(c: Ctx) {
        c.canvas.drawColor(shade(c.tint, .16f))
        c.art?.let { backdropImage(c.canvas, it, .8f, 0f, H) }
        gradient(c.canvas, 0f, H, 0x66000000, 0xCC000000.toInt())
        c.canvas.drawRect(0f, 0f, 380f, H, Paint().apply { color = (shade(c.tint, .55f) and 0x00FFFFFF) or 0xE6000000.toInt() })
        val title = fit(c, c.track.title, 76f, 700, Color.WHITE, CONTENT, Layout.Alignment.ALIGN_NORMAL, 2)
        val artist = fit(c, c.track.artist, 50f, 400, 0xFFB0B0B0.toInt(), CONTENT, Layout.Alignment.ALIGN_NORMAL, 1)
        val footer = Block(56f, 0f) { y ->
            val logo = logoPart(c); logo.draw(SIDE, y)
            val chips = chipsPart(c); chips.draw(W - SIDE - chips.width, y)
        }
        val blocks = coverStack(TOP, BOTTOM, 400f, 780f) { size -> listOf(
            // The cover starts inside the coloured panel and reaches across its edge.
            Block(size, 56f) { y -> drawCover(c, 170f, y, size, 6f, true) }, textBlock(c, title, 14f, x = SIDE), textBlock(c, artist, 56f, x = SIDE), footer) }
        stack(blocks, TOP, BOTTOM)
    }

    /** Liner notes: the cover and the title at the top, then when it was released and who made it. */
    private fun liner(c: Ctx) {
        c.canvas.drawColor(0xFF0A0A0B.toInt())
        c.art?.let { backdropImage(c.canvas, it, .42f, 0f, H) }
        gradient(c.canvas, 0f, H, 0x99000000.toInt(), 0xF2000000.toInt())
        val size = 300f
        val gapAfterHeader = 64f
        val textWidth = CONTENT - size - 48f
        val title = fit(c, c.track.title, 56f, 700, Color.WHITE, textWidth, Layout.Alignment.ALIGN_NORMAL, 3)
        val artist = fit(c, c.track.artist, 38f, 400, 0xFFB0B0B0.toInt(), textWidth, Layout.Alignment.ALIGN_NORMAL, 2)
        val header = Block(maxOf(size, title.height + 14f + artist.height.toFloat()), gapAfterHeader) { y ->
            drawCover(c, SIDE, y, size, 6f, true)
            val textHeight = title.height + 14f + artist.height
            val ty = y + (size - textHeight).coerceAtLeast(0f) / 2f
            c.canvas.save(); c.canvas.translate(SIDE + size + 48f, ty); title.draw(c.canvas); c.canvas.restore()
            c.canvas.save(); c.canvas.translate(SIDE + size + 48f, ty + title.height + 14f); artist.draw(c.canvas); c.canvas.restore()
        }
        val logo = rowBlock(c, listOf(logoPart(c)), 0f, Align.LEFT, 0f)
        val room = BOTTOM - TOP - header.height - header.gapAfter - logo.height - 60f
        val rest = dateBlocks(c, CONTENT, 24f, 46f, Layout.Alignment.ALIGN_NORMAL, SIDE) + creditBlocks(c, CONTENT, SIDE, Layout.Alignment.ALIGN_NORMAL, 22f, 38f, room - 150f, 2)
        stack(listOf(header) + rest.withGap(34f) + listOf(Block(0f, 60f) {}) + logo, TOP, BOTTOM, alignTop = true)
    }

    /** A record sleeve: a big cover, the release date in a line under the title, and the credits in two columns of label and names. */
    private fun sleeve(c: Ctx) {
        c.canvas.drawColor(shade(c.tint, .22f))
        c.art?.let { backdropImage(c.canvas, it, .35f, 0f, H) }
        gradient(c.canvas, 0f, H, 0x55000000, 0xEE000000.toInt())
        val title = fit(c, c.track.title, 60f, 700, Color.WHITE, CONTENT, Layout.Alignment.ALIGN_CENTER, 2)
        val artist = fit(c, c.track.artist, 38f, 400, 0xFFB8B8B8.toInt(), CONTENT, Layout.Alignment.ALIGN_CENTER, 1)
        val date = releaseDateText(c)
        val dateBlock = date?.let { d -> textBlock(c, fit(c, d, 30f, 500, GOLD, CONTENT, Layout.Alignment.ALIGN_CENTER, 1, spacing = .16f, mono = true), 40f) }
        val credits = rows(c).take(5)
        val rowsBlock = Block(credits.sumOf { (60f).toDouble() }.toFloat(), 0f) { y ->
            credits.forEachIndexed { i, (label, value) ->
                val l = fit(c, label, 24f, 600, GRAY, CONTENT * .36f, Layout.Alignment.ALIGN_NORMAL, 1, spacing = .12f)
                val v = fit(c, value, 34f, 500, Color.WHITE, CONTENT * .6f, Layout.Alignment.ALIGN_OPPOSITE, 1)
                val top = y + i * 60f
                c.canvas.save(); c.canvas.translate(SIDE, top + 8f); l.draw(c.canvas); c.canvas.restore()
                c.canvas.save(); c.canvas.translate(W - SIDE - CONTENT * .6f, top); v.draw(c.canvas); c.canvas.restore()
                c.canvas.drawRect(SIDE, top + 54f, W - SIDE, top + 55f, Paint().apply { color = 0x33FFFFFF })
            }
        }
        val blocks = coverStack(TOP, BOTTOM, 360f, 620f) { size -> listOfNotNull(
            coverBlock(c, size, 52f, center = true, radius = 4f, shadow = true), textBlock(c, title, 12f), textBlock(c, artist, 40f), dateBlock,
            if (credits.isEmpty()) null else Block(rowsBlock.height, 52f, rowsBlock.draw), rowBlock(c, listOf(logoPart(c)), 0f, Align.CENTER, 0f)) }
        stack(blocks, TOP, BOTTOM)
    }

    /** A poster: the year of release set very large, then the cover, the title and the credits centred under it. */
    private fun poster(c: Ctx) {
        gradient(c.canvas, 0f, H, shade(c.tint, .38f), 0xFF050505.toInt())
        val year = c.details.releaseDate?.take(4)?.takeIf { it.length == 4 }
        val yearLayout = year?.let { fit(c, it, 250f, 800, 0x33FFFFFF, CONTENT, Layout.Alignment.ALIGN_CENTER, 1, spacing = -.02f) }
        val title = fit(c, c.track.title, 64f, 700, Color.WHITE, CONTENT, Layout.Alignment.ALIGN_CENTER, 2)
        val artist = fit(c, c.track.artist, 40f, 400, 0xFFB8B8B8.toInt(), CONTENT, Layout.Alignment.ALIGN_CENTER, 1)
        val date = releaseDateText(c)?.let { fit(c, it, 28f, 500, GOLD, CONTENT, Layout.Alignment.ALIGN_CENTER, 1, spacing = .16f, mono = true) }
        val credits = rows(c).take(4)
        val lines = credits.map { (label, value) ->
            val l = fit(c, label, 22f, 600, GRAY, CONTENT, Layout.Alignment.ALIGN_CENTER, 1, spacing = .14f)
            val v = fit(c, value, 36f, 500, Color.WHITE, CONTENT, Layout.Alignment.ALIGN_CENTER, 2)
            Block(l.height + 4f + v.height, 26f) { y ->
                c.canvas.save(); c.canvas.translate(SIDE, y); l.draw(c.canvas); c.canvas.restore()
                c.canvas.save(); c.canvas.translate(SIDE, y + l.height + 4f); v.draw(c.canvas); c.canvas.restore()
            }
        }
        val blocks = coverStack(TOP, BOTTOM, 300f, 460f) { size ->
            (if (yearLayout != null) listOf(textBlock(c, yearLayout, 8f)) else emptyList()) +
                listOf(coverBlock(c, size, 48f, center = true, radius = 4f, shadow = true), textBlock(c, title, 12f), textBlock(c, artist, 20f)) +
                (if (date != null) listOf(textBlock(c, date, 44f)) else emptyList()) + lines +
                listOf(Block(0f, 20f) {}, rowBlock(c, listOf(logoPart(c)), 0f, Align.CENTER, 0f)) }
        stack(blocks, TOP, BOTTOM)
    }

    // ---- Credits ----

    /** The roles of the song that the cards tell, in this order, with the names of the people who have them. */
    private fun rows(c: Ctx): List<Pair<String, String>> {
        val credits = c.details.credits
        fun names(vararg keys: String, exclude: List<String> = emptyList()): String {
            val found = credits.filter { credit -> val role = credit.role.lowercase(); keys.any { it in role } && exclude.none { it in role } }
                .flatMap { it.people }.map { it.name }.distinct()
            return if (found.size > 4) found.take(4).joinToString(", ") + " +" + (found.size - 4) else found.joinToString(", ")
        }
        val result = mutableListOf<Pair<String, String>>()
        fun add(label: Int, value: String) { if (value.isNotBlank()) result += c.context.getString(label).uppercase() to value }
        add(R.string.credit_label_producer, names("producer", exclude = listOf("executive")))
        add(R.string.credit_label_writer, names("writer", "composer", "lyricist"))
        add(R.string.credit_label_mixing, names("mix"))
        add(R.string.credit_label_mastering, names("master"))
        add(R.string.credit_label_recording, names("record", "engineer", exclude = listOf("mix", "master")))
        add(R.string.credit_label_executive, names("executive"))
        return result
    }

    private fun releaseDateText(c: Ctx): String? {
        val raw = c.details.releaseDate ?: return null
        val date = runCatching { LocalDate.parse(raw.take(10)) }.getOrNull() ?: return raw
        return c.context.getString(R.string.story_released).uppercase() + "  " +
            date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.getDefault())).uppercase()
    }

    /** The release date as a block, or nothing when it is not known. */
    private fun dateBlocks(c: Ctx, width: Float, size: Float, valueSize: Float, align: Layout.Alignment, x: Float): List<Block> {
        val raw = c.details.releaseDate ?: return emptyList()
        val date = runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()
        val text = date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.getDefault())) ?: raw
        val label = fit(c, c.context.getString(R.string.story_released).uppercase(), size, 600, GRAY, width, align, 1, spacing = .14f)
        val value = fit(c, text, valueSize, 600, GOLD, width, align, 1)
        return listOf(Block(label.height + 6f + value.height, 30f) { y ->
            c.canvas.save(); c.canvas.translate(x, y); label.draw(c.canvas); c.canvas.restore()
            c.canvas.save(); c.canvas.translate(x, y + label.height + 6f); value.draw(c.canvas); c.canvas.restore()
        })
    }

    /** The credit rows as blocks, as many as fit in [budget] pixels of height. */
    private fun creditBlocks(c: Ctx, width: Float, x: Float, align: Layout.Alignment, labelSize: Float, valueSize: Float, budget: Float, maxLines: Int): List<Block> {
        val blocks = ArrayList<Block>()
        var used = 0f
        for ((label, value) in rows(c)) {
            val l = fit(c, label, labelSize, 600, GRAY, width, align, 1, spacing = .14f)
            val v = fit(c, value, valueSize, 500, Color.WHITE, width, align, maxLines)
            val height = l.height + 6f + v.height
            if (used + height > budget) break
            used += height + 30f
            blocks += Block(height, 30f) { y ->
                c.canvas.save(); c.canvas.translate(x, y); l.draw(c.canvas); c.canvas.restore()
                c.canvas.save(); c.canvas.translate(x, y + l.height + 6f); v.draw(c.canvas); c.canvas.restore()
            }
        }
        return blocks
    }

    private fun List<Block>.withGap(gap: Float): List<Block> = mapIndexed { i, b -> if (i < lastIndex) Block(b.height, gap, b.draw) else Block(b.height, 0f, b.draw) }

    // ---- Toolkit ----

    private enum class Align { LEFT, CENTER }

    /** The size of the cover is whatever height the other blocks leave, between [min] and [max]. */
    private fun coverStack(top: Float, bottom: Float, min: Float, max: Float, build: (Float) -> List<Block>): List<Block> {
        val used = total(build(0f))
        return build((bottom - top - used).coerceIn(min, max))
    }

    private fun total(blocks: List<Block>): Float {
        var sum = 0f
        blocks.forEachIndexed { i, b -> sum += b.height + if (i < blocks.lastIndex) b.gapAfter else 0f }
        return sum
    }

    /** Draws the blocks one under the other, centred in the zone between [top] and [bottom], or from the top with [alignTop]. */
    private fun stack(blocks: List<Block>, top: Float, bottom: Float, alignTop: Boolean = false) {
        var y = if (alignTop) top else top + ((bottom - top) - total(blocks)).coerceAtLeast(0f) / 2f
        blocks.forEachIndexed { i, b -> b.draw(y); y += b.height + if (i < blocks.lastIndex) b.gapAfter else 0f }
    }

    private fun coverBlock(c: Ctx, size: Float, gapAfter: Float, center: Boolean, radius: Float, shadow: Boolean): Block =
        Block(size, gapAfter) { y -> drawCover(c, if (center) SIDE + (CONTENT - size) / 2f else SIDE, y, size, radius, shadow) }

    private fun textBlock(c: Ctx, layout: StaticLayout, gapAfter: Float, x: Float = SIDE): Block =
        Block(layout.height.toFloat(), gapAfter) { y -> c.canvas.save(); c.canvas.translate(x, y); layout.draw(c.canvas); c.canvas.restore() }

    /** A row of parts. A row without parts has no height. */
    private fun rowBlock(c: Ctx, parts: List<Part>, gap: Float, align: Align, gapAfter: Float): Block {
        val usable = parts
        val height = usable.maxOfOrNull { it.height } ?: 0f
        val width = usable.sumOf { it.width.toDouble() }.toFloat() + gap * (usable.size - 1).coerceAtLeast(0)
        return Block(height, if (usable.isEmpty()) 0f else gapAfter) { y ->
            var x = if (align == Align.CENTER) (W - width) / 2f else SIDE
            usable.forEach { it.draw(x, y + (height - it.height) / 2f); x += it.width + gap }
        }
    }

    /** Text that fits [width] in at most [maxLines] lines: it shrinks to 60 percent of [size], and only then is it cut with an ellipsis. */
    private fun fit(c: Ctx, text: String, size: Float, weight: Int, color: Int, width: Float, align: Layout.Alignment, maxLines: Int, spacing: Float = 0f, mono: Boolean = false): StaticLayout {
        var s = size
        while (true) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; textSize = s; typeface = styled(if (mono) c.mono else c.face, weight); letterSpacing = spacing
            }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt()).setAlignment(align).setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
            val clipped = (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }
            if (!clipped || s <= size * .6f) return layout
            s *= .94f
        }
    }

    private fun measure(c: Ctx, text: String, size: Float, weight: Int, spacing: Float, mono: Boolean = false): Float =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = styled(if (mono) c.mono else c.face, weight); letterSpacing = spacing }.measureText(text)

    /** A small rectangular label such as MAX or HiFi. */
    private fun chipPart(c: Ctx, label: String, color: Int, filled: Boolean, grey: Boolean = false): Part {
        val textWidth = measure(c, label, 30f, 600, .08f)
        val width = textWidth + 40f
        return Part(width, 56f) { x, y ->
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = if (grey) 0x40FFFFFF else if (filled) 0x33FFFFFF else (color and 0x00FFFFFF) or 0x26000000 }
            c.canvas.drawRoundRect(RectF(x, y, x + width, y + 56f), 6f, 6f, fill)
            c.canvas.drawText(label, x + 20f, y + 38f, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = 30f; typeface = styled(c.face, 600); letterSpacing = .08f })
        }
    }

    /** MAX in gold and HiFi in grey, side by side. */
    private fun chipsPart(c: Ctx): Part {
        val parts = c.chips.map { chipPart(c, it, toneOf(c, it), false) }
        val width = parts.sumOf { it.width.toDouble() }.toFloat() + 20f * (parts.size - 1).coerceAtLeast(0)
        return Part(width, if (parts.isEmpty()) 0f else 56f) { x, y ->
            var at = x
            parts.forEach { it.draw(at, y); at += it.width + 20f }
        }
    }

    private fun pillPart(c: Ctx, label: String, color: Int): Part {
        val textWidth = measure(c, label, 30f, 600, .1f)
        val width = textWidth + 64f
        return Part(width, 56f) { x, y ->
            c.canvas.drawRoundRect(RectF(x, y, x + width, y + 56f), 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = (color and 0x00FFFFFF) or 0xAA000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f })
            c.canvas.drawText(label, x + 32f, y + 38f, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = 30f; typeface = styled(c.face, 600); letterSpacing = .1f })
        }
    }

    private fun toneOf(c: Ctx, label: String) = if (label == c.maxLabel) GOLD else 0xFFC8C8C8.toInt()

    /** The TIDAL mark: four diamonds and the tracked name. */
    private fun logoPart(c: Ctx): Part {
        val name = "T I D A L"
        val width = 56f + 18f + measure(c, name, 34f, 700, .12f)
        return Part(width, 56f) { x, y ->
            diamonds(c.canvas, x, y + 4f, 56f)
            c.canvas.drawText(name, x + 56f + 18f, y + 40f, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f; typeface = styled(c.face, 700); letterSpacing = .12f })
        }
    }

    private fun logoStackedPart(c: Ctx): Part {
        val width = maxOf(56f, measure(c, "TIDAL", 30f, 700, .16f))
        return Part(width, 56f + 14f + 30f) { x, y ->
            diamonds(c.canvas, x + (width - 56f) / 2f, y, 56f)
            c.canvas.drawText("TIDAL", x, y + 56f + 14f + 26f, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 30f; typeface = styled(c.face, 700); letterSpacing = .16f })
        }
    }

    private fun diamondPart(c: Ctx): Part = Part(96f, 56f) { x, y ->
        c.canvas.drawRoundRect(RectF(x, y, x + 96f, y + 56f), 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF })
        diamonds(c.canvas, x + 24f, y + 4f, 48f)
    }

    /** Four diamonds in the arrangement of the TIDAL mark. */
    private fun diamonds(canvas: Canvas, x: Float, y: Float, size: Float) {
        // The mark is [size] wide and a little less than that tall.
        val unit = size / 6f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        fun diamond(cx: Float, cy: Float) = canvas.drawPath(Path().apply { moveTo(cx, cy - unit); lineTo(cx + unit, cy); lineTo(cx, cy + unit); lineTo(cx - unit, cy); close() }, paint)
        diamond(x + unit, y + unit * 2); diamond(x + unit * 3, y + unit * 2); diamond(x + unit * 5, y + unit * 2)
        diamond(x + unit * 3, y + unit * 4)
    }

    private fun drawCover(c: Ctx, x: Float, y: Float, size: Float, radius: Float, shadow: Boolean) {
        val rect = RectF(x, y, x + size, y + size)
        if (shadow) c.canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); setShadowLayer(size * .07f, 0f, size * .035f, 0x99000000.toInt()) })
        val art = c.art
        if (art == null) { c.canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shade(c.tint, .3f) }); return }
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        c.canvas.save(); c.canvas.clipPath(clip)
        c.canvas.drawBitmap(art, null, rect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        c.canvas.restore()
    }

    private fun gradient(canvas: Canvas, from: Float, to: Float, start: Int, end: Int) {
        canvas.drawRect(0f, from, W, to, Paint().apply { shader = LinearGradient(0f, from, 0f, to, start, end, Shader.TileMode.CLAMP) })
    }

    /** The cover scaled to fill the width, blurred by scaling down and up again, drawn between [top] and [top] + [height]. */
    private fun backdropImage(canvas: Canvas, art: Bitmap, alpha: Float, top: Float, height: Float) {
        val small = Bitmap.createScaledBitmap(art, 32, 32, true)
        val mid = Bitmap.createScaledBitmap(small, 128, 128, true)
        val side = maxOf(height, W)
        val left = W / 2f - side / 2f
        canvas.save()
        canvas.clipRect(0f, top, W, top + height)
        canvas.drawBitmap(mid, null, RectF(left, top, left + side, top + side), Paint(Paint.FILTER_BITMAP_FLAG).apply { this.alpha = (alpha * 255).toInt() })
        canvas.restore()
        small.recycle(); mid.recycle()
    }

    private fun styled(base: Typeface, value: Int): Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, value, false) else Typeface.create(base, if (value >= 600) Typeface.BOLD else Typeface.NORMAL)

    /** A dark version of a colour. [amount] is the brightness that it keeps. */
    private fun shade(color: Int, amount: Float): Int {
        val hsv = FloatArray(3); Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * amount).coerceIn(0f, 1f); hsv[1] = (hsv[1] * .9f).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    /** The colour of the cover that stands out most: the average of a small copy, weighted toward saturated pixels. */
    private fun dominant(art: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(art, 16, 16, true)
        var r = 0.0; var g = 0.0; var b = 0.0; var total = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until 16) for (x in 0 until 16) {
            val pixel = small.getPixel(x, y); Color.colorToHSV(pixel, hsv)
            val w = 0.15 + hsv[1] * hsv[2]
            r += Color.red(pixel) * w; g += Color.green(pixel) * w; b += Color.blue(pixel) * w; total += w
        }
        small.recycle()
        return Color.rgb((r / total).toInt(), (g / total).toInt(), (b / total).toInt())
    }

    /** Loads the cover into a software bitmap. Coil decodes into hardware bitmaps, which a canvas cannot draw. */
    private suspend fun loadArtworkBitmap(context: Context, uriString: String): Bitmap? = withContext(Dispatchers.IO) {
        if (uriString.isBlank()) return@withContext null
        try {
            val result = SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(uriString).build())
            if (result is SuccessResult) {
                val bmp = result.image.toBitmap()
                return@withContext if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
            }
        } catch (_: Throwable) { }
        null
    }

    // ---- Sharing ----

    fun saveStoryToCache(context: Context, bitmap: Bitmap): Uri {
        val dir = File(context.cacheDir, "stories").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith("tidal_story_") }?.forEach { it.delete() }
        val file = File(dir, "tidal_story_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * Opens the Instagram Stories composer with the card as the background, or falls back to the system share sheet.
     * The card is the whole background. It is not sent as a sticker too, because Instagram would then put a second copy on top of it.
     */
    fun shareToInstagramStories(context: Context, imageUri: Uri, track: Track): Boolean {
        runCatching { context.grantUriPermission("com.instagram.android", imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val intent = Intent("com.instagram.share.ADD_TO_STORY").apply {
            setDataAndType(imageUri, "image/png")
            setPackage("com.instagram.android")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("source_application", context.packageName)
        }
        return try { context.startActivity(intent); true } catch (_: Throwable) { shareToGeneric(context, imageUri, track); false }
    }

    fun shareToGeneric(context: Context, imageUri: Uri, track: Track) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            putExtra(Intent.EXTRA_TEXT, "${track.title} · ${track.artist}\nhttps://tidal.com/browse/track/${track.id}") // i18n-ignore
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_story_preview_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun saveStoryToGallery(context: Context, bitmap: Bitmap, track: Track): Boolean = runCatching {
        val filename = "TidalStory_${track.id}_${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MyTidal")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "MyTidal").apply { mkdirs() }
            val file = File(dir, filename)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            @Suppress("DEPRECATION")
            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))
        }
        true
    }.getOrDefault(false)
}
