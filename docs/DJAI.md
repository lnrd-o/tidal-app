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
