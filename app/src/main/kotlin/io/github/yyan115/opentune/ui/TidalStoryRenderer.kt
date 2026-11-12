package io.github.yyan115.opentune.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import io.github.yyan115.opentune.R
import io.github.yyan115.opentune.core.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** The nine layouts of the story card. Each one is a 1080 x 1920 design that takes its colours from the cover. */
enum class StoryLayout(val titleRes: Int) {
    CENTERED(R.string.story_layout_1), AURA(R.string.story_layout_2), NOIR(R.string.story_layout_3),
    MINI(R.string.story_layout_4), BACKDROP(R.string.story_layout_5), CARD(R.string.story_layout_6),
    GLOW(R.string.story_layout_7), SLATE(R.string.story_layout_8), SPLIT(R.string.story_layout_9)
}

/** Draws story cards and shares them. Rendering never throws: a missing cover gives a plain card, and a hardware bitmap is copied before it is drawn. */
object TidalStoryRenderer {
    const val STORY_WIDTH = 1080
    const val STORY_HEIGHT = 1920
    private const val W = STORY_WIDTH.toFloat()
    private const val H = STORY_HEIGHT.toFloat()
    private const val CX = W / 2f
    private const val GOLD = 0xFFE3C07A.toInt()

    /** What a layout draws with: the cover, its colour, the text and the quality chips. */
    private class Ctx(val canvas: Canvas, val art: Bitmap?, val track: Track, val tint: Int, val chips: List<String>, val maxLabel: String, val detail: String, val face: Typeface, val mono: Typeface)

    suspend fun generateStoryCard(context: Context, track: Track, quality: String, layout: StoryLayout = StoryLayout.CENTERED): Bitmap = withContext(Dispatchers.Default) {
        val bitmap = Bitmap.createBitmap(STORY_WIDTH, STORY_HEIGHT, Bitmap.Config.ARGB_8888)
        val art = loadArtworkBitmap(context, track.artwork.orEmpty())
        val readout = SourceReadout.from(quality)
        val chips = when (qualityTier(quality)) {
            QualityTier.MAX -> listOf(context.getString(R.string.badge_max), context.getString(R.string.badge_hifi))
            QualityTier.HIFI -> listOf(context.getString(R.string.badge_hifi))
            QualityTier.LOSSY -> listOf(readout.codec)
            QualityTier.UNKNOWN -> emptyList()
        }
        val detail = listOfNotNull(readout.codec.ifBlank { null }, readout.bits?.let { "$it-bit" }, readout.sampleRate?.let { "$it kHz" }, readout.bitrate?.let { "$it kbps" }).joinToString(" · ")
        val base = ResourcesCompat.getFont(context, R.font.dm_sans) ?: Typeface.SANS_SERIF
        val mono = ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
        val ctx = Ctx(Canvas(bitmap), art, track, art?.let(::dominant) ?: 0xFF3A3A3A.toInt(), chips, context.getString(R.string.badge_max), detail, base, mono)
        when (layout) {
            StoryLayout.CENTERED -> centered(ctx); StoryLayout.AURA -> aura(ctx); StoryLayout.NOIR -> noir(ctx)
            StoryLayout.MINI -> mini(ctx); StoryLayout.BACKDROP -> backdrop(ctx); StoryLayout.CARD -> card(ctx)
            StoryLayout.GLOW -> glow(ctx); StoryLayout.SLATE -> slate(ctx); StoryLayout.SPLIT -> split(ctx)
        }
        bitmap
    }

    // ---- Layouts ----

    private fun centered(c: Ctx) = with(c) {
        fill(canvas, 0xFF0B0B0C.toInt())
        art?.let { backdrop(canvas, it, 0.5f, top = 0f, height = 1100f) }
        gradient(canvas, 0f, 760f, 0f, 1300f, 0x000B0B0C, 0xFF0B0B0C.toInt())
        cover(canvas, art, CX - 300f, 460f, 600f, 6f, tint)
        titles(c, CX, 1260f, 1336f, 64f, 44f, Paint.Align.CENTER, 0xFFFFFFFF.toInt(), 0xFF9A9A9A.toInt())
        chipRow(c, CX, 1470f, center = true)
        logo(c, CX, 1700f)
    }

