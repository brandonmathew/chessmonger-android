# ChessMonger Android

Desktop ChessMonger, recreated as a pure-Java Android app. It watches
your phone screen, finds the chessboard in whatever chess app is in
front, tracks the game, asks Stockfish 17.1 at max strength for the
best move on your turn, and draws that move as a green arrow on top
of the board. It never taps or plays for you — suggestion only.

## v1.11.0: GitHub Releases auto-update with download & install

Replaces the v1.9.0 raw-main version.json check, which
suffered GitHub raw CDN caching (v1.10.0 was pushed but
raw still served v1.9.0).

- UpdateChecker now calls the GitHub Releases API for
  the latest release, reads its version.json asset for
  versionCode / sha256 / notes, and downloads the APK
  asset from that release. A release asset URL is unique
  to its release, so it cannot serve a previous version
  by mistake, and release assets allow up to 2 GB, so
  the 50 MB warning is gone and APKs no longer bloat
  git history.
- Download & install, as requested: tapping "Download
  & install" downloads the APK in the app (no browser),
  verifies its SHA-256 against version.json (a mismatch
  deletes the file and never launches the installer),
  then opens the system installer automatically.
  Honest limit: Android does not let a normal app
  install silently. You still tap Install once in the
  system installer, and the very first time you must
  allow "Install unknown apps" for ChessMonger, which
  the dialog opens in Settings for you. After that it
  is download + one Install tap.
- No AndroidX FileProvider is available in the manual
  build pipeline, so a minimal UpdateFileProvider
  (ContentProvider, serves only the update APK from
  the app cache dir) hands the file to the installer.
  Manifest gains REQUEST_INSTALL_PACKAGES and that
  provider entry.

## v1.10.0: robust new-game detection

The user played a whole match on v1.9.0, but new-game
detection after it was fragile and failed. Evidence from
the three screenshots he sent (all 945x2048, wooden
Chess.com board, rect 0,608-945,1553):

- Start-position screenshots (both the first game and the
  next game) read exactly as the start position, and the
  checker distance at that rect is ~120, far above the
  25 hidden threshold — so the board itself is not the
  problem, and the win-popup screenshot correctly reads
  checker distance 2.5 (hidden).
- The win popup ("You Beat Zach! by Checkmate") is the
  break: it covers the board, so the hidden-board guard
  (which runs before any tracking) holds the position,
  and after 24 hidden frames (~6 s) it cleared the
  whole tracking state — including the checkmate pause.
  The tracker then forgot the game had ended.
- After that, a new game looked like a desync from the
  mated position, not a new game: the old code required
  two start frames judged against prevOccupancy, but
  prevOccupancy is stale after a hidden period and null
  after a lock release, so the first visible start frame
  never counted; and recovery only tried the tracked
  position, its forward moves, and the last-50 history —
  a whole match is longer than 50 plies, so the start
  had usually fallen out of history. If the user also
  played the first move before two untouched start
  frames appeared, no path at all led back to the new
  game.

Fixes:

- New-game counting uses a dedicated newGameFrames
  counter, not prevOccupancy. It is not reset by hidden
  frames or by a lock release, so the first visible
  start frame counts. Two start frames still required,
  so one noisy frame cannot reset a game.
- On a new-game trigger the baseline is set directly:
  two identical start frames ARE the confirmation, so
  there is no "confirming position…" limbo afterwards.
- The checkmate pause (and a new gameEnded flag, set
  on checkmate and on any other game-over) now survives
  the board-hidden timeout and a lock release. Only an
  explicit Resync / FEN / Start, or a confirmed new
  game, clears them.
- Recovery now tries the starting position as a base,
  exact or via a 1-2 ply forward sequence, with history
  reset to the new game. That catches the fast-first-
  move case: a new game where the user already played
  1.e4 (or 1.e4 e5) before two untouched start frames
  appeared now recovers to the new game instead of
  staying "out of sync" forever.

## v1.9.0: Kxh8 freeze, checkmate pause, GitHub auto-update

