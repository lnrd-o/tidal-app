# D'JAI: automatic mixing for OpenTune

Design notes, first approach. Nothing in here is built yet.

D'JAI plays a queue as one continuous mix. It picks tracks that fit, lines up their beats and phrases, matches their loudness, and hands the low end from one track to the next at a phrase boundary. The method follows the DJ methodology in the brief: compatibility, phrasing, gain, EQ blend, exit.

## 1. Pipeline

```
analysis (per track, cached)  ->  planner (per pair)  ->  engine (real time)  ->  UI
 bpm, beat grid, key/Camelot,     score + choose            two decks, LR4 EQ,      toggle, plan view,
 energy, loudness, phrase map     out/in points + length    tempo, gain, swap       signal path stage
```

The first two stages are pure Kotlin in `core/`, so they are unit-testable on the JVM with synthetic audio. Only the engine touches Android.

## 2. Analysis (what we know about a track)

| Value | Source | Notes |
| --- | --- | --- |
| BPM | TIDAL track metadata if present, else measured | **To verify:** whether the TIDAL API returns `bpm`, `key`, `keyScale` for our session. `Track` has none of them today. Measured fallback: spectral flux onset curve, autocorrelation, then a refinement pass; resolve half/double-time. |
| Beat grid | measured | First downbeat offset plus constant BPM (most pop/dance/hip-hop is 4/4 at constant tempo). Variable-tempo tracks are flagged "not mixable". |
| Key / Camelot | TIDAL metadata, else measured | 12-bin chroma from the FFT, Krumhansl-Schmuckler profile correlation, mapped to Camelot (8A, 8B...). Keep a confidence value. |
| Energy 1..10 | measured | Loudness, spectral centroid, onset density, low-band energy per section, normalised across the library. |
| Loudness | ReplayGain from the stream (`app.sources.gains`) or measured | Used for gain staging. |
| Phrase map | measured | The beat grid cut into 16/32-beat blocks with an energy value each. Intro and outro are the first and last runs of low-energy, low-onset blocks. Breakdown = a block run with the kick band near zero. |

Results are cached per track id in a new `track_analysis` table in `LibraryStore`.

**When can we analyse?** Downloaded tracks are files, so a `WorkManager` job can analyse them at download time. A streamed track exists only as a stream, and the player gives us PCM only while it plays. So streams need either metadata from TIDAL, or a background decode of the next track while the current one plays (phase 5).

## 3. Planner

For the track that plays now (A) and each candidate (B):

1. **Hard filters:** BPM within +/-4% (also x2 or /2, then retimed), key relation allowed, B not played recently (`RepeatPolicy`), both analysed with confidence above a threshold.
2. **Score:** `bpm`, `key` (same 1.0, +/-1 on the wheel 0.9, relative A<->B 0.8, +/-2 energy-boost 0.5, otherwise 0), `energy` (distance to the target curve: build, hold, release), `loudness` (gain needed), `taste` (the existing `AiRecommendationEngine` affinity). Weights are settings and the starting values are tuned by listening.
3. **Transition plan:** `outBeat` in A (the next 32-beat boundary at or after the start of A's outro), `inBeat` in B (its first downbeat, or the start of a phrase if B has a long silent intro), `length` (16 or 32 beats, shorter if A's outro is short), `speedB` (A's BPM / B's BPM, applied over the whole overlap and eased back to 1.0 afterwards), `gainB`, and the **EQ automation**: B low band closed at the start; B mids and highs opened over the length; at the final downbeat, **bass swap** (A low closes, B low opens); A mids and highs out over the next 16 beats. A high-pass or echo exit is chosen when A's phrasing runs out.
4. **Sequencing (the "AI" part):** with a queue of N tracks, choose the order that maximises the sum of pair scores and follows the energy curve. It is a small path-optimisation problem (beam search is enough for N up to a few hundred). The result is shown as a proposed order that the listener can accept.

A rules-and-DSP core is predictable and testable. A language model is not needed for the mix itself. It could name a set or choose an energy curve from a sentence ("sunset drive"), which sends only the text prompt, never the listening history.

## 4. Engine (the hard part)

Requirements: a second track plays under the first with **sample-accurate** alignment, with per-band EQ, gain and time-stretch.