    private fun aura(c: Ctx) = with(c) {
        fill(canvas, shade(tint, .18f))
        art?.let { backdrop(canvas, it, 0.95f, top = 0f, height = H) }
        gradient(canvas, 0f, 0f, 0f, H, 0x66000000, 0x66000000)
        gradient(canvas, 0f, 900f, 0f, H, 0x00000000, 0xF2000000.toInt())
        if (chips.isNotEmpty()) text(canvas, chips.joinToString("  |  "), CX, 128f, 30f, 0xFFE8D9B0.toInt(), face, Paint.Align.CENTER, bold = true, spacing = .12f)
        cover(canvas, art, 110f, 290f, 860f, 8f, tint, shadow = true)
        titles(c, CX, 1330f, 1410f, 76f, 50f, Paint.Align.CENTER, 0xFFE6E6E6.toInt(), 0xFFA0A0A0.toInt())
        logo(c, CX, 1700f)
    }

    private fun noir(c: Ctx) = with(c) {
        fill(canvas, 0xFF08040A.toInt())
        gradient(canvas, 0f, 0f, 0f, 900f, shade(tint, .22f) and 0x00FFFFFF or 0x55000000, 0x00000000)
        cover(canvas, art, CX - 300f, 460f, 600f, 6f, tint, shadow = true)
        titles(c, CX, 1260f, 1336f, 64f, 44f, Paint.Align.CENTER, 0xFFD0D0D0.toInt(), 0xFF8C8C8C.toInt())
        if (chips.isNotEmpty()) pill(canvas, chips.first(), CX, 1480f, face, if (chips.first() == maxLabel) GOLD else 0xFFC8C8C8.toInt())
        logo(c, CX, 1700f)
    }

    private fun mini(c: Ctx) = with(c) {
        fill(canvas, 0xFF050B0C.toInt())
        gradient(canvas, 0f, 0f, 0f, 1000f, 0x22000000 or (shade(tint, .5f) and 0x00FFFFFF), 0x00000000)
        cover(canvas, art, CX - 200f, 600f, 400f, 4f, tint, shadow = true)
        titles(c, CX, 1130f, 1196f, 52f, 36f, Paint.Align.CENTER, 0xFFCFCFCF.toInt(), 0xFF8A8A8A.toInt())
        logo(c, 300f, 1700f)
        if (chips.isNotEmpty()) chip(canvas, chips.first(), 820f, 1700f, face, if (chips.first() == maxLabel) GOLD else 0xFFC8C8C8.toInt(), align = Paint.Align.CENTER)
    }

    private fun backdrop(c: Ctx) = with(c) {
        fill(canvas, 0xFF060606.toInt())
        art?.let { backdrop(canvas, it, 1f, top = 0f, height = 1450f) }
        gradient(canvas, 0f, 0f, 0f, 1450f, 0x33000000, 0x22000000)
        gradient(canvas, 0f, 1000f, 0f, 1700f, 0x00060606, 0xFF060606.toInt())
        if (chips.isNotEmpty()) chip(canvas, chips.last(), 480f, 150f, face, 0xFFFFFFFF.toInt(), align = Paint.Align.CENTER, filled = true)
        diamondChip(canvas, 600f, 150f)
        cover(canvas, art, CX - 400f, 330f, 800f, 6f, tint, shadow = true)
        titles(c, CX, 1420f, 1500f, 96f, 54f, Paint.Align.CENTER, 0xFFFFFFFF.toInt(), 0xFF9A9A9A.toInt())
    }

