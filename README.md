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