Two simultaneous `ExoPlayer`s mixed by Android drift by milliseconds and the kick drums flam. Instead, **mix inside the audio chain of the main player**, where one output clock drives everything:

- **Deck A**: the existing player. Its `AudioProcessor` chain gets a `DjMixProcessor` beside `SpectrumProcessor`.
- **Deck B**: a second `ExoPlayer` whose audio sink is a `BufferingAudioSink` that writes decoded PCM into a ring buffer instead of an output. B decodes a little ahead of A.
- `DjMixProcessor` reads B from the ring buffer by sample count, resamples it to A's rate if they differ (44.1/48/96 kHz), applies the **LR4 3-band crossover** (about 250 Hz and 2.5 kHz) to both decks, multiplies each band by its automation curve, and sums.
- **Tempo:** `PlaybackParameters(speed)` on deck B (Sonic, pitch preserved), within +/-4%.
- **Handing over:** when the overlap ends, deck A must continue with B's track. Options: (a) swap the session's player to deck B (`MediaSession.setPlayer`), (b) a custom `MediaSource` that splices. (a) is simpler and is the first thing to prototype.

Costs and limits: two decoders and a resampler (fine on a flagship phone, to measure on mid-range), the notification and widget must follow the swap, and **bit-perfect USB mode is incompatible with mixing**, so the signal path sheet must show a "Mixing" stage and the verdict "Processed" while D'JAI is blending.

## 5. UI

- A square **D'JAI** toggle in the player (and on the Home flow card), in the style of the rest of the app.
- A plan strip in the player: two lanes of square cells showing both beat grids, the overlap, and the bass-swap marker. Gesture: long-press the strip to skip or shorten the next transition.
- Queue sheet: BPM and Camelot per row, compatibility colour (one colour, brightness only), and an "Optimise order" action.
- Settings: BPM tolerance, transition length (auto, 16, 32), key strictness, energy curve.

## 6. Plan

0. **Spike** (a day or two): two local FLAC files, `DjMixProcessor` with fixed offsets and a hard-coded bass swap. Question answered: is sample-accurate mixing in the chain feasible, and does the player swap work with Media3?
1. **Analysis engine** in `core/`: BPM and beat grid, chroma and key, energy, phrase map. Tests with synthetic click tracks and chords, and with the test FLAC.
2. **Planner** in `core/`: scoring, plan, sequencing. Tests on fixtures.
3. **Engine**: mixer, automation, swap handover, notification following.
4. **UI and settings**, signal path integration.
5. **Streaming**: pre-analysis of the next track, TIDAL metadata, tuning with real music.

## 7. Open questions

- Does the TIDAL API give BPM and key to this app? (Check with a signed-in session.)
- Scope of the first version: only downloaded tracks (analysis is easy), or streams too?
- Auto-reorder the queue, or keep the order and only choose how to transition?
- TIDAL's terms: the app is unofficial, and D'JAI decodes and processes streams. Fine for personal use, but it is a reason to keep the first version local and optional.

## 8. Decisions

- **Scope: streams too.** The first version must mix streamed tracks, so TIDAL's BPM and key metadata (or a background decode of the next track) is needed from the start. Downloaded tracks stay the easy case and get exact analysis.
- **Control: propose an order.** D'JAI suggests an order and the listener accepts it with one tap. The queue never changes by surprise.
- **First step: the mixer spike (phase 0).** It answers the riskiest question first: sample-accurate mixing in the audio chain, and the handover to the second player. The first thing to check in parallel is whether TIDAL returns `bpm` and `key` for a signed-in session.

## 9. Cue points, loop and roll (0.8.1)
D'JAI uses these by itself. There is no pad and nothing to press.