The user reported the tracker froze at 15...Kxh8, stuck on
"Waiting for opponent... · move 15" while the game was already
on move 16, and only recovered after undoing several moves.
Tested against that screenshot, v1.8's detector reads the
post-Kxh8 board perfectly (highlights exactly g8/h8, board
delta 0) and the move generator accepts Kxh8 (in check, the
only legal black moves are Kg7 and Kxh8) — so the static case
is not the bug. The freeze is a live-tracking state issue: a
single missed move that leaves a persistent 1-3 square
difference, with a flickering square or an already-tried
search, could keep the tracker waiting forever, because the
small-delta search only ran on a stable frame with a changed
occupancy, and waiting itself never timed out. Fixes:

- **Stuck-waiting watchdog:** every frame with a persistent
  1-3 square difference counts, regardless of stability. At
  40 frames (~10 s) it forces recovery (exact match, 1-2 move
  forward sequence, history rollback) instead of waiting
  forever. A legitimately slow opponent has delta 0, so this
  never fires on a normal wait.
- **Periodic small-delta retry:** every 20th stuck frame, the
  depth-1 forward search retries even if the frame is not
  stable, so flicker cannot block recovery forever.
- **Checkmate pause (as requested):** when the tracked
  position is checkmate (no legal moves AND in check — not
  stalemate or insufficient material, which still show their
  text as before), tracking pauses completely: no board
  changes are detected, no suggestion is drawn, and the
  status is "Checkmate — paused until a new game starts".
  It resumes only when the starting position is detected
  for two consecutive frames, or on an explicit Resync / FEN
  / Start. Undoing a checkmate move does NOT resume it, by
  design — only a new game does.

**GitHub auto-update (as requested):** UpdateChecker fetches
version.json from a raw GitHub URL on every fresh start of
the app (MainActivity.onCreate, not onResume, so switching
back from the chess app does not re-check), compares
versionCode, and if newer, shows the notes with a Download
button that opens the APK URL in the browser (tap the
downloaded file to install — Android requires that approval
for sideloaded apps anyway). A manual "Check for updates"
button does the same check with a toast result. If the URL
is 404, the network is off, or the JSON is malformed, the
check silently does nothing — important while the repo does
not exist yet. To make it live: create a GitHub repo, put
this version.json and the APK on its main branch at the
paths in UpdateChecker.UPDATE_URL / apkUrl, and change those
two constants if your repo name or branch differs. The raw
URL is raw.githubusercontent.com, the standard raw file host
for GitHub.

## v1.8.0: full review after the "Waiting for opponent" screenshot

The user sent a screenshot (2026-10-23, wooden Chess.com board,
move list ending "... 6. Nc3 Bd7") with the overlay stuck on
"Waiting for opponent..." after Black had already played Bd7.
The board in that screenshot reads perfectly with the detector
(delta 0 to the true post-Bd7 position, delta 2 to the pre-Bd7
position), and Bd7 is a legal move scoring 2 in the move
generator — so the miss was in the detector/service layer, not
the position. Fixes, all proven against that screenshot:

- **Highlights were garbage on a wooden board.** The old
  absolute colour test fired on every bare light square (light
  wood IS yellowish, 200,165,110), giving a 23-24 square
  highlight list, never 2, so the highlight fast path never
  ran. Highlights are now relative: a square whose background
  is far from its parity's median background is tinted. On
  that screenshot c8 and d7 are the two farthest backgrounds
  (36 and 40, next square 27), so a threshold of 30 gives
  exactly the Bd7 pair, and the 2-frame highlight confirmation
  now actually works on this board.
- **Centre sample was rect-shift fragile.** At the old 0.35
  inset, a white pawn's luminance swung 163-208 across a
  +/-2 px locked-rect shift (the detector's downscaled refine
  step is ~4 full-res pixels), crossing the background
  threshold and flipping to black. At 0.42 it stays 208-224,
  and the whole board still reads exactly at +/-2 px and
  +/-4 px shifts.
- **Desync wiped calibration.** Removing the learned
  white/black means on desync fell back to "brighter than its
  square = white", which on wood is only ~7 luminance apart
  for a black piece on a dark square — recovery then
  misclassified those pieces and could never recover. Desync
  no longer resets calibration; recalibration still happens
  on every 50th exact match, and Resync re-learns it.
