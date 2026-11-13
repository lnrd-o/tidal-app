# Changelog

All notable changes to OpenTune are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.8.0] - 2026-11-12

### Added

- D'JAI, an automix: it listens to the songs ahead and mixes into the next one on the beat, matching tempo, key and energy, with a bass swap and a filter sweep. When the next song matches by less than 70 percent it picks a better one from the queue. Albums play as released.
- Crossfade between songs, from 0 to 12 seconds, without D'JAI.
- Tone Lab, for the sound: studio glue in seven flavors (also on the centre and the sides apart), console and tube saturation, four kinds of tape, air, a soft clip, and vinyl with its condition and speed. A switch compares with the original at the same loudness.
- A signal path sheet, opened from the quality badge, and a full-screen spectrum analyzer in portrait and landscape. In landscape it shows the artwork.
- An adjustable audio cache for the songs that were listened to, with its size, its use and a button to clear it. D'JAI remembers what it learned about songs between sessions.
- Online song data: D'JAI can take the tempo and the key from TIDAL, and the tempo from Deezer, so that it can pick a better next song sooner.
- A switch that colours the player and the spectrum with the main colour of the cover.
- Twelve layouts for sharing a song to an Instagram story, three of them with the release date and the credits.
- Swipe a song to the right to play it next, to the left to add it to the queue, and press it for the menu. The sleep timer fades out over twelve seconds. A press on the artwork shows the credits.
- A layout for tablets and wide screens, and a report of the last crash that can be shared.

### Changed

- The update check ignores test versions, such as 0.7.1-rc1, so only a published release is offered.
- When TIDAL stops accepting the app, a listener who is signed in sees that message at once and stays signed in, in place of a message about an expired session.
- A new look: square corners, a dark monochrome theme, the DM Sans font and a player that is driven by gestures. The app is now called MyTidal.
- The player in landscape keeps its text and controls under the top bar and scrolls when the D'JAI strip is open.
- The page title is left out in landscape, to leave room for the list.

### Fixed

- The app no longer crashes on Settings and on the library on phones that report no locale, such as some that run Android apps through HarmonyOS.

### Removed

- Adding audio files from the device. Downloads from TIDAL stay.

## [0.7.0] - 2026-11-08

### Added

- A finished download is one tagged audio file in the Music/OpenTune folder, with title, artist, album, track number and cover, and any file manager or music player can open it. Settings shows the folder.
- A page after the first screen asks for the permissions that the version of Android needs: notifications on Android 13 and later, and storage on Android 9 and older.

### Changed

- Downloads from earlier versions move into the folder once, in the background. A download that cannot move keeps playing as before.
- A download whose file was removed elsewhere is a stream again.
- A folder scan leaves the files of downloads out, so a track does not appear twice.

### Removed

- Export audio file, because the download is the file.

## [0.6.4] - 2026-11-07

### Changed

- OpenTune is licensed under Apache-2.0 and no longer under GPL-3.0-or-later. Releases up to 0.6.3 stay under the GPL for those who have them.
- Signing in opens the browser at once, without a sheet in between. A bar shows while the app completes the sign-in, and a failure appears as a message.
- The first screen reads "Unofficial open source music player for TIDAL with local playback", and its note puts each sentence on its own line.
- The card and the account row read "Sign in to stream, download and manage your TIDAL library". Privacy reads "No ads, analytics or tracking. Streaming is provided by TIDAL."
- The version row has the title Version and the number below it.
- A line of text that holds one sentence has no full stop. Text with several sentences keeps its full stops.
- The empty list of On device sits in the middle of the free space.

## [0.6.3] - 2026-11-07

### Changed

- The card and the account row read "Connect TIDAL" with "Sign in to use TIDAL features and settings."
- Light comes before Dark in the theme picker.
- The equalizer has a Custom entry that keeps the bands that you set. Its preset field is inactive while the equalizer is off, and its list shows four entries at a time and opens below the field.
- The information button of the listening statistics sits on the line of the title, and a change of period no longer flashes a loading indicator.
- About starts with Check for updates, and the version has no description. The title of an empty list is centred.

## [0.6.2] - 2026-11-07

### Added

- Check for updates, under About, looks for a newer release when the app opens and shows a dialog when there is one. A switch turns it off.
- The theme picker shows the swiped theme in the whole app at once. Use keeps it, and closing the picker brings back the saved theme.
- Listening statistics has an information button that explains when a track counts as played.

### Changed

- Licenses lists OpenTune and each component with its licence, and a row opens the licence text. The notices carry no other text.
- The first screen, the sign-in sheet, the empty list of On device, the account row, the About text and the explanations of Avoid repeats and Clear listening history have new wording. The sign-in sheet no longer carries the password sentence.
- Avoid repeats names its list Avoided tracks, and the action on a track is Allow.
- The text of the statistics states the real rule: a track counts after half its length or 4 minutes, whichever comes first, and never before 30 seconds. The selected period is a filled segment.
- Add files and Add folder fit narrow screens.

