#!/usr/bin/env python3
"""Run after `npx cap add ios` + `npx cap sync ios`.
Locks portrait on iPhone, hides the status bar, sets export compliance, installs an OPAQUE app icon (no alpha
channel) and a dark launch screen. The shared www/ folder is NOT edited; only the generated ios/ project is patched."""
import json
import plistlib
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "ios" / "App" / "App"
NIGHT = (0x1A, 0x10, 0x20)

# --- Info.plist
pl_path = APP / "Info.plist"
pl = plistlib.loads(pl_path.read_bytes())
pl["UISupportedInterfaceOrientations"] = ["UIInterfaceOrientationPortrait"]
pl.pop("UISupportedInterfaceOrientations~ipad", None)
pl["UIRequiredDeviceCapabilities"] = ["arm64"]
pl["UIRequiresFullScreen"] = True
pl["UIStatusBarHidden"] = True
pl["UIViewControllerBasedStatusBarAppearance"] = False
pl["ITSAppUsesNonExemptEncryption"] = False
pl["CFBundleDisplayName"] = "Touch One"
pl["UIUserInterfaceStyle"] = "Dark"
pl_path.write_bytes(plistlib.dumps(pl))

# --- opaque icon (flatten onto the night colour, save RGB: no alpha channel at all)
src = Image.open(ROOT / "tools" / "icons" / "icon-1024.png").convert("RGBA")
flat = Image.new("RGB", (1024, 1024), NIGHT)
flat.paste(src, (0, 0), src)
iconset = APP / "Assets.xcassets" / "AppIcon.appiconset"
for f in iconset.glob("*"):
    f.unlink()
flat.save(iconset / "AppIcon-1024.png", "PNG")
(iconset / "Contents.json").write_text(json.dumps({
    "images": [{"filename": "AppIcon-1024.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}],
    "info": {"author": "xcode", "version": 1}}, indent=2))

# --- dark launch image (replaces Capacitor's white splash)
splash = APP / "Assets.xcassets" / "Splash.imageset"
if splash.exists():
    for f in splash.glob("*.png"):
        Image.new("RGB", (2732, 2732), NIGHT).save(f, "PNG")

# --- dark web view background behind the page
for sb in APP.rglob("*.storyboard"):
    s = sb.read_text()
    s = s.replace('<color key="backgroundColor" red="1" green="1" blue="1" alpha="1" colorSpace="custom" customColorSpace="sRGB"/>',
                  '<color key="backgroundColor" red="0.102" green="0.063" blue="0.125" alpha="1" colorSpace="custom" customColorSpace="sRGB"/>')
    sb.write_text(s)

# --- validate: no alpha anywhere in the AppIcon set
for p in iconset.glob("*.png"):
    im = Image.open(p)
    assert im.mode == "RGB" and "A" not in im.getbands(), f"{p} has alpha ({im.mode})"
    assert im.size == (1024, 1024)
print("iOS project patched; AppIcon is opaque RGB 1024x1024.")
