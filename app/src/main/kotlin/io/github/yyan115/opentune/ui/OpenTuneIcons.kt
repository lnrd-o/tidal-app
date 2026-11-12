package io.github.yyan115.opentune.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** OpenTune's 24-unit icon grid. Strokes share the same weight, joins and optical inset. */
object OpenTuneIcons {
    private fun glyph(name: String, data: String, solid: Boolean = false, mirror: Boolean = false) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirror).addPath(
            pathData = PathParser().parsePathString(data).toNodes(),
            fill = if (solid) SolidColor(Color.Black) else null,
            stroke = if (solid) null else SolidColor(Color.Black),
            strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ).build()

    val Notifications by lazy { glyph("Notifications", "M6 17V11A6 6 0 0 1 18 11V17L19.5 18.5H4.5Z M10 21A2 2 0 0 0 14 21") }
    val Radio by lazy { glyph("Radio", "M5.5 18.5A9 9 0 0 1 5.5 5.5 M18.5 5.5A9 9 0 0 1 18.5 18.5 M8.6 15.4A4.6 4.6 0 0 1 8.6 8.6 M15.4 8.6A4.6 4.6 0 0 1 15.4 15.4 M13.2 12A1.2 1.2 0 1 1 10.8 12A1.2 1.2 0 0 1 13.2 12Z") }
    val Timer by lazy { glyph("Timer", "M20 13.5A8 8 0 1 1 4 13.5A8 8 0 0 1 20 13.5Z M12 9V13.5L15 15.2 M9.5 2.8H14.5") }
    val Sort by lazy { glyph("Sort", "M4 7H20 M4 12H15 M4 17H10") }
    val Equalizer by lazy { glyph("Equalizer", "M4 7H20 M4 17H20 M8 4V10 M16 14V20") }
    val Video by lazy { glyph("Video", "M3.5 6.5H15V17.5H3.5Z M15 10.5L20.5 7.5V16.5L15 13.5") }
    val Person by lazy { glyph("Person", "M16 8A4 4 0 1 1 8 8A4 4 0 0 1 16 8Z M4.5 20.5C5.5 16.5 8.5 15 12 15C15.5 15 18.5 16.5 19.5 20.5") }
    val Fullscreen by lazy { glyph("Fullscreen", "M4 9V4H9 M15 4H20V9 M20 15V20H15 M9 20H4V15") }
    val Folder by lazy { glyph("Folder", "M3 6.5H10L12 9H21V19H3Z") }
    val Device by lazy { glyph("Device", "M8 3H16A1.5 1.5 0 0 1 17.5 4.5V19.5A1.5 1.5 0 0 1 16 21H8A1.5 1.5 0 0 1 6.5 19.5V4.5A1.5 1.5 0 0 1 8 3Z M10.5 17.5H13.5") }
    val Brand by lazy { glyph("OpenTune", "M18.4 5.8A9 9 0 1 0 20.5 15 M9.3 5.5V10.2A2.7 2.7 0 0 0 14.7 10.2V5.5 M12 12.9V20") }
    val Headphones by lazy { glyph("Listen", "M4 13V11A8 8 0 0 1 20 11V13 M4 12H6.4V20H4.8A1.8 1.8 0 0 1 3 18.2V13.8A1.8 1.8 0 0 1 4.8 12 M20 12H17.6V20H19.2A1.8 1.8 0 0 0 21 18.2V13.8A1.8 1.8 0 0 0 19.2 12") }
    val Search by lazy { glyph("Search", "M16 16L21 21 M18 10.8A7.2 7.2 0 1 1 3.6 10.8A7.2 7.2 0 0 1 18 10.8Z") }
    val LibraryMusic by lazy { glyph("Library", "M3.5 4V20 M8 4V20 M12.5 5L17.5 3.8L21 18.6L16 19.8Z") }
    val Download by lazy { glyph("Download", "M12 3V16 M7.5 11.5L12 16L16.5 11.5 M4 18V21H20V18") }
    val DownloadDone by lazy { glyph("Downloaded", "M6 10.5L10 14.5L18 6.5 M4 18V21H20V18") }
    val PlayArrow by lazy { glyph("Play", "M8 5.5L18 12L8 18.5Z", solid = true) }
    val Pause by lazy { glyph("Pause", "M7 5.5H10V18.5H7Z M14 5.5H17V18.5H14Z", solid = true) }
    val SkipNext by lazy { glyph("Next", "M5 5.5L15 12L5 18.5Z M19 5.5V18.5") }
    val SkipPrevious by lazy { glyph("Previous", "M19 5.5L9 12L19 18.5Z M5 5.5V18.5") }
    val Shuffle by lazy { glyph("Shuffle", "M3 6H5.5C10.5 6 12.5 18 17.5 18H21 M17.5 14.5L21 18L17.5 21.5 M3 18H5.5C7.8 18 9.4 15.3 10.8 12.5 M13.2 7.5C14.6 6.4 15.8 6 17.5 6H21 M17.5 2.5L21 6L17.5 9.5") }
    val Repeat by lazy { glyph("Repeat", "M5 7H18A3 3 0 0 1 21 10V12 M15 4L18 7L15 10 M19 17H6A3 3 0 0 1 3 14V12 M9 14L6 17L9 20") }
    val RepeatOne by lazy { glyph("RepeatOne", "M5 7H18A3 3 0 0 1 21 10V12 M15 4L18 7L15 10 M19 17H6A3 3 0 0 1 3 14V12 M9 14L6 17L9 20 M11 10L13 9V15") }
    val FavoriteBorder by lazy { glyph("Save", "M12 20S3 14.5 3 8.4A4.7 4.7 0 0 1 12 6.7A4.7 4.7 0 0 1 21 8.4C21 14.5 12 20 12 20Z") }
    val Favorite by lazy { glyph("Saved", "M12 20S3 14.5 3 8.4A4.7 4.7 0 0 1 12 6.7A4.7 4.7 0 0 1 21 8.4C21 14.5 12 20 12 20Z", solid = true) }
    val Lyrics by lazy { glyph("Lyrics", "M5 3.5H16L20 7.5V20.5H5Z M15.5 3.5V8H20 M8.5 11.5H16.5 M8.5 15H16.5 M8.5 18.5H13") }
    val PlaylistPlay by lazy { glyph("Queue", "M3 5.5H20 M3 10.5H13 M3 15.5H10 M16 13L22 17L16 21Z", mirror = true) }
    val QueueMusic get() = PlaylistPlay
    val PlaylistAdd by lazy { glyph("AddToPlaylist", "M3 5.5H20 M3 10.5H13 M3 15.5H10 M17.5 13V21 M13.5 17H21.5", mirror = true) }
    val PlaylistRemove by lazy { glyph("RemoveFromPlaylist", "M3 5.5H20 M3 10.5H13 M3 15.5H10 M14 17H22") }
    val Settings by lazy { glyph("Settings", "M10.23 4.92 L10.48 2.42 L13.52 2.42 L13.77 4.92 A7.3 7.3 0 0 1 15.76 5.74 L17.70 4.15 L19.85 6.30 L18.26 8.24 A7.3 7.3 0 0 1 19.08 10.23 L21.58 10.48 L21.58 13.52 L19.08 13.77 A7.3 7.3 0 0 1 18.26 15.76 L19.85 17.70 L17.70 19.85 L15.76 18.26 A7.3 7.3 0 0 1 13.77 19.08 L13.52 21.58 L10.48 21.58 L10.23 19.08 A7.3 7.3 0 0 1 8.24 18.26 L6.30 19.85 L4.15 17.70 L5.74 15.76 A7.3 7.3 0 0 1 4.92 13.77 L2.42 13.52 L2.42 10.48 L4.92 10.23 A7.3 7.3 0 0 1 5.74 8.24 L4.15 6.30 L6.30 4.15 L8.24 5.74 A7.3 7.3 0 0 1 10.23 4.92 Z M15 12A3 3 0 1 1 9 12A3 3 0 0 1 15 12Z") }
    val DragHandle by lazy { glyph("Reorder", "M5 8H19 M5 12H19 M5 16H19") }
    val MoreHoriz by lazy { glyph("More", "M5 12H5.01 M12 12H12.01 M19 12H19.01") }
    val MoreVert by lazy { glyph("Options", "M12 5H12.01 M12 12H12.01 M12 19H12.01") }
    val ArrowBack by lazy { glyph("Back", "M20 12H4 M10 6L4 12L10 18", mirror = true) }
    val ArrowForward by lazy { glyph("Forward", "M4 12H20 M14 6L20 12L14 18", mirror = true) }
    val ArrowUpward by lazy { glyph("MoveUp", "M12 21V3 M6 9L12 3L18 9") }
    val KeyboardArrowDown by lazy { glyph("Collapse", "M5 8.5L12 15.5L19 8.5") }
    val ChevronRight by lazy { glyph("Open", "M9 5L16 12L9 19", mirror = true) }
    val Close by lazy { glyph("Close", "M5.5 5.5L18.5 18.5 M18.5 5.5L5.5 18.5") }
    val Menu by lazy { glyph("Navigation", "M3 8H21 M3 16H16") }
    val Add by lazy { glyph("Add", "M12 4V20 M4 12H20") }
    val Album by lazy { glyph("Album", "M3 3H21V21H3Z M17 12A5 5 0 1 1 7 12A5 5 0 0 1 17 12Z M12 12H12.01") }
    val MusicNote by lazy { glyph("Audio", "M9 17V5L20 3V15 M9 8L20 6 M9 17C9 21 3 21 3 18C3 15 9 14.5 9 17 M20 15C20 19 14 19 14 16C14 13 20 12.5 20 15") }
    val AudioFile by lazy { glyph("AudioFile", "M5 3H15L20 8V21H5Z M15 3V8H20 M9 12V17 M12 10V19 M15 12V17") }
    val GraphicEq by lazy { glyph("AudioSource", "M4 10V14 M8 6V18 M12 3V21 M16 7V17 M20 10V14") }
    val Waves get() = Brand
    val Check by lazy { glyph("Check", "M4 12L9.5 17.5L20 6.5") }
    val CheckCircle by lazy { glyph("Connected", "M21 12A9 9 0 1 1 18 5.3 M8 11.5L11.5 15L21 5.5") }
    val AccountCircle by lazy { glyph("Account", "M21 12A9 9 0 1 1 3 12A9 9 0 0 1 21 12Z M15 9A3 3 0 1 1 9 9A3 3 0 0 1 15 9Z M6 18C7 13.5 17 13.5 18 18") }
    val Contrast by lazy { glyph("Appearance", "M21 12A9 9 0 1 1 3 12A9 9 0 0 1 21 12Z M12 3V21 M15 4V20 M18 6V18") }
    val CloudOff by lazy { glyph("Offline", "M3 3L21 21 M7 17H5A4 4 0 0 1 5 9 M9 5.3A6 6 0 0 1 18 10A3.5 3.5 0 0 1 20 16") }
    val Wifi by lazy { glyph("Wifi", "M2.5 7.5A16 16 0 0 1 21.5 7.5 M6 11A10 10 0 0 1 18 11 M9 14.5A5 5 0 0 1 15 14.5 M12 18H12.01") }
    val SignalCellularAlt by lazy { glyph("Mobile", "M5 17V21 M12 10V21 M19 3V21") }
    val Info by lazy { glyph("Information", "M21 12A9 9 0 1 1 3 12A9 9 0 0 1 21 12Z M12 10.5V17 M12 7H12.01") }
    val PrivacyTip by lazy { glyph("Privacy", "M12 2.5L20 6V12C20 17 15.5 20.5 12 22C8.5 20.5 4 17 4 12V6Z M9 12L11.5 14.5L16 9.5") }
    val Description get() = Lyrics
    val Code by lazy { glyph("SourceCode", "M8 6L2 12L8 18 M16 6L22 12L16 18 M14 3L10 21") }
    val Key by lazy { glyph("Key", "M12 8A5 5 0 1 1 2 8A5 5 0 0 1 12 8Z M11 11L21 21 M16 16L19 13 M19 19L22 16") }
    val Refresh by lazy { glyph("Refresh", "M20 9A8 8 0 0 0 6 5L3 8 M3 3V8H8 M4 15A8 8 0 0 0 18 19L21 16 M16 16H21V21") }
    val Edit by lazy { glyph("Edit", "M4 16L15.5 4.5L19.5 8.5L8 20H4Z M13 7L17 11 M14 21H21") }
    val RemoveCircleOutline by lazy { glyph("Remove", "M21 12A9 9 0 1 1 3 12A9 9 0 0 1 21 12Z M7 12H17") }
    val SaveAlt by lazy { glyph("Export", "M12 16V3 M7.5 7.5L12 3L16.5 7.5 M4 14V21H20V14") }
    val Share by lazy { glyph("Share", "M8 10L16 5 M8 14L16 19 M8 12A3 3 0 1 1 2 12A3 3 0 0 1 8 12Z M22 4A3 3 0 1 1 16 4A3 3 0 0 1 22 4Z M22 20A3 3 0 1 1 16 20A3 3 0 0 1 22 20Z") }
    val SearchOff by lazy { glyph("NoResults", "M17 16L21 20 M16.5 7A7.2 7.2 0 0 1 7 16.5 M4 4L20 20 M4 7A7.2 7.2 0 0 0 7 16.5") }
    val VolumeUp by lazy { glyph("VolumeUp", "M3 9V15H7L12 20V4L7 9H3Z M16.5 12A4.5 4.5 0 0 0 14 7.97V16.03A4.5 4.5 0 0 0 16.5 12Z M14 3.23V5.29A7 7 0 0 1 19 12A7 7 0 0 1 14 18.71V20.77A9 9 0 0 0 21 12A9 9 0 0 0 14 3.23Z") }
    val VolumeDown by lazy { glyph("VolumeDown", "M3 9V15H7L12 20V4L7 9H3Z M16.5 12A4.5 4.5 0 0 0 14 7.97V16.03A4.5 4.5 0 0 0 16.5 12Z") }
    val VolumeOff by lazy { glyph("VolumeOff", "M3 9V15H7L12 20V4L7 9H3Z M16 9L22 15 M22 9L16 15") }
    val AutoAwesome by lazy { glyph("AutoAwesome", "M12 2L14.2 7.8L20 10L14.2 12.2L12 18L9.8 12.2L4 10L9.8 7.8Z M19 15L17.9 17.9L15 19L17.9 20.1L19 23L20.1 20.1L23 19L20.1 17.9Z") }
    val Instagram by lazy { glyph("Instagram", "M12 2.163c3.204 0 3.584.012 4.85.07 3.252.148 4.771 1.691 4.919 4.919.058 1.265.069 1.645.069 4.849 0 3.205-.012 3.584-.069 4.849-.149 3.225-1.664 4.771-4.919 4.919-1.266.058-1.644.07-4.85.07-3.204 0-3.584-.012-4.849-.07-3.26-.149-4.771-1.699-4.919-4.92-.058-1.265-.07-1.644-.07-4.849 0-3.204.013-3.583.07-4.849.149-3.227 1.664-4.771 4.919-4.919 1.266-.057 1.645-.069 4.849-.069zm0-2.163c-3.259 0-3.667.014-4.947.072-4.358.2-6.78 2.618-6.98 6.98-.059 1.281-.073 1.689-.073 4.948 0 3.259.014 3.668.072 4.948.2 4.358 2.618 6.78 6.98 6.98 1.281.058 1.689.072 4.948.072 3.259 0 3.668-.014 4.948-.072 4.354-.2 6.782-2.618 6.979-6.98.059-1.28.073-1.689.073-4.948 0-3.259-.014-3.667-.072-4.947-.196-4.354-2.617-6.78-6.979-6.98-1.281-.059-1.69-.073-4.949-.073zm0 5.838c-3.403 0-6.162 2.759-6.162 6.162s2.759 6.163 6.162 6.163 6.162-2.759 6.162-6.163c0-3.403-2.759-6.162-6.162-6.162zm0 10.162c-2.209 0-4-1.79-4-4 0-2.209 1.791-4 4-4s4 1.791 4 4c0 2.21-1.791 4-4 4zm6.406-11.845c-.796 0-1.441.645-1.441 1.44s.645 1.44 1.441 1.44c.795 0 1.439-.645 1.439-1.44s-.644-1.44-1.439-1.44z") }
    val TidalDiamond by lazy { glyph("TidalDiamond", "M4 8L7 5L10 8L7 11Z M10 8L13 5L16 8L13 11Z M16 8L19 5L22 8L19 11Z M10 14L13 11L16 14L13 17Z") }
    val Bluetooth by lazy { glyph("Bluetooth", "M7 7L17 17L12 22V2L17 7L7 17") }
    val Usb by lazy { glyph("Usb", "M12 3V17 M12 17A2.5 2.5 0 1 0 12 22A2.5 2.5 0 0 0 12 17Z M12 14L7 11V8 M12 12L17 9V6.5 M5.5 5.5H8.5V8.5H5.5Z M17 3.5L19 6.5H15Z M10 3H14L12 1Z") }
    val Speaker by lazy { glyph("Speaker", "M6 2H18V22H6Z M15.5 15A3.5 3.5 0 1 1 8.5 15A3.5 3.5 0 0 1 15.5 15Z M12 6.5V7") }
    val Tune by lazy { glyph("Tune", "M4 7H13 M17 7H20 M4 17H7 M11 17H20 M15 4V10 M9 14V20") }
    val FastRewind by lazy { glyph("FastRewind", "M11.5 5.5V18.5L3 12Z M21.5 5.5V18.5L13 12Z", solid = true, mirror = true) }
    val FastForward by lazy { glyph("FastForward", "M12.5 5.5V18.5L21 12Z M2.5 5.5V18.5L11 12Z", solid = true, mirror = true) }
    val PlaySolid by lazy { glyph("PlaySolid", "M6.5 3.5V20.5L20.5 12Z", solid = true, mirror = true) }
    val PauseSolid by lazy { glyph("PauseSolid", "M5.5 3.5H10V20.5H5.5Z M14 3.5H18.5V20.5H14Z", solid = true) }
    val SkipNextSolid by lazy { glyph("SkipNextSolid", "M5 5V19L15 12Z M16.5 5H19V19H16.5Z", solid = true, mirror = true) }
    val ContentCopy by lazy { glyph("ContentCopy", "M16 1H4C2.9 1 2 1.9 2 3V17H4V3H16V1ZM19 5H8C6.9 5 6 5.9 6 7V21C6 22.1 6.9 23 8 23H19C20.1 23 21 22.1 21 21V7C21 5.9 20.1 5 19 5ZM19 21H8V7H19V21Z") }
}