- **Cue points.** `SectionAccumulator` keeps half-second slices of the whole playthrough (bass below 150 Hz, loudness, onset strength) from the frames of `OnsetEnvelope`. `SectionDetector` turns them into bars from the grid and the phrase origin, scales bass (0.5), loudness (0.3) and onsets (0.2) into one energy per bar, splits the song into loud and quiet runs (short runs merged), and marks INTRO, BUILD (8 bars before a drop), DROP (a loud run after a quiet one with the bass rising), BREAK (a quiet run after a loud one) and OUTRO (the last quiet run). They are found at the swap, when the outgoing song has been heard from its start, and kept in `LibraryStore` (`cue_points`, schema 8). A song heard once has its cues next time.
- **Loop primitive.** `DjMixer` can loop a deck (`setLoop`, `endLoop`): when the read position reaches the loop end it wraps to the start, with a 5 ms fade between the two so there is no click. A `shadow` position keeps the time that the deck would have without the loop, and ending with `toShadow` returns there.
- **Roll.** `MixPlan.roll` (`RollPlan`) makes the outgoing deck repeat, over the two beats before the swap, its first half beat twice, then a quarter beat twice, then an eighth beat four times, and return to its own time at the swap. Beat mixes of two songs with energy 5 or more get it.
- **Cue-aware plan.** `MixScheduler.schedule` starts the blend at the outgoing song's OUTRO cue when it fits, and enters the incoming song at its DROP minus the blend, so that its drop falls on the swap (`MixPlan.dropEntry`). A far drop is reached by starting the incoming player at `drop - 32 beats - 16 s` (`DjEngine.startForDrop`). The planner waits for enough of it to be decoded unless the outgoing song is nearly over.
- **Setting.** "D'JAI effects" (default on). The strip shows ROLL, DROP and OUTRO while a plan uses them.
- **Limits.** Cues exist only for songs that were heard from their start with D'JAI on, so the first play of a song is mixed as before. Chorus labels are not made. A loop that extends a song that ends too soon is not built yet.

## 10. Listening ahead at 8 times the speed
The songs ahead are listened to by a hidden player that nobody hears (`DjEngine.startScout`). It used to play in real time, so each song took 17 to 20 seconds and six songs took about two minutes. Now it plays at the player's top speed of 8x (`SCOUT_SPEED`). The tap (`DeckTapProcessor`) sits before the speed change in the audio chain, so the ring still holds the song at its own speed and the analyzers need no change. Twenty seconds of a song (`SCOUT_SECONDS`) are read in a few seconds, and twelve songs ahead (`SCOUT_COUNT`) are covered. The listening starts right after a manual change of song, without waiting for the song that plays to be analysed. What was learned is kept (`djai_profiles.tsv`), so a song is listened to once.

## 11. Analyze for D'JAI, and what is kept
The menu of a song has an Analyze for D'JAI button. `Preprocessor` reads the whole song with a hidden player on a deck of its own (`DjMix.ANALYSIS_SLOT`, whose tap always copies and whose sound is never heard) at four times its speed, with a full estimate every 20 s of audio so that the analyzer keeps up. It ends with the tempo, key, energy and cue points, which go to `DjEngine.learn` (the profile) and `LibraryStore.saveCues`.

What is kept, and where:
- **Profile** (tempo, period, confidence, beats per bar, key, energy, rate): `djai_profiles.tsv`, up to 1500 songs, written at most once every 3 s and all together. A song with a profile is never listened to again by the listening ahead, and an analyzed song replaces its short profile.
- **Cue points**: `cue_points` in the library database. A song whose cue points are kept is not analyzed again (the button says so), and the swap does not find them again.
- Not kept: the online hints (TIDAL, Deezer), which are cheap to ask again. Two recordings of the same song under different track ids are analyzed twice; keying by ISRC would join them.

## 12. Off means off
With D'JAI off (`DjEngine.configure`) nothing of it runs in the background: a prepared transition is dropped, the listening ahead and its hidden players end, the analyzers stop, the online questions stop, and `Preprocessor` waits (a song that was being read is put back in line). Songs that were learned stay learned. With D'JAI on again the engine starts again for the song that plays: its analysis, the listening ahead and the questions to the catalogue, and the queue of songs to prepare goes on. The deck that prepares songs has its own stream, which the engine never clears.

