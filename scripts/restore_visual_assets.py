#!/usr/bin/env python3
"""Restore invalid Touch One PNGs from the preserved LFS source and baseline APK.

The checked-in app source is authoritative for Java/game logic. This script only
repairs visual resource files before the CI build and fails if required art is
still unavailable or any PNG remains malformed.
"""
import argparse
import io
import struct
import sys
import zipfile
import zlib
from pathlib import Path

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
REQUIRED = {
    "bg_splash.png", "bg_table_1.png", "bg_table_2.png", "bg_table_3.png",
    "bg_win.png", "bg_win_clean.png", "bg_loss.png",
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
                pos = end
                break
            pos = end
        if not (saw_ihdr and saw_iend and idat):
            return False
        # PNG image data is one zlib stream split across one or more IDAT chunks.
        zlib.decompress(b"".join(idat))
        return True
    except (ValueError, struct.error, zlib.error):
        return False


def read_pngs_from_zip(path, apk=False):
    result = {}
    with zipfile.ZipFile(path) as archive:
        members = archive.namelist()
        if apk:
            apks = [n for n in members if n.lower().endswith(".apk")]
            if not apks:
                raise RuntimeError(f"No APK found inside {path}")
            member = max(apks, key=lambda n: archive.getinfo(n).file_size)
            with zipfile.ZipFile(io.BytesIO(archive.read(member))) as apk_archive:
                for name in apk_archive.namelist():
                    if name.lower().endswith(".png"):
                        result.setdefault(Path(name).name, apk_archive.read(name))
        else:
            for name in members:
                if name.lower().endswith(".png"):
                    result.setdefault(Path(name).name, archive.read(name))
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--resource-dir", required=True, type=Path)
    parser.add_argument("--source-zip", required=True, type=Path)
    parser.add_argument("--baseline-apk-zip", required=True, type=Path)
    args = parser.parse_args()

    source_pngs = read_pngs_from_zip(args.source_zip)
    baseline_pngs = read_pngs_from_zip(args.baseline_apk_zip, apk=True)
    repaired = []
    failures = []

    for path in sorted(args.resource_dir.glob("*.png")):
        current = path.read_bytes()
        if valid_png(current):
            continue
        name = path.name
        candidates = [source_pngs.get(name), baseline_pngs.get(name)]
        replacement = next((blob for blob in candidates if blob and valid_png(blob)), None)
        if replacement is None:
            if name in REQUIRED:
                failures.append(name)
            else:
                path.unlink()
                print(f"Removed invalid optional resource with no valid copy: {name}")
            continue
        path.write_bytes(replacement)
        repaired.append(name)

    still_invalid = [p.name for p in args.resource_dir.glob("*.png")
                     if not valid_png(p.read_bytes())]
    absent_required = sorted(name for name in REQUIRED
                             if not (args.resource_dir / name).is_file())
    if failures or still_invalid or absent_required:
        raise RuntimeError(
            "Visual asset validation failed; missing valid copies for "
            + ", ".join(sorted(set(failures + still_invalid + absent_required)))
        )
    print("Restored PNGs: " + (", ".join(repaired) if repaired else "none needed"))
    print(f"Validated {len(list(args.resource_dir.glob('*.png')))} PNG resources, "
          "including Scoobert's 15-frame dance sheet.")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)