    private fun card(c: Ctx) = with(c) {
        gradient(canvas, 0f, 0f, 0f, H, 0xFF2B2B2D.toInt(), 0xFF19191B.toInt())
        cover(canvas, art, 90f, 130f, 900f, 14f, tint, shadow = true)
        titles(c, 90f, 1230f, 1310f, 76f, 50f, Paint.Align.LEFT, 0xFFDADADA.toInt(), 0xFF8E8E90.toInt(), maxWidth = 640f)
        logoStacked(c, 905f, 1190f)
        if (chips.isNotEmpty()) chip(canvas, chips.joinToString(" • "), 90f, 1690f, face, 0xFFBFBFC2.toInt(), align = Paint.Align.LEFT, filled = true, grey = true)
    }

    private fun glow(c: Ctx) = with(c) {
        fill(canvas, 0xFF0A0505.toInt())
        art?.let { backdrop(canvas, it, 0.9f, top = 0f, height = H) }
        gradient(canvas, 0f, 0f, 0f, H, 0x44000000, 0x99000000.toInt())
        gradient(canvas, 0f, 1000f, 0f, H, 0x00000000, 0xEE000000.toInt())
        cover(canvas, art, CX - 330f, 400f, 660f, 8f, tint, shadow = true)
        titles(c, CX, 1230f, 1304f, 64f, 44f, Paint.Align.CENTER, 0xFFFFFFFF.toInt(), 0xFFA8A8A8.toInt())
        logo(c, 420f, 1700f)
        if (chips.isNotEmpty()) chip(canvas, chips.first(), 700f, 1700f, face, if (chips.first() == maxLabel) GOLD else 0xFFC8C8C8.toInt(), align = Paint.Align.LEFT)
    }

    private fun slate(c: Ctx) = with(c) {
        gradient(canvas, 0f, 0f, 0f, H, 0xFF273034.toInt(), 0xFF171C1F.toInt())
        logo(c, CX, 190f)
        cover(canvas, art, CX - 380f, 330f, 760f, 6f, tint, shadow = true)
        titles(c, CX, 1290f, 1370f, 80f, 50f, Paint.Align.CENTER, 0xFFE8E8E8.toInt(), 0xFF9AA2A6.toInt())
        if (chips.isNotEmpty()) chip(canvas, chips.last(), CX, 1470f, face, 0xFFE0E0E0.toInt(), align = Paint.Align.CENTER, filled = true, grey = true)
    }

    private fun split(c: Ctx) = with(c) {
        fill(canvas, shade(tint, .16f))
        art?.let { backdrop(canvas, it, .8f, top = 0f, height = H) }
        gradient(canvas, 0f, 0f, 0f, H, 0x66000000, 0xCC000000.toInt())
        // The coloured panel on the left carries the cover and the title.
        val panel = Paint().apply { color = shade(tint, .55f) and 0x00FFFFFF or 0xE6000000.toInt() }
        c.canvas.drawRect(0f, 0f, 400f, H, panel)
        cover(canvas, art, 160f, 330f, 800f, 6f, tint, shadow = true)
        titles(c, 90f, 1330f, 1412f, 76f, 50f, Paint.Align.LEFT, 0xFFFFFFFF.toInt(), 0xFFB0B0B0.toInt(), maxWidth = 900f)
        logo(c, 90f + 130f, 1700f, left = true)
        chipRow(c, 990f, 1700f, center = false, rightAlign = true, joiner = "&")
    }

    // ---- Parts ----

    private fun fill(canvas: Canvas, color: Int) = canvas.drawColor(color)