- **Stale Stockfish arrows.** Analyses queue on one engine
  thread, so a move made while Stockfish was thinking used to
  draw an arrow for the old position (the old guard only
  checked the board rect, which had not changed). The result
  is now dropped if its FEN is no longer the tracked FEN.
- **Stockfish start failure latched forever.** One transient
  failure disabled suggestions for the whole session. It now
  retries after 10 s, and a failed start clears the pending
  analysis so the next frame retries.
- **"Waiting" now shows the move number** ("Waiting for
  opponent... · move 7"), so a tracker that is a move behind
  is visible instead of looking identical to a correctly
  waiting tracker.
- MainActivity's hash text corrected (was still saying
  "256 MB hash"; hash is scaled to device RAM since v1.5.0).

Other files were re-read in full for this review (CaptureService,
ChessBoard, StockfishEngine, OverlayView, CrashHandler,
MainActivity, manifest, build script). No other
opponent-move-blocking bug was found: move generation for
Bd7, castling, en passant and promotions is correct, the
lock/hidden/recovery logic is sound once the above are fixed,
and the overlay's per-square background already cancels its
own tint, so self-capture does not explain the miss.

## v1.7.0: faster, more reliable opponent-move detection

Opponent moves were slow and flaky for three compounding
reasons, all fixed here. First, speed: the tick was 600 ms
and a move needed 3 stable frames, so a move took animation
+ ~1.8 s just to confirm, plus Stockfish's think time, before
any arrow appeared — and in a fast game the next move could
arrive inside that window and reset the pending count, so
the first move was never confirmed at all. The tick is now
250 ms, so the same rule is ~750 ms, and a
highlight-corroborated move confirms in 2 frames (~500 ms).
Second, a single persistently misread square (common on some
themes) made every frame look 1 square off after a move, and
the old code declared desync after 5 such frames, stranding
the tracker in recovery — opponent moves made during recovery
were never tracked. A 1-3 square difference with no legal
move is no longer a desync at all: it first tries a depth-1
forward search for a single missed move (once per distinct
screen), and if that fails it is treated as noise and
Stockfish is still asked on the trusted FEN. Third, the
baseline after a confirmed move is now taken from the screen,
not from the expected board, so a misread square stays
stable across frames instead of looking like a new difference
every frame. Large differences (4+ squares) still desync and
recover as in v1.5.0, with the threshold adjusted to 8 frames
to keep the same ~2 s timing at the faster tick, and the
board-hidden grace is 24 frames to keep ~6 s.

## v1.6.0: accessibility removed

MediaProjection is now the only capture path. The
accessibility service, its manifest entry and config, the
capture-mode switch in the setup screen, the screenshot poll
loop, and the foreground-app watcher that depended on it are
all gone. Starting the app now always asks for screen
recording, every start, and there is no longer any service to
enable in Settings > Accessibility. If the old version's
service still shows as enabled in Settings after updating,
that is the old install's entry — updating to this build
removes the component, and uninstalling/reinstalling clears
it for good.

## How it works

- **Capture:** `CaptureService` is a foreground service holding a
  MediaProjection + VirtualDisplay + ImageReader, sampling the
  screen about every 600 ms.
- **Board detection:** `BoardDetector` (pure Java, no OpenCV) finds
  the board from Sobel edge projections — 9 near-equally-spaced
  grid lines each way — then reads each square. It reuses a cached
  board rect between full detections for stability and speed.
- **Move tracking:** last-move highlights first (Lichess/Chess.com
  yellow/green/blue tints), square-occupancy change second, with a
  2-frame debounce — the same priority desktop ChessMonger uses.
  Moves are checked against a full legal-move generator
  (`ChessBoard`) so a misread square cannot invent an illegal move.
  A return to the starting position resets to a new game
  automatically; a persistent mismatch shows "Out of sync".
- **Engine:** `StockfishEngine` runs the bundled Stockfish 17.1
  binary (arm64-v8a, shipped as `libstockfish.so` and executed from
  the native library dir, exactly as in Gambit Chess) over UCI at
  max strength: Skill Level 20, no Elo limit, all cores, 256 MB
  hash, MultiPV 1. Think time is yours to set (default 2000 ms).
