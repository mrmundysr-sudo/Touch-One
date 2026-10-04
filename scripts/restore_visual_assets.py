#!/usr/bin/env python3
"""Fail the build if any game PNG or the animated win plate is damaged."""
import argparse
import struct
import sys
import zlib
from pathlib import Path

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
REQUIRED_PNGS = {
    "bg_splash.png", "bg_loss.png", "bg_win.png",
    "bg_table_1.png", "bg_table_2.png", "bg_table_3.png",
    "char_boy.png", "char_dog.png", "char_girl.png",
    "scoobert_dance_sheet.png",
}


def valid_png(data):
    if not data.startswith(PNG_SIGNATURE):
        return False
    pos = len(PNG_SIGNATURE)
    idat = []
    saw_ihdr = saw_iend = False
    try:
        while pos + 12 <= len(data):
            length = struct.unpack(">I", data[pos:pos + 4])[0]
            kind = data[pos + 4:pos + 8]
            end = pos + 12 + length
            if end > len(data):
                return False
            payload = data[pos + 8:pos + 8 + length]
            crc = struct.unpack(">I", data[pos + 8 + length:end])[0]
            if zlib.crc32(kind + payload) & 0xffffffff != crc:
                return False
            if kind == b"IHDR":
                if saw_ihdr or length != 13:
                    return False
                width, height = struct.unpack(">II", payload[:8])
                if width == 0 or height == 0:
                    return False
                saw_ihdr = True
            elif kind == b"IDAT":
                idat.append(payload)
            elif kind == b"IEND":
                if length != 0:
                    return False
                saw_iend = True
                break
            pos = end
        if not (saw_ihdr and saw_iend and idat):
            return False
        zlib.decompress(b"".join(idat))
        return True
    except (ValueError, struct.error, zlib.error):
        return False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--resource-dir", required=True, type=Path)
    parser.add_argument("--win-gif", required=True, type=Path)
    args = parser.parse_args()

    if not args.resource_dir.is_dir():
        raise RuntimeError(f"Resource directory is missing: {args.resource_dir}")
    missing = sorted(name for name in REQUIRED_PNGS
                     if not (args.resource_dir / name).is_file())
    invalid = sorted(path.name for path in args.resource_dir.glob("*.png")
                     if not valid_png(path.read_bytes()))
    if missing or invalid:
        raise RuntimeError("Missing required PNGs: " + ", ".join(missing)
                           + "; invalid PNGs: " + ", ".join(invalid))

    gif = args.win_gif.read_bytes()
    if len(gif) < 100_000 or gif[:6] not in (b"GIF87a", b"GIF89a"):
        raise RuntimeError(f"Win-screen animation is missing or malformed: {args.win_gif}")
    width, height = struct.unpack("<HH", gif[6:10])
    if (width, height) != (720, 1600):
        raise RuntimeError(f"Win-screen animation dimensions are {width}x{height}; expected 720x1600")

    print(f"Validated {len(list(args.resource_dir.glob('*.png')))} PNG resources.")
    print("Validated the 720x1600 Scoobert win-screen GIF.")
    print("All required game visuals are intact.")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)
