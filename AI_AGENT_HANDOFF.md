# MyTidal (OpenTune code base): handoff

> For engineers and AI agents continuing work. Last updated November 2026.
> Android, Kotlin 2, Jetpack Compose (Material 3), Media3 ExoPlayer, Coil 3. Min SDK 26, JDK 21.

## What the app is

A TIDAL player. Local file import and folder scanning were removed. Offline TIDAL downloads stay and are listed under **Downloads**
(`Destination.Device`, kept as the id so the `nav-device` test tag still works). Playlists made on the device (of TIDAL tracks) stay.

## Design rules

- **One theme**: dark, monochrome, **square corners everywhere** (`Themes.kt`, `AppTheme.shapes` is radius 0). There is no theme picker, no light mode.
- **Typeface**: DM Sans (`res/font/dm_sans.ttf`, variable) for text, JetBrains Mono for timecodes and readouts. Licence in `assets/licenses/DM-Sans-OFL.txt`.
- Buttons and cards are rectangles. Use `RectangleShape`, never `RoundedCornerShape` or `CircleShape`. Sheets use `SheetHandle()` and a square shape.
- Every user-visible string lives in `res/values/strings.xml` (`python scripts/check_strings.py` enforces it).
- Keep test tags: `player-title`, `player-artist`, `mini-player`, `nav-home|search|library|device`, `spectrum`, `quality-badge`, `signal-path`, `up-next`, `cover-pager`.

## Now Playing (`ui/GalleryPlayer.kt`)

The cover is large and the text and controls are always visible. These gestures work on the cover:

| Gesture | Action |
| --- | --- |
| Double tap | save the track (heart burst) |
| Swipe left / right | next / previous track (pager) |
| Swipe up | queue |
| Swipe down | close |
| Long press | track menu |
| Drag along the right edge | volume (square HUD) |

The **Up next** strip slides in a new track when the next track changes. Lyrics swap with the cover through an animated transition, and lines spring and fade by their distance from the active line (`LyricsPanel.kt`).

## Signal path and spectrum (`ui/SignalPathSheet.kt`)

The quality badge (MAX | 24/96, HiFi, or the codec for lossy) opens a **signal path** sheet in the style of Roon: Source, Decoder, optional Normalization, Output, each with a fidelity mark, and a verdict (Hi-Res Lossless / Lossless / Processed / Lossy). Everything shown is measured:

- Source: the stream label (`SourceReadout`, `qualityTier`).
- Decoder: the PCM format the decoder hands on (`SignalPath.decoded`, written by `SpectrumProcessor.onConfigure`).
- Output: the device Android routes to (`AudioManager`, with a device callback), the mixer rate, and whether bit-perfect USB is active (`SignalPath.bitPerfectDevice`).
- Normalization gain (`SignalPath.normalizationDb`).

The sheet also shows a **live spectrum analyzer**: single colour, square cells, falling columns with held peaks, plus the **highest frequency that carries signal** (a lossy stream stops near 16 to 20 kHz).

### FFT (`core/Spectrum.kt`)

- Real-input FFT of 2048 samples: packed into a 1024-point complex FFT and split, about half the work of a complex FFT. Bit-reversal and twiddle tables are precomputed.
- Band levels sum the power of the bins of a band, on a -84 dB to 0 dB scale. The overload with `reBuffer`, `imBuffer`, `powerBuffer` allocates nothing.
- The analysis runs on `Dispatchers.Default` about every 16 ms, writes into shared arrays, and the canvas redraws from `withFrameNanos` without recomposing.
- `AudioSpectrum.watch()` and `release()` switch the tap on only while a screen shows the analyzer, so it costs nothing otherwise. `latest()` copies with two `arraycopy` calls.
- Tests: `core/src/test/.../SpectrumTest` (inside `LocalFilesTest.kt`) and `FastSpectrumTest.kt`.

There is **no equalizer** (the hardware `Equalizer` effect and its settings were removed).

## Instagram story (`ui/TidalStoryRenderer.kt`, `ui/StoryShareSheet.kt`)