- **Overlay:** `OverlayView` is a touch-transparent
  `TYPE_APPLICATION_OVERLAY` view: board outline, tinted from/to
  squares, arrow with arrowhead, and a one-line status
  (move + eval + depth). Taps pass through to the game.

## Use

1. Open ChessMonger, tap "Allow overlay over other apps", allow it.
   The setup screen shows a live permission line (overlay /
   notifications) on every resume, because Android
   can revoke these behind the app's back.
2. Pick your colour (White = white at bottom,
   Black = flipped), think time, and optionally paste a FEN to
   join a game already in progress (from your chess app's
   analysis board).
3. Tap "Start watching the board", allow screen recording,
   then switch to your chess app. The arrow appears on your turn.
4. Stop from the app's Stop button. Resync re-reads the board if
   tracking drifts; "Apply FEN" re-syncs it to a pasted position.

## Android 8-17 (API 26-37) — what was checked per version

Build note, honestly: this APK is compiled against SDK 36,
because the stable SDK repository available here tops out at
platform-36 (checked `repository2-1.xml`: the newest platform
package is `platform-36_r02.zip`; no platform-37 package is listed
yet). The manifest declares `minSdkVersion 26` and
`targetSdkVersion 37`, which aapt accepts with the 36 jar, so on
an Android 17 device the app runs as a target-37 app. No API
37-only calls are used — every API in the code exists at 36 or
earlier — so nothing in the app needs a 37 jar to run on 37.
When a platform-37 jar lands in the stable repo, rebuilding with
it is a one-line change in `build.sh` (`ANDROID_JAR`), no code
change. Your Galaxy S25 on API 37 is the first real test of the
37 path: if Stockfish fails to execute there, tell Kai and the
first thing to check is the extracted binary's execute bit (see
STOCKFISH.md), not the target version.

Android 17 specifics checked against the published behaviour
changes for apps targeting 37:
- **Background audio hardening:** not applicable — the app plays
  no audio and requests no audio focus. Its foreground service is
  mediaProjection, not mediaPlayback.
- **Large-screen orientation/resizability:** Android 17 removes
  the API 36 opt-out for ignoring orientation/resizability
  constraints on large screens. This app sets no fixed orientation
  and its setup screen is a scrolling vertical layout with
  `orientation|screenSize` config changes, so there is nothing to
  opt out of.
- **Reflection / static-final / MessageQueue / native loading:**
  the app uses no reflection, loads no `.so` via `System.load()`
  (Stockfish is executed as a separate process, not loaded), and
  uses no private framework fields — all three API 37 breakages
  above therefore do not apply to it.
- **MediaProjection ordering (API 34+, still enforced on 37):**
  grant first (in MainActivity), then the foreground service
  starts with the mediaProjection type, then `getMediaProjection`
  is called. The code follows that order, and a lost grant is
  caught as a `SecurityException` and shown as a status message
  instead of crashing the service.

- **Overlay (all versions):** `TYPE_APPLICATION_OVERLAY`, which
  exists from API 26 — the whole supported range. It is granted
  via Settings (`ACTION_MANAGE_OVERLAY_PERMISSION`), checked
  with `canDrawOverlays()` before every start, and drawn with
  `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE` so taps pass through
  to the game. On API 28+ the window also opts into
  short-edges cutout mode so a board under a notch still lines up.