## 13. Echo wash, groove and the arc of a set
- **Echo wash** (`Echo.kt`, `MixPlan.echo`). A soft transition (songs whose tempos or grooves do not match, 8 seconds) sends the outgoing song into a stereo ping-pong echo (`EchoTail`, three quarters of a beat of the outgoing song, 62 percent feedback through a 3.2 kHz low-pass, so each repeat is darker). The send opens at 2 percent of the transition and closes at 90 percent, and the repeats go on ringing for 4 seconds after the transition, over the new song. It needs D'JAI effects on.
- **Groove** (`Groove.kt`, `Swing.measure`). The hats and shakers (above 5 kHz) are folded onto one beat of the kick-aligned grid, and the off-beat eighth is looked for between 0.4 and 0.7 of the beat: 0.5 is straight, about 0.62 is swung, 0.67 is a triplet shuffle. `DeckAnalyzer.swing` keeps it and the profile file has a ninth column for it. In `MixPlanner.decide`, two grooves more than 0.10 apart are not locked on the beat (a soft transition with the echo instead), more than 0.06 apart get the short 16-beat blend, and when both are known the groove is 15 percent of the match (tempo 35, key 25, energy 25).
- **Arc of the set** (`Arc.kt`, `EnergyArc`). The engine remembers the energies of the last six songs. A next song that would prolong a run of three or more loud songs (7 and above) or quiet ones (3 and below) scores low, and one that breaks the run scores high. In `MixPlanner.choose`, a next song that goes well (70 percent or more) but scores below 0.4 gives way to another that goes well and scores 0.65 or more. Nothing is replaced by a song that does not go well.

## 14. The D'JAI page in Settings, and the marks in lists
- **Page.** Settings has one D'JAI row (with On and the number of songs kept, or Off) that opens `DjaiPage` (`ui/DjaiSettings.kt`). Everything D'JAI does is there, and each setting acts at once: the automix switch; the filter sweeps, loop roll, cue points and echo wash; matching grooves, shaping the set, the match below which the next song is changed (50 to 90 percent, default 70) and how many songs are listened to ahead (4 to 16, default 12); showing D'JAI data in lists, the online source for tempo and key, the count of songs kept, and a button that forgets everything that was learned. The settings reach the engine as `DjOptions` (`DjEngine.configure`); the older single "D'JAI effects" setting is read as the starting value of the roll, cues and echo.
- **Marks.** A song that D'JAI has kept something about (a profile or cue points) shows a G, for guardado, at the start of the line under its name. With "D'JAI data in song lists" on, the tempo, key and energy show under the artist, for example `126 BPM · 8A · E7`. The data comes from `DjMix.known`, which the engine publishes (`DjEngine.publishKnown`, a moment after the last change) and which `MainActivity` hands to `TrackRow` as `LocalDjKnown`.

## 15. Verse, chorus, bridge and peak
The energy reading of section 9 is right for dance music but sees nothing of a song with verses and choruses, so `SectionDetector` has a second, structural reading (`Sections.kt`). `SectionAccumulator` now keeps, for each half second, the bass, middle and top bands and the harmony (the share of each of the twelve pitch classes, from 220 Hz to 2.5 kHz).
- The song is cut into blocks of four bars from the first bar. Each block has the harmony of eight parts of it, minus the mean harmony of the song (so that the key does not make every block alike), scaled to length one; a difference smaller than noise counts as no harmony, and two parts with none are alike.
- Two blocks are alike by 80 percent harmony and 20 percent intensity. A section begins where a block is not alike the one before it (novelty above the mean plus half a standard deviation, at least 0.3), so sections begin on a phrase.
- Sections that are alike by 0.62 or more (their blocks side by side) are one group. The **chorus** is the repeated group with the highest intensity (above the median), helped a little by coming back often. The **verse** is the other repeated group that comes first, as long as it is not much quieter than the median (so not an intro or an outro). The **bridge** is a one-off in the late part (45 to 95 percent of the song) that is alike to no other group by more than 0.55. The **peak** is the two blocks in a row with the most intensity.
- A drop and a chorus at the same moment are both kept: the key of `cue_points` is the song, the moment and the kind (schema 9, which clears the cue points of version 8: they are found again when a song is heard or analyzed). The pins of the player show the drop, the chorus and the peak brighter than the rest.
- The planner does not use the new cues yet: the entry is still at the drop and the exit at the outro. They are what a medley of sections would be built from.