## [0.6.1] - 2026-11-07

### Added

- Avoid repeats has its own page. It lists the excluded tracks with the time of their last play and the time that remains, and Include lets a track play again at once.
- Experimental has two download options. Download saved tracks downloads a track when it is saved with the heart button. Download played tracks downloads each track that counts as played and asks for confirmation. Turning one on turns the other off.
- Listening statistics has its own page, and the period is chosen with a segmented button.
- The equalizer chooses its preset from a list that shows the current preset.

### Changed

- Without an account, On device is the only place, with its text filter and no navigation bar. Settings that only apply to TIDAL appear once an account is connected.
- Experimental is a section of the Settings list and no longer a collapsed row.
- The licences open as a list with one page for each document, in place of a dialog with seven tabs.
- New installs follow the theme of the device. Audiophile Gold is named Gold, and the themes no longer carry descriptions.
- The About section names OpenTune as an unofficial open source Android music player for TIDAL, for every version.
- Offline mode is switched off when the account is disconnected.

### Removed

- Keep saved tracks offline, which became Download saved tracks. A saved track is downloaded when it is saved, and tracks that were saved earlier are no longer downloaded in the background.
- The Audio output list, which only repeated what Android reports.

## [0.6.0] - 2026-11-07

### Added

- Local files are known by their document, so a file that you add by hand and that a folder holds is one track. A file is read again when it changes.
- Local files show their bit depth, sample rate and bitrate, carry a Local mark and have a details sheet with their location, folder and size. A file without a picture takes the cover of its folder, or a coloured tile with a note.
- On device sorts by date added, title, artist or album, and filters by text. Downloads and Local files have their own filters.
- The queue shows artwork, reorders by dragging a row and removes a track with a swipe. A swipe on the mini player stops playback.
- Playlists of the account and playlists made on the device share one list in Library. A playlist page has Add songs, which searches TIDAL and the local files and adds tracks without leaving the page. A new playlist is created on the account or on the device.
- Avoid repeats puts recently played tracks last in shuffle and leaves them out of radio and autoplay.
- A visualizer shows the spectrum of the music over the artwork in the player, on Android 11 and later.
- Download over mobile data, with a confirmation before it turns on.
- A download notification shows its progress and has a Cancel button.

### Changed

- Shuffle reorders the queue itself. The queue shows what plays next, and play next and reordering work while shuffle is on. Turning shuffle off restores the earlier order.
- Settings put the equalizer, ListenBrainz, the visualizer and bit-perfect USB output under Experimental, which is hidden until opened. Switches respond to a tap anywhere on their row. The wording of the settings is shorter.
- The theme picker fits on one screen, and the licence texts open in a dialog with one row of tabs.
- Biographies keep their paragraphs.
- A local file that cannot be read gives a clear message, and a folder scan that fails keeps what it found before.
- Track radio is named Play similar music.
- The Settings icon is a gear. Opening an album or an artist from the player works when Settings is open behind it.
- Downloads use the mobile data setting in place of Wi-Fi only.

### Fixed

- A folder on a memory card that was not available at the time of a scan no longer empties the library of its files.
- The licence texts open without a delay.

## [0.5.0] - 2026-11-02

### Added

- A welcome screen on the first launch. Sign in to TIDAL, or use local music only. Without an account the app offers On device and Search.
- On device replaces Downloads. It holds the tracks downloaded from TIDAL, audio files, folders and playlists made on the device, with filters for each.
- Folders on the device. Add a folder with the system folder picker and OpenTune scans it, reads its subfolders and keeps it up to date.
- Twelve themes: Dark, Light, Obsidian, Audiophile Gold, Cyberpunk, Neon, Ocean, Ember, Forest, Paper, Sky and Rose, and a choice that follows the device. Each has its own colours, corner shapes and type. The theme picker previews each theme with the real interface.
- The player title opens the album, and the artist name opens the artist page.
- Autoplay keeps similar music playing when the queue ends. It is on by default and can be turned off under Settings.
- Save playlists from other people, such as editorial playlists, from the playlist page.
- Space Grotesk and JetBrains Mono join the bundled fonts, with their licences.

### Changed

- The player keeps its controls at the bottom of the screen on every screen height and display size. The actions below the transport controls are equal columns with captions, so longer translations shorten a caption and never move a button.
- Playback buffers up to 90 seconds ahead. Lossless tracks used to run down to an empty buffer between loads, which paused the music after a short hiccup. A silent connection now fails after 12 seconds, loads retry for longer, and playback resumes by itself when the network returns.
- Covers that fail to load are requested again after a pause and when the network returns.
- Only the progress views redraw while a track plays. The rest of the screen no longer updates twice a second, which keeps scrolling and navigation smooth during playback.
- Settings are grouped into Account, Playback, Offline and downloads, Appearance, Listening, Data and Advanced. Bit-perfect USB output moved to Advanced. Scrobbling is named ListenBrainz.
- The wording of settings, messages and errors is revised.
- Home replaces Listen in the navigation.
- The theme setting is a single choice. Existing settings and backups map to the closest theme.

