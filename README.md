# ChessMonger Android — v2.1.0

## What v2.1.0 changed (UI recode + Stockfish settings)

- **Home screen recoded, not just rearranged:** title, one-line
  subtitle, permissions line (the overlay button only shows
  when overlay is *not* allowed), Play as (White/Black/Either),
  one big Start/Stop button (it becomes Stop when running),
  a short status line, an Engine summary line, one
  "Engine settings" button, and a collapsed "More" section
  (FEN, Resync, tracker log, crash report, updates). The old
  screen showed all of those buttons plus a long paragraph
  at once. The overlay itself is unchanged except for the
  MultiPV line below.
- **Stockfish is now configurable:** Engine settings dialog
  (persisted, so it survives relaunch) — think time
  (500–10000 ms), Skill Level (0–20, 20 = strongest),
  Limit Strength + Elo (1320–3190, for a human-like
  opponent), MultiPV (1–5), Threads (auto or 1–8), Hash
  (auto or 16–1024 MB), Contempt (-100–100). Defaults are
  the old max strength, so nothing changes unless you
  change it. Settings apply on the next Start (the dialog
  says so) — the engine process is created with them, and
  restarting it mid-game would drop the current search.
  MultiPV > 1 draws a second line on the board with the
  2nd/3rd moves and their evals; MultiPV = 1 (default)
  shows nothing extra, so the board stays uncluttered.
- **Testable engine config:** `core.EngineSettings`
  (defaults, clamping, UCI commands, summary) and
  `core.UciParser` (info-line parsing, including multipv)
  are pure Java and covered by `EngineSettingsTest`.
  Total tests: 21/21 pass.


Screen-vision chess assistant for Android 8–17 (API 26–37):
watches the screen via MediaProjection, finds the 8×8 board,
tracks moves by last-move highlights then occupancy against a
full legal-move generator, asks bundled Stockfish 17.1 at max
strength, and draws the move as a touch-transparent overlay
arrow. Suggestion-only: it never taps or plays for you.

## What v2.0.0 changed (full rebuild, not a patch)

v1.1–v1.12 grew by patching one 1,334-line `CaptureService`
and a 746-line `BoardDetector`, with tests only in `/tmp`
and a Python port that could drift from the Java code.
v2 rebuilds the project around a clean split:

- **`core` (pure Java, no Android imports)** — the only code
  that decides what is on the board and what move was played:
  - `ChessBoard` — full legal-move generator, FEN, castling,
    en passant, promotions, insufficient material.
  - `DetectorCore` — board finding (grid + checkerboard),
    occupancy, highlights, RGB calibration. Operates on
    raw `int[]` pixels, so it runs unchanged on the phone
    and on the JVM in tests. This is the v1.12 detector
    logic (RGB per-parity calibration, adaptive highlights,
    board-hidden threshold 15), ported, not reinvented.
  - `Tracker` — the position tracker as an explicit state
    machine: board lock, hidden guard, orientation trial,
    baseline confirmation (2 frames), move confirmation
    (2 frames with highlights, 3 without), desync after
    8 stable frames, stuck-waiting recovery after 40
    frames, small-delta search, automatic recovery
    (exact, 1–2 move forward, start candidate, history
    rollback up to 50), new-game detection, checkmate
    pause. Every threshold from v1.12 is preserved and
    documented in the class header — changing one is a
    test change first, not a magic-number edit.
  - `TrackerLog` — ring buffer (200 entries) of moves,
    recoveries, desyncs, new games and checkmate pauses,
    with FEN and delta. Shared from MainActivity, so a
    future "it froze here" report can include what the
    tracker actually did.
- **`android` (thin shell)** — `CaptureService` is now only
  capture, overlay and Stockfish execution (~500 lines,
  down from 1,334). It converts a Bitmap to pixels once
  per frame and acts on `Tracker.Result` (status, clear
  suggestion/board, board rect, FEN to analyse). All the
  hard-won Android-only details are preserved: the
  MediaProjection callback registered before
  `createVirtualDisplay` (API 34+), full stop before a
  restart, callback unregistered before `stop()`, and no
  zombie service on a restart with no intent.
  `BoardDetector` remains as a thin wrapper around
  `DetectorCore` for old callers; new code uses the core.
- **Tests in the repo** (`app/src/test/java/...`, JUnit 4):
  `ChessBoardTest` (opening, Fool's Mate, castling, en
  passant, promotion, FEN, forward search),
  `DetectorCoreTest` (10 board themes rendered as phone
  screenshots, highlights, checker distance, board
  finding on a full screenshot), `TrackerTest` (opening
  game, checkmate pause + new-game resume, missed-move
  recovery). Run them with the core-tests CI job, or
  locally with `javac`/`java` + JUnit (see CI workflow).
  **Result: 17/17 pass.**
- **Gradle + CI** (`settings.gradle`, `app/build.gradle`,
  `.github/workflows/android.yml`): AGP 8.8, Gradle 8.10,
  compileSdk 36, targetSdk 37, minSdk 26. The core-tests
  job needs only a JDK; the Android job builds a debug
  APK. Honest limit: this Gradle setup is new in v2 and
  its first CI run is the real validation. The shipped
  v2.0.0 APK was built with the verified manual pipeline
  (`build.sh`, same as v1), which remains the fallback.

## What did NOT change (deliberately)

- Stockfish 17.1, arm64-v8a only, bundled as before
  (62 MB APK, same as v1). Splitting per ABI or
  downloading on first run would shrink it, but would
  change first-run behaviour and was not worth the risk
  in a rebuild that must first prove parity.
- Auto-update: GitHub Releases, in-app download,
  SHA-256 verification, system installer (same as
  v1.11). v2.0.0 is a normal release, so v1.12 phones
  pick it up on fresh start. `version.json` format is
  unchanged: versionCode, versionName, apkUrl, sha256,
  notes (user-facing only).
- Piece-type recognition is NOT in v2.0.0. The rebuild
  makes it easy to add (a `PieceClassifier` can plug
  into `DetectorCore` and `Tracker` without touching the
  shell), but template matching on real Chess.com piece
  shapes was not proven in the harness — the harness
  pieces are simple shapes, not real piece art — so it
  is documented as the next step, not shipped unproven.

## Building

Manual (verified, used for the shipped APK):
```
./build.sh   # needs ~/workspace/tools/android/env.sh, JDK 17, SDK 36, build-tools 34.0.0
```
Output: `build/ChessMongerAndroid.apk`, signed with the
debug keystore (same as v1; a Play release would need a
real signing key, which is not in this repo).

Gradle (new, first CI run validates):
```
./gradlew :app:assembleDebug
```

## Release flow (same as v1.11+)

Build APK → sha256 → write `version.json` (versionCode
incremented by 1, `apkUrl` = that release's asset URL)
→ update `version.json` + `README.md` on `main` (no APK
on main) → create the GitHub Release with the APK and a
file actually named `version.json` as assets → verify
via `https://api.github.com/repos/brandonmathew/chessmonger-android/releases/latest`
(tag, draft false, prerelease false, asset digests match
the build) → deliver the APK and update MEMORY.md.

## Livetest harness (v1.12, still the colour proof)

`~/workspace/livetest/` (not in the APK): Stockfish 17.1
x86 plays itself, each position rendered in 10 board
colours, detector run on every frame. Old detector:
41/41 on most themes, 0/41 on beige, white-on-white,
black-on-black. With the v1.12 fix (now in
`DetectorCore`): 41/41 on all 10 themes, highlights
40/40. The Java `DetectorCoreTest` is the same idea in
the repo, on rendered Java images.
