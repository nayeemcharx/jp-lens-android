#!/usr/bin/env python3
"""
Fetch the Sudachi system dictionary used by JP Lens' morpheme mode
(JapaneseTokenizer / OverlayService.MODE_MORPHEME).

It downloads a prebuilt SudachiDict dictionary zip from the WorksApplications S3
site and extracts the `system_*.dic` inside it to
    app/src/main/assets/system_full.dic
(always that filename, regardless of edition — it's what the app loads; Sudachi
opens any system dictionary by path). The app ships it as an asset and copies it
to internal storage on first run (Sudachi memory-maps it from the filesystem).

SudachiDict has three editions, smallest → largest: `small`, `core`, `full`.
For a *small APK*, build the `small` edition (~50 MB vs ~360 MB for full) — it
covers the everyday vocabulary morpheme mode needs:
    python3 scripts/build_sudachi_dict.py --lite          # = --edition small

Requires only the Python 3 standard library. Run from anywhere:
    python3 scripts/build_sudachi_dict.py                 # full (default)
    python3 scripts/build_sudachi_dict.py --edition core  # middle ground
Pin a specific dictionary date (default is the latest known release):
    python3 scripts/build_sudachi_dict.py --date 20260428
Use an already-downloaded zip to skip the download:
    python3 scripts/build_sudachi_dict.py --zip path/to/sudachi-dictionary-XXXXXXXX-small.zip

License note: SudachiDict is built from UniDic and other sources and is distributed
by Works Applications under the Apache License 2.0 (with the bundled lexicon under its
own terms — see the SudachiDict LEGAL file). The extracted system_full.dic carries the
same attribution requirement.
"""

import argparse
import io
import os
import sys
import urllib.request
import zipfile

# Prebuilt dictionaries are published as dated zips on the SudachiDict S3 site.
BASE_URL = "http://sudachi.s3-website-ap-northeast-1.amazonaws.com/sudachidict"
# Latest known full-dictionary release as of this script. Override with --date.
DEFAULT_DATE = "20260428"

# Where the app expects the extracted dictionary.
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)
ASSETS_DIR = os.path.join(REPO_ROOT, "app", "src", "main", "assets")
OUT_NAME = "system_full.dic"


def download(date: str, edition: str) -> bytes:
    url = f"{BASE_URL}/sudachi-dictionary-{date}-{edition}.zip"
    print(f"Downloading {url} ...", file=sys.stderr)
    req = urllib.request.Request(url, headers={"User-Agent": "jp-lens-build"})
    with urllib.request.urlopen(req) as resp:
        data = resp.read()
    print(f"  got {len(data):,} bytes", file=sys.stderr)
    return data


def extract_dic(zip_bytes: bytes, out_path: str, edition: str) -> None:
    member_name = f"system_{edition}.dic"
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
        members = [n for n in zf.namelist() if n.endswith(member_name)]
        if not members:
            raise SystemExit(
                f"{member_name} not found in zip; members: " + ", ".join(zf.namelist())
            )
        member = members[0]
        print(f"Extracting {member} -> {out_path}", file=sys.stderr)
        os.makedirs(os.path.dirname(out_path), exist_ok=True)
        with zf.open(member) as src, open(out_path, "wb") as dst:
            while True:
                chunk = src.read(1 << 20)
                if not chunk:
                    break
                dst.write(chunk)


def main() -> None:
    ap = argparse.ArgumentParser(description="Fetch a Sudachi system dictionary.")
    ap.add_argument("--date", default=DEFAULT_DATE, help="dictionary release date YYYYMMDD")
    ap.add_argument("--edition", choices=("small", "core", "full"), default="full",
                    help="dictionary size (default full)")
    ap.add_argument("--lite", action="store_true",
                    help="shorthand for --edition small (smallest, for a small APK)")
    ap.add_argument("--zip", help="path to an already-downloaded dictionary zip "
                                  "(its edition must match --edition/--lite)")
    args = ap.parse_args()

    edition = "small" if args.lite else args.edition

    out_path = os.path.join(ASSETS_DIR, OUT_NAME)
    if args.zip:
        with open(args.zip, "rb") as f:
            zip_bytes = f.read()
    else:
        zip_bytes = download(args.date, edition)

    extract_dic(zip_bytes, out_path, edition)
    size = os.path.getsize(out_path)
    print(f"Done. {out_path} ({size:,} bytes) [edition={edition}]", file=sys.stderr)
    # Rough lower bounds per edition; warns if the file is implausibly small.
    floors = {"small": 30 * 1024 * 1024, "core": 50 * 1024 * 1024, "full": 50 * 1024 * 1024}
    if size < floors[edition]:
        print(f"WARNING: file looks smaller than expected for the {edition} dictionary.",
              file=sys.stderr)


if __name__ == "__main__":
    main()