## [0.4.0] - 2026-11-01

### Added

- Playlist folders: Library shows your TIDAL folders and the playlists inside them. Create, rename and delete folders, and move a playlist into a folder with its folder button. Deleting a folder moves its playlists to the top level first.
- Translation support. All text in the interface, the widget, the notifications and the error messages comes from string resources, and the system language settings list every language that has translations. [docs/translating.md](docs/translating.md) explains how to add a language. Only English ships so far.
- TIDAL pages, such as Home and Explore, now ask TIDAL for text in the language of the device.
- Checks for translation: a script rejects text that is written in code, and a device test runs the main screens in a pseudo-language to show that no text is left untranslated.

### Fixed

- Missing fields from TIDAL no longer appear as the word "null" in lists.
- The button row at the bottom of the player wraps onto a second line when the labels do not fit, instead of squeezing them. This affects small screens and longer text.

### Changed

- The ATMOS badge is gone. TIDAL sends this client the stereo mix of every track that also has an Atmos mix, so OpenTune cannot play Atmos. The limit is listed in the usage guide.

## [0.3.0] - 2026-11-01

OpenTune now has its own application ID, `io.github.yyan115.opentune`. It installs next to the earlier prerelease builds and does not update them. Downloads and the TIDAL session from those builds do not carry over, so export any downloads you want to keep before you uninstall.

### Added

- Synced lyrics in the player. The current line is highlighted and centred, and a tap on a line seeks to it. Lyrics are kept on the device after the first load.
- Personal sections on Listen from TIDAL: your mixes, radio stations, albums based on what you played, new releases and editors' playlists. Pull down to refresh.
- Explore in Search: genres, moods, decades and the Hi-Res page. Recent searches stay on the device.
- Search shows a top result and has tabs for tracks, albums, artists, playlists and videos.
- Artist pages with biography, popular tracks, albums, singles, playlists, videos and related artists. Follow artists and play artist radio.
- Album pages with TIDAL's review, quality badges and full credits. Mix pages for your daily mixes.
- Track radio and track credits in the track menu.
- Library lists followed artists and can filter and sort saved tracks and albums. Shuffle plays all filtered tracks.
- Sleep timer in the player: 15 to 90 minutes, or the end of the current track.
- Volume normalization from TIDAL's ReplayGain values, by track or by album. It is off by default.
- Equalizer with presets, using the equalizer that Android provides.
- Audio output information in Settings, showing where music plays and which sample rates the output accepts.
- Bit-perfect output to a USB DAC on Android 14 and later. It is off by default.
- Listening history on the device, with a statistics view for top tracks and artists.
- Optional scrobbling to ListenBrainz or a compatible server. It is off by default and queues listens while the server is out of reach.
- Music videos from artist pages and search, with picture-in-picture.
- Back up and restore for settings, device playlists and listening history.
- Keep saved tracks offline: downloads your saved TIDAL tracks in the background.
- Move tracks up and down in your playlists, on the device and in TIDAL.
- Mixes in Library, "Playing from" in the player and saving the queue as a playlist.
- Browsing for cars and Bluetooth devices, including Android Auto, and voice requests such as "play Nujabes".
- A home screen widget with the current track and playback buttons.

### Changed

- Sign in through the browser only. The device-code option, the pasted-link option and the client override setting were removed.
- OpenTune no longer appears in the Android share sheet.
- Releases are built, signed and published by a GitHub Actions workflow with a build provenance attestation. They use a new signing key, listed in the README.

## [0.2.0] - 2026-10-31

Published under the application ID `io.github.yyan115.undertow`.

### Added

- Gallery design across the whole app, with serif headings, custom icons and a floating player.
- Seven color themes, each with light, dark and system appearance.
- Source details in the player showing codec, bit depth and sample rate.

### Changed

- Renamed the app to OpenTune.

## [0.1.1] - 2026-10-30

### Added

- Browser sign-in that returns to the app automatically.

### Changed

- Lossless quality settings reject lossy responses.
- New installs default to lossless quality on Wi-Fi and mobile data.

## [0.1.0] - 2026-10-30

### Added

- TIDAL search, collection browsing and full-track lossless playback.
- Downloads that play without an account.
- Export of downloads as tagged FLAC files without re-encoding.
- Local audio import, device and TIDAL playlists, queue editing, shuffle and repeat.
- Background playback with notification, lock screen and Bluetooth controls.
