// App entry: load the font, artwork and sounds, then hand the canvas to the table view.
import { Engine } from './engine.js';
import { Sfx } from './audio.js';
import { TableView } from './game.js';

const IMAGES = [
  'bg_splash', 'bg_win', 'bg_loss', 'bg_table_1', 'bg_table_2', 'bg_table_3',
  'bg_setup', 'bg_scoring',
  'bubble_count', 'bubble_count_right', 'bubble_count_down', 'card_back', 'scoobert_dance_sheet',
];

function loadImage(name) {
  return new Promise((resolve) => {
    const img = new Image();
    img.onload = () => resolve([name, img]);
    img.onerror = () => resolve([name, null]); // a missing plate falls back to a gradient, never a crash
    img.src = `img/${name}.png`;
  });
}

async function boot() {
  try { await document.fonts.load('700 40px Outfit'); } catch (e) { /* falls back to system font */ }
  const images = Object.fromEntries(await Promise.all(IMAGES.map(loadImage)));
  const sfx = new Sfx('snd/');
  sfx.load(); // decodes in the background; sounds start playing as each one is ready
  const cap = window.Capacitor;
  let haptics = null;
  try {
    // Native plugin from @capacitor/haptics; registerPlugin is the fallback when Plugins isn't pre-populated.
    if (cap && cap.isNativePlatform && cap.isNativePlatform()) {
      haptics = (cap.Plugins && cap.Plugins.Haptics) || (cap.registerPlugin && cap.registerPlugin('Haptics')) || null;
    }
  } catch (e) { haptics = null; }
  const view = new TableView(document.getElementById('table'), images, sfx, new Engine(),
    haptics, document.getElementById('winGif'));
  window.__touchOne = view; // handy for debugging and the browser smoke test
  view.start();
}

boot();