- **Capture (MediaProjection only):** the default and only
  path. Needs a fresh grant each start. On API 29+ the service is
  started with `startForegroundService` and calls
  `startForeground` with `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`
  (declared in the manifest, plus the API 34
  `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission); on API
  26-28 it uses the plain two-argument `startForeground`.
  On Android 14+ the user may share a single app instead of
  the whole screen — sharing just the chess app works fine.
- **Notifications:** a channel is created on API 26+ (required
  for any foreground notification); on API 33+ the app also
  requests `POST_NOTIFICATIONS` at runtime.
- **Foreground-service launch rules (API 31+):** the service is
  always started from the foreground setup screen with
  `startForegroundService`, never from the background.

## Limits, honestly

- arm64-v8a only for Stockfish (your Galaxy S25 is arm64). On other
  ABIs the app will report that Stockfish failed to start.
- Board detection needs a clean, axis-aligned, reasonably large
  board filling much of the screen — the usual Lichess/Chess.com
  mobile board. Rotated, tiny, or heavily themed boards may fail
  the grid test, in which case it says "no board found".
- Piece *identity* is not classified from pixels: tracking starts
  from the opening position (or your pasted FEN) and follows moves
  by highlights/occupancy. That is why a mid-game join needs a FEN
  unless the last-move highlight is still visible.
- Stockfish is GPLv3 — see STOCKFISH.md. Personal use is fine;
  publishing this app needs a source offer for the Stockfish code.

## v1.5.0: automatic resync without a FEN + full reliability review

Out of sync no longer means "paste a FEN or stuck". When
desync is declared, the tracker enters recovery and keeps
trying on every new stable frame, in order: (1) the screen
matches the tracked position after all (noise desync —
re-confirm and resume); (2) a unique 1-2 move forward
sequence from the tracked position explains the screen
exactly (missed moves, e.g. the board was hidden while both
sides moved) — uniqueness is enforced, so a bishop-vs-queen
ambiguity is rejected rather than guessed; (3) the screen
matches a position in the last 50 positions (an undo /
takeback — roll back to it). Only if all three fail does it
show "Out of sync — trying to recover automatically (paste
a FEN if it stays stuck)", and it keeps trying on later
frames in case a later move makes recovery possible. A
pasted FEN always exits recovery immediately. The baseline
is also no longer set from the screen when the screen is
far from the tracked board — that was how a desync became
permanent. Other review fixes in the same release: Stockfish
now restarts after a dead engine process instead of returning
null forever (that was the biggest single reliability bug);
Stockfish hash is scaled to device memory (256 MB on 6 GB+,
128 MB on 3-6 GB, 64 MB below that) instead of a fixed
256 MB that could OOM on a low-end Android 8 phone; FEN
halfmove/fullmove are tracked properly instead of hardcoded
"0 1"; malformed FENs (wrong row lengths, missing kings)
are rejected instead of corrupting the tracker, and a bad
FEN now shows an error instead of silently starting from
the opening position; insufficient-material draws are
detected (K vs K, single minor, bishop-vs-bishop on same
colour); "Either side" now auto-detects board orientation
instead of always assuming white at bottom; board detection
tries smaller boards down to 0.35 of the screen (split
screen / analysis board with a side panel); a highlight pair
must now be corroborated by an occupancy change, so a UI
tint cannot confirm a move that never happened; piece
colours are recalibrated every ~30 s on an exact match, so
a mid-game theme change no longer misclassifies every piece;
rotation actually releases the lock (the old size check
never fired, because the virtual display size is fixed);
a service restart with no intent stops cleanly instead of
leaving a zombie service; crash reports prune after a
successful write with millisecond filenames; and the
detector shares one centre sample per square instead of
computing it twice.

## v1.4.0: board lock + anti-desync

The old loop re-detected the board every 5th tick (~3 s), so
the outline and arrow jumped by a few pixels each time, and
one bad frame (animation, drag, popup) could wipe the lock
and throw the tracker out of sync for no reason on the game's
side. v1.4.0 locks the rect once found and never re-detects
on a timer. The lock is only released by a screen-size change,
the board staying hidden for 10 frames (~6 s, checked by
checkerboard distance at the locked rect — a popup or a drag
holds the lock and freezes tracking instead of desyncing it),
or an explicit Resync / FEN / Start. Resync and FEN also
release the lock now, so a drifted lock cannot keep causing
desync. Other anti-desync guards in the same release: the
baseline is only taken after 2 identical occupancy frames
(a mid-animation first frame used to become the baseline);
a move needs 3 stable frames with the same candidate (an
animation spans 1-2 ticks and used to confirm phantom moves
at 2); a frame with no legal move no longer clears a pending
move or counts as desync — transition frames hold, only a
frame stable against the previous frame and still mismatched
counts, and a persistent small mismatch (5 stable frames)
declares out of sync; Stockfish is only asked when the screen
exactly matches the tracked position, and a result whose
board rect changed while it was thinking is discarded
instead of drawing a stale, offset arrow. The overlay also
skips redrawing when the board/suggestion did not change.

## v1.3.2 fix: "no board found" on a visible Chess.com board

Two bugs, both proven against the user's own screenshot
(Chess.com green board, 945x2048, board at 0,607-945,1552):
1. Grid detection missed it. Internal square lines are
   low-contrast (light/dark differ by ~45) next to the huge
   board-vs-background edge and the UI, so the global
   40%-of-max peak threshold filtered them out, and the outer
   vertical lines sit at x=0 / x=width, never interior peaks.
   Fix: a checkerboard search (two alternating colours, corner
   samples) for a coarse rect, then edge-fitting refinement
   restricted to the board's own range. On that screenshot the
   coarse search lands at 0,608 and refinement holds it at
   0,608-944,1552 — 1 px off the true rect.
2. Occupancy then misread it. Exact corner pixels read
   Chess.com's dark tile border, so empty light squares looked
   occupied on a parity pattern, and white pawns (narrow, beige)
   scored under the threshold with a wide centre sample. Fix:
   background from median 3x3 patches inset 15% from each
   corner, centre inset tightened to 0.35. On that screenshot
   empty squares now score 0-26, white pawns 94+, back ranks
   100+ — the start position reads exactly, where the old code
   read the middle ranks as alternating pieces.
If a board still is not found, the overlay now shows the
checkerboard distance in parentheses, so a black capture (~0)
reads differently from a board-like pattern that failed to
validate (~60).

## v1.3.1 fix: "Could not start screen capture (IllegalStateException)"

That exact status was a real bug in v1.3.0, and it failed on
every API 34+ phone (including Android 17 / API 37) — not a
device quirk. On target SDK 34 and up, `createVirtualDisplay`
throws `IllegalStateException` if no `MediaProjection.Callback`
is registered before it is called, and v1.3.0 registered none
and passed null for both callbacks. v1.3.1 registers a
`MediaProjection.Callback` (whose `onStop` stops the service
cleanly when the user/system ends the session) before
`createVirtualDisplay`, and passes a non-null
`VirtualDisplay.Callback` as well. It also fully stops any
previous capture before starting a new one, because API 34+
throws `SecurityException` if a projection records again
without `stop()` first, and it now includes the exception's
message in the status, not just its class name, so a future
failure is diagnosable from the screenshot alone.

If v1.3.1 still shows a capture error, the message after the
class name is the clue — send that whole line.

## Crash reports

`ChessMongerApp` installs `CrashHandler` before anything else
runs. Any uncaught crash — in the setup screen, the capture
service, or the Stockfish thread —
writes a report to app-private `crash-reports/` (last 5 kept)
with app version, Android version/API, device, screen, capture
mode / colour / think time / current FEN / last tracker status,
thread, and the full stack trace. On the next launch the setup
screen shows "The app crashed last time" with Share / Copy /
Dismiss; Share and Copy delete the file so it does not re-prompt.
There is also a "Show last crash report" button. Send that report
to Kai instead of "it crashed" — it names the exact failure.

Crash risks fixed in v1.3.0 along with the handler (audit of the
v1.2.0 code, before any specific crash report was available):
overlay or foreground-service start refused (permission revoked,
API 31+ background-start rule) now stops cleanly instead of
crashing; Resync/FEN intents no longer create an
orphan background service when nothing is running; board-square
sampling is clamped
to the bitmap so a detection rounded past the edge cannot throw
from `getPixel`; `getParcelableExtra` uses the typed API 33+ form;
`createVirtualDisplay` failures are reported as a status instead
of crashing; the Stockfish callback checks the overlay and engine
thread are still alive before drawing.

## Build

```sh
./build.sh   # -> build/ChessMongerAndroid.apk (debug-signed)
```

Manual javac + D8 + aapt pipeline (no Gradle daemon in this VM).
Requires the Android SDK/NDK layout in `~/workspace/tools/android/`.