    private fun gradient(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, from: Int, to: Int) {
        val paint = Paint().apply { shader = LinearGradient(x0, y0, x1, y1, from, to, Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, minOf(y0, y1), W, maxOf(y0, y1), paint)
    }

    /** The cover scaled to fill the width, blurred by scaling down and up again, and faded at the bottom so that the card shows below it. */
    private fun backdrop(canvas: Canvas, art: Bitmap, alpha: Float, top: Float, height: Float) {
        val small = Bitmap.createScaledBitmap(art, 32, 32, true)
        val mid = Bitmap.createScaledBitmap(small, 128, 128, true)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { this.alpha = (alpha * 255).toInt() }
        // A square cover scaled to the card height is wider than the card, so its sides are cropped.
        val side = maxOf(height, W)
        val left = CX - side / 2f
        canvas.save()
        canvas.clipRect(0f, top, W, top + height)
        canvas.drawBitmap(mid, null, RectF(left, top, left + side, top + side), paint)
        canvas.restore()
        small.recycle(); mid.recycle()
    }

    private fun cover(canvas: Canvas, art: Bitmap?, x: Float, y: Float, size: Float, radius: Float, tint: Int, shadow: Boolean = false) {
        val rect = RectF(x, y, x + size, y + size)
        if (shadow) canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); setShadowLayer(size * .07f, 0f, size * .035f, 0x99000000.toInt()) })
        if (art == null) { canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shade(tint, .3f) }); return }
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        canvas.save(); canvas.clipPath(clip)
        canvas.drawBitmap(art, null, rect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        canvas.restore()
    }

    private fun titles(c: Ctx, x: Float, titleY: Float, artistY: Float, titleSize: Float, artistSize: Float, align: Paint.Align, titleColor: Int, artistColor: Int, maxWidth: Float = 900f) {
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = titleColor; textSize = titleSize; typeface = weight(c.face, 700); textAlign = align; letterSpacing = -.01f }
        val artist = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = artistColor; textSize = artistSize; typeface = weight(c.face, 400); textAlign = align }
        c.canvas.drawText(TextUtils.ellipsize(c.track.title, title, maxWidth, TextUtils.TruncateAt.END).toString(), x, titleY, title)
        c.canvas.drawText(TextUtils.ellipsize(c.track.artist, artist, maxWidth, TextUtils.TruncateAt.END).toString(), x, artistY, artist)
    }

    private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, face: Typeface, align: Paint.Align, bold: Boolean = false, spacing: Float = 0f) {
        canvas.drawText(value, x, y, TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size; typeface = weight(face, if (bold) 700 else 400); textAlign = align; letterSpacing = spacing
        })
    }

    /** A small rectangular label such as MAX or HiFi, set in the tracked capitals of the TIDAL badges. */
    private fun chip(canvas: Canvas, label: String, x: Float, y: Float, face: Typeface, color: Int, align: Paint.Align, filled: Boolean = false, grey: Boolean = false) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = 30f; typeface = weight(face, 600); letterSpacing = .08f }
        val width = paint.measureText(label)
        val left = when (align) { Paint.Align.CENTER -> x - width / 2f; Paint.Align.RIGHT -> x - width; else -> x }
        val rect = RectF(left - 18f, y - 34f, left + width + 18f, y + 14f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = if (grey) 0x40FFFFFF else if (filled) 0x33FFFFFF else (color and 0x00FFFFFF) or 0x26000000 }
        canvas.drawRoundRect(rect, 6f, 6f, fill)
        canvas.drawText(label, left, y, paint)
    }

    private fun pill(canvas: Canvas, label: String, cx: Float, y: Float, face: Typeface, color: Int) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = 30f; typeface = weight(face, 600); letterSpacing = .1f }
        val width = paint.measureText(label)
        val rect = RectF(cx - width / 2f - 30f, y - 36f, cx + width / 2f + 30f, y + 16f)
        canvas.drawRoundRect(rect, 26f, 26f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = (color and 0x00FFFFFF) or 0xAA000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f })
        canvas.drawText(label, cx - width / 2f, y, paint)
    }

    private fun chipRow(c: Ctx, x: Float, y: Float, center: Boolean, rightAlign: Boolean = false, joiner: String? = null) {
        if (c.chips.isEmpty()) return
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; typeface = weight(c.face, 600); letterSpacing = .08f }
        val gap = 26f
        val separator = joiner?.let { paint.measureText(it) + gap * 2 } ?: gap
        val widths = c.chips.map { paint.measureText(it) + 36f }
        val total = widths.sum() + separator * (c.chips.size - 1)
        var left = when { center -> x - total / 2f; rightAlign -> x - total; else -> x }
        c.chips.forEachIndexed { index, label ->
            val tone = if (label == c.maxLabel) GOLD else 0xFFC8C8C8.toInt()
            chip(c.canvas, label, left + 18f, y, c.face, tone, Paint.Align.LEFT, grey = tone != GOLD)
            left += widths[index]
            if (index < c.chips.lastIndex) {
                joiner?.let { text(c.canvas, it, left + gap, y, 30f, 0xFF8A8A8A.toInt(), c.face, Paint.Align.LEFT) }
                left += separator
            }
        }
        if (c.detail.isNotEmpty() && center) text(c.canvas, c.detail, x, y + 64f, 24f, 0xFF707070.toInt(), c.mono, Paint.Align.CENTER, spacing = .04f)
    }

    /** The TIDAL mark: four diamonds and the tracked name. [left] puts the mark at x instead of centring it. */
    private fun logo(c: Ctx, x: Float, y: Float, left: Boolean = false) {
        val name = "T I D A L"
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f; typeface = weight(c.face, 700); letterSpacing = .12f }
        val width = paint.measureText(name)
        val total = 56f + 18f + width
        val start = if (left) x - 130f else x - total / 2f
        diamonds(c.canvas, start, y - 26f, 56f)
        c.canvas.drawText(name, start + 56f + 18f, y, paint)
    }

    private fun logoStacked(c: Ctx, cx: Float, y: Float) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 30f; typeface = weight(c.face, 700); letterSpacing = .16f; textAlign = Paint.Align.CENTER }
        diamonds(c.canvas, cx - 28f, y - 70f, 56f)
        c.canvas.drawText("TIDAL", cx, y + 20f, paint)
    }

    private fun diamondChip(canvas: Canvas, cx: Float, y: Float) {
        canvas.drawRoundRect(RectF(cx - 40f, y - 34f, cx + 40f, y + 14f), 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF })
        diamonds(canvas, cx - 24f, y - 24f, 48f)
    }

    /** Four diamonds in the arrangement of the TIDAL mark. */
    private fun diamonds(canvas: Canvas, x: Float, y: Float, size: Float) {
        val unit = size / 4f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        fun diamond(cx: Float, cy: Float) = canvas.drawPath(Path().apply { moveTo(cx, cy - unit); lineTo(cx + unit, cy); lineTo(cx, cy + unit); lineTo(cx - unit, cy); close() }, paint)
        diamond(x + unit, y + unit); diamond(x + unit * 3, y + unit); diamond(x + unit * 5, y + unit); diamond(x + unit * 3, y + unit * 3)
    }

    private fun weight(base: Typeface, value: Int): Typeface =
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
            val weight = 0.15 + hsv[1] * hsv[2]
            r += Color.red(pixel) * weight; g += Color.green(pixel) * weight; b += Color.blue(pixel) * weight; total += weight
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

    /** Opens the Instagram Stories composer with the card, or falls back to the system share sheet. */
    fun shareToInstagramStories(context: Context, imageUri: Uri, track: Track): Boolean {
        runCatching { context.grantUriPermission("com.instagram.android", imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val intent = Intent("com.instagram.share.ADD_TO_STORY").apply {
            setDataAndType(imageUri, "image/png")
            setPackage("com.instagram.android")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("interactive_asset_uri", imageUri)
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
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OpenTune")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "OpenTune").apply { mkdirs() }
            val file = File(dir, filename)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            @Suppress("DEPRECATION")
            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))
        }
        true
    }.getOrDefault(false)
}