## 16. Keeping the match of the choice on the mix
The match that the listening ahead saw for the next song could fall by the time the mix was planned. The causes, and what each now does:
- **A guess counted as a measure.** A song known only from a catalogue (a tempo and a key, no energy and no groove) was scored as neutral on energy and could keep the next place with a good-looking match. It is now a `Candidate` with `verified = false`, its match is lowered by 0.10 (`GUESS_PENALTY`), and a song that was listened to takes the place if it goes well.
- **Less was read at the mix than at the choice.** The plan read 14 s of the next song, the listening ahead 20 s. The plan now reads 18 s (`READ_SECONDS`) and starts earlier (`PREROLL_MS` 75 s), and takes the key, energy and groove from the profile that the listening kept (`profiles`), with the live grid for the beats. The choice and the plan see the same song.
- **A fallback to a soft mix lowered the match.** When a beat mix could not be placed before the song ends, the plan became soft with a tempo score of 0.2, which cut the match. The soft plan now keeps the scores of the pair, since the songs are as alike as they were.
- **A low match is checked and given another song.** After the plan reads the songs, if the match for the mix is below the limit and the outgoing song has more than 48 s left, the songs ahead are scored again with the same data (`DjEngine.rechoose`). One that goes at least 5 points better (and reaches the limit) takes the next place, and the next song is prepared again, once for each song. The log says `D'JAI match for the mix` with the parts of the match and the match at the choice, and `D'JAI changed the next song` when it changes it.

## 17. Following the kick for the tempo
The comb filter of `BeatTracker` reads the whole band below 4 kHz, so hats and snares that divide the beat can make a tempo look double (or three to two). `OnsetEnvelope` now also keeps the onset strength of the kick alone (below about 130 Hz, `lowTail`), and `BeatTracker.followTheKick` settles the tempo with it: for the tempo that was found and for its half, double, two thirds, three quarters, four thirds and three halves (within 70 to 180 BPM), it measures how much more the kick falls on the beats of the grid than half way between them (`kickContrast`). At the right tempo the kick sits on the beats and nothing is between them; at double, half of the beats are empty; at half, the skipped beats have kicks half way. A new tempo is taken only when the kick has a contrast of at least 0.4 there and 1.3 times the contrast at the old one. Songs with no kick keep the tempo that was found. The profile file is now version 2, so tempos that were kept wrong are read again.

## 18. The blink of the tempo
`Modifier.beatPulse` (`ui/BeatPulse.kt`) is not a Compose animation: those are stretched by the animation scale of the system (a developer option, and battery saver can stop them), and the strip changed the tempo of its animation at each estimate, which started the cycle again. The blink now reads one frame clock (`BeatClock`, run by `BeatClockHost` at the root, only while something blinks) and works out where it is in the beat from the real time. In the D'JAI strip the tempo of the song that is heard blinks on its beats: the engine writes the grids of the two decks to `DjMix.gridOut` and `gridIn`, and `DjMix.beatPhase` places the position of the deck in the mixer, less about 0.1 s for the output, on its grid. The incoming song does so once the mix plays it. A paused mixer holds the blink where it is. In the song lists, where nothing is heard, the tempo keeps time on the clock.

## 19. Audit: why mixes sounded random, and the cue
- **The incoming player ran ahead.** It began to play (silently) at the preparation, 75 s before the end, but the mix read it from its first beat much later. By the start of the mix the player was 30 to 50 s ahead. The ring holds 50 s, so the entry was often overwritten, and the mixer then jumped to the newest frame: a random place, off the beat. After the swap the player stayed that far ahead of what was heard, so the next mix was timed too early and cut the song in the middle. Raising the preparation to 75 s made it worse.
- **The fix: cue and hold, as a DJ does.** Once the plan is made, the incoming player is sent to 1.5 s before the entry (`DjEngine.cue`), paused, and the plan reaches the mixer only when the seek has reached the ring (`cueing`), with the entry moved to its new frame. Just over 2.5 s before the mix (`ready`), the player is let go; it decodes from the cue in real time, so the entry is in the ring when the mix reads it, and the player and the music are in step after the swap. The mirrors of play and pause leave a held deck alone. A second flush moves the entry again. The analyzer of the deck starts again at the cue and knows its song time, so songs are still learned from their start. A drop no longer has to be decoded before the mix: the cue goes to it.
- **The tempo of the incoming song eased back too soon.** It eased from the swap on, while the outgoing song still played, and the two drifted apart. It now stays matched for 70 percent of the tail.
- **Three-to-two tempo changes** need 1.6 times the kick contrast (half and double, 1.3), and three-quarters and four-thirds are no longer tried: a syncopated kick could look like them.

