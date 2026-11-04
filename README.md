# Touch One

Touch One 1.4.0 ("premium pass"). `main` carries the original Android project (the milestone zips and the
`.touch-uploads/` chunks). The `claude/ios` branch is the **iOS** version.

## iOS branch layout
- `www/` - the whole app: a Canvas 2D port of the Android game, packaged for iPhone with Capacitor
  (`appId` `com.touchone.game`, `webDir` `www`).
  - `js/engine.js` - rules (port of `Engine.java` / `Card.java`), no DOM; tested under Node.
  - `js/game.js` - the table: one game clock, timeline, animation, input (port of `TableView.java`).
  - `js/art.js` - cached card faces (port of `CardArt.java`); `js/gfx.js` - palette and canvas helpers.
  - `js/audio.js` - Web Audio sound effects (port of `Sfx.java`); `js/main.js` - boot.
  - `img/`, `snd/`, `fonts/` - the same artwork, sounds and Outfit Bold (SIL OFL) as Android 1.4.0.
- `tests/engine.test.mjs` - rules + AI tests. `npm test`
- `tools/patch_ios.py` - portrait lock, hidden status bar, opaque icon, dark launch screen (run after `cap sync`).
- `.github/workflows/ios.yml` - CI on macOS: tests, simulator build + screenshot, unsigned archive. TestFlight upload
  only on a manual run with "upload" ticked.

## Differences from Android
- iOS has no back button, so the table has a small **Menu** pill (top left) that returns to the title screen.
- Haptics use `@capacitor/haptics`; sound uses Web Audio and plays even with the ringer switch on silent.
- The win animation is the same GIF, shown as an `<img>` beneath the canvas.

## Run in a browser
`npm run serve`, then open http://localhost:8080 in a phone-size window.

## Build
CI generates `ios/` (not committed): `npm install`, `npx cap add ios`, `npx cap sync ios`, `python3 tools/patch_ios.py`.

## Game modes (Setup)

The orange **Setup** button above the 1 / 2 / 3 opponent tiles opens the Setup screen:

- **Quick Play** (default): one round, the round winner ends the game. Unchanged.
- **Scoring Game**: everyone starts at 0. Number cards score face value, Jump / Spin / +2 score 20, Touch One and +4 wilds score 50.
  The round winner collects the value of all cards left in the other hands; the round breakdown and running totals are shown,
  then **Next Round** keeps the totals. The match ends when a player reaches or passes **Points to win** (500 prefilled; any
  positive whole number, typed on the in-app keypad). A new Scoring Game resets totals and the target to 500.

Code: `www/js/scoring.js`, `www/js/screens.js` (iOS/web); `Scoring.java`, `ModeState.java`, `ModeUi.java` (Android).
Tests: `npm test` (JS) and `tests/ScoringTest.java` (see the header of that file for the `javac` one-liner).
