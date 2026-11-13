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