Nine 1080 x 1920 layouts (`StoryLayout`): Centered, Aura, Noir, Mini, Backdrop, Card, Glow, Slate, Split. Colours come from the cover (`dominant`). Chips show the real quality: MAX and HiFi, HiFi, or the codec. Always copy a hardware bitmap to `ARGB_8888` before drawing it. Sharing uses a `FileProvider` URI, `grantUriPermission` for Instagram, and the `<queries>` entries in the manifest.

## Performance notes

- `MainViewModel` writes `PlaybackState` only when something changed (the 500 ms loop used to rewrite it every time and recompose the screen).
- Decoded queue tracks are cached by item id (cleared when the timeline changes). The queue is saved on queue events, not on every player event.
- The playback position has its own flow (`LocalPlaybackPosition`); only the seek line and the mini player line read it.

## Layout of the code

```
app/src/main/kotlin/io/github/yyan115/opentune/
  data/       AiRecommendationEngine, LibraryStore, AppSettings, Download*, BackupManager, Scrobbler
  playback/   PlaybackService, AudioSpectrum (+ SignalPath, SpectrumProcessor), BrowseTree, AudioSources
  ui/         Root, GalleryHome, GalleryPlayer, SignalPathSheet, StoryShareSheet, TidalStoryRenderer,
              DownloadsScreen, Screens (search, library, settings), PageScreens, PlayerScreen (queue),
              LyricsPanel, Components, Themes, Theme, Navigation
core/         Spectrum, Models, TidalClient (pure Kotlin)
```

## Checks before handing off

```powershell
./gradlew :core:test :app:testDebugUnitTest
python scripts/check_strings.py
./gradlew :app:assembleDebug :app:lintDebug
```

The instrumented tests in `app/src/androidTest` were edited to compile after the removals but have not been run; the UI-driven ones
(`ScreenTourTest`, `PlayerLayoutTest`, `PseudoLocaleTest`, `LocalPlaybackTest`) still describe the old player and settings and need updating on a device.

## D'JAI (automix)

Design notes are in `docs/DJAI.md`. The code is in `core/.../dj/` (pure Kotlin, tested) and `app/.../playback/dj/` and `ui/DjStrip.kt`.

- **Output path:** with D'JAI on (and not while an album plays), the players only decode. `DeckTapProcessor` copies their audio into rings and outputs silence, and `DjMixer` plays the result through its own `AudioTrack` (48 kHz float). A queue that is one album turns D'JAI off and the mixer stops.
- **Matching:** `MixPlanner.decide` scores tempo (40%), key (30%, Camelot) and energy (30%). From 70% the strip is cyan, from 90% gold, below that white. While a song plays, D'JAI listens to the next six songs on the idle deck (15 s each, `DjEngine.scouting`); shortly before the last minute, `MixPlanner.choose` moves the best match into the next place if the next song is below 70%.
- **Transition:** the swap lands on a 32-beat phrase boundary of the outgoing track (counted from its first drum hit, `PhraseOrigin`). The incoming track enters 32 beats (or 16, when the energies differ) before it, at its own first drum hit. After the swap the outgoing track leaves over 16 beats. The kicks are locked by measuring each track's kick against its grid (`KickAligner`). A low-pass opens on the incoming track and a high-pass closes in on the outgoing one (`SweepFilter`, `MixCurves.lowpassHz/highpassHz`).
- **Limits:** the incoming tempo is matched by varispeed, so pitch moves by up to 6%. Half and double time are not matched. A seek, skip or queue change during a prepared mix cancels it.

## Stories, credits and spectrum

- `TidalStoryRenderer` has 12 layouts built from stacks inside a safe zone (Instagram covers the top and bottom of a story). Text measures and shrinks to fit. The last three (Liner, Sleeve, Poster) show the release date and the credits, which `MainViewModel.storyDetails` loads from TIDAL.
- Instagram gets the card as the background only. Sending it as a sticker too put a second copy on top of it.
- A long press on the cover shows the credits. `SpectrumScreen` is the full-screen analyzer, in portrait and landscape.
- Debug builds have a preview page that renders every story layout: `adb shell am start -n <debug package>/io.github.yyan115.opentune.preview.ThemePreviewActivity --es page stories`, then read `files/story-preview` with `run-as`.