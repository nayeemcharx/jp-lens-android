#!/usr/bin/env python3
"""
Build the offline dictionary SQLite database used by JP Lens' "full-sentence
block + word-by-word" mode (OverlayService.MODE_SENTENCE_DICT).

It downloads the latest release of scriptin/jmdict-simplified
(https://github.com/scriptin/jmdict-simplified) — the *full* English JMdict and
the English KANJIDIC2 — and folds them into a single SQLite file at
    app/src/main/assets/jmdict.db
which the app ships as an asset and copies to internal storage on first run.

Why these two sources:
  - JMdict gives word -> readings + English glosses (no JLPT).
  - KANJIDIC2 gives per-kanji meanings + a JLPT level (legacy 1-4 scale).

Requires only the Python 3 standard library. Run from anywhere:
    python3 scripts/build_jmdict_db.py
Pass local json files to skip the download:
    python3 scripts/build_jmdict_db.py --jmdict path/to/jmdict-eng.json \
                                       --kanjidic path/to/kanjidic2-en.json

License note: JMdict and KANJIDIC2 are the property of the Electronic
Dictionary Research and Development Group (EDRDG) and are used under the EDRDG
license (CC BY-SA 4.0). The generated jmdict.db is a derived work and carries
the same attribution requirement.
"""

import argparse
import gzip
import io
import json
import os
import re
import sqlite3
import sys
import urllib.request
import zipfile

REPO = "scriptin/jmdict-simplified"
LATEST_RELEASE_API = f"https://api.github.com/repos/{REPO}/releases/latest"

# Asset name patterns within the release. The full JMdict English file is
# "jmdict-eng-<version>.json.zip" (the "common"-only file is excluded by
# requiring a digit right after "jmdict-eng-"). KANJIDIC2 English is
# "kanjidic2-en-<version>.json.zip".
JMDICT_ASSET_RE = re.compile(r"^jmdict-eng-\d.*\.json\.zip$")
KANJIDIC_ASSET_RE = re.compile(r"^kanjidic2-en-\d.*\.json\.zip$")

# KANJIDIC2 carries the *legacy* 4-level JLPT (4 = easiest .. 1 = hardest).
# Map it to the modern N-labels the rest of the app uses. The legacy scale has
# no clean N3, so this is an approximation (documented in CLAUDE.md).
LEGACY_JLPT_TO_N = {4: "N5", 3: "N4", 2: "N3", 1: "N1"}

# Cap on glosses kept per word in the one-line summary so the row stays readable.
# (The expandable detail panel shows every gloss of every sense — see build_entries.)
MAX_GLOSSES = 4

DEFAULT_OUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "assets", "jmdict.db",
)


def _http_get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "jp-lens-build/1.0"})
    with urllib.request.urlopen(req) as resp:
        return resp.read()


def _find_latest_assets():
    print(f"Querying latest release of {REPO} ...")
    data = json.loads(_http_get(LATEST_RELEASE_API))
    tag = data.get("tag_name", "?")
    jmdict_url = kanjidic_url = None
    for asset in data.get("assets", []):
        name = asset.get("name", "")
        if JMDICT_ASSET_RE.match(name):
            jmdict_url = asset["browser_download_url"]
        elif KANJIDIC_ASSET_RE.match(name):
            kanjidic_url = asset["browser_download_url"]
    if not jmdict_url or not kanjidic_url:
        sys.exit(
            "Could not locate both assets in the latest release.\n"
            f"  jmdict:   {jmdict_url}\n  kanjidic: {kanjidic_url}\n"
            "Download them manually and pass --jmdict/--kanjidic."
        )
    print(f"Release {tag}")
    return tag, jmdict_url, kanjidic_url


def _load_json_from_zip_url(url):
    print(f"Downloading {url.rsplit('/', 1)[-1]} ...")
    raw = _http_get(url)
    with zipfile.ZipFile(io.BytesIO(raw)) as zf:
        # Each archive holds a single .json file.
        name = next(n for n in zf.namelist() if n.endswith(".json"))
        print(f"  unzip {name} ({zf.getinfo(name).file_size // 1_048_576} MB) ...")
        with zf.open(name) as f:
            return json.load(f)


def _load_json_file(path):
    print(f"Loading {path} ...")
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def _xref_str(xref):
    """jmdict-simplified xref is a tuple like ["見る","みる",1]. Render the word
    (+ reading in parens) for display, dropping the sense index."""
    if not xref:
        return ""
    word = str(xref[0])
    if len(xref) > 1 and isinstance(xref[1], str):
        word += f"（{xref[1]}）"
    return word


def _detail_obj(w):
    """The trimmed, runtime-rendered view of a JMdict word. Short keys keep the
    compressed blob small; tag codes stay raw (decoded at runtime via `tags`)."""
    k = [{"t": x["text"], "c": 1 if x.get("common") else 0, "tags": x.get("tags", [])}
         for x in w.get("kanji", [])]
    r = [{"t": x["text"], "c": 1 if x.get("common") else 0,
          "tags": x.get("tags", []), "ak": x.get("appliesToKanji", [])}
         for x in w.get("kana", [])]
    s = []
    for sense in w.get("sense", []):
        glosses = [g["text"] for g in sense.get("gloss", [])
                   if g.get("lang", "eng") == "eng" and g.get("text")]
        if not glosses:
            continue
        s.append({
            "pos": sense.get("partOfSpeech", []),
            "field": sense.get("field", []),
            "misc": sense.get("misc", []),
            "dialect": sense.get("dialect", []),
            "info": sense.get("info", []),
            "xref": [_xref_str(x) for x in sense.get("related", [])],
            "ant": [_xref_str(x) for x in sense.get("antonym", [])],
            "ls": [{"lang": ls.get("lang", ""), "text": ls.get("text"),
                    "wasei": bool(ls.get("wasei"))}
                   for ls in sense.get("languageSource", [])],
            "g": glosses,
        })
    return {"k": k, "r": r, "s": s}


def build_entries(con, jmdict):
    """Builds the lightweight `entries` index (one row per headword spelling, with a
    one-line gloss summary) and the `detail` table (one gzipped JSON per word)."""
    words = jmdict.get("words", [])
    print(f"JMdict words: {len(words)}")
    entry_rows = []
    detail_rows = []
    word_id = 0
    for w in words:
        kanji = w.get("kanji", [])
        kana = w.get("kana", [])
        # Primary reading = first kana headword.
        reading = kana[0]["text"] if kana else ""
        common = 1 if (
            any(k.get("common") for k in kanji) or any(k.get("common") for k in kana)
        ) else 0
        glosses = []
        for sense in w.get("sense", []):
            for g in sense.get("gloss", []):
                if g.get("lang", "eng") == "eng" and g.get("text"):
                    glosses.append(g["text"])
                    if len(glosses) >= MAX_GLOSSES:
                        break
            if len(glosses) >= MAX_GLOSSES:
                break
        gloss = "; ".join(glosses)
        if not gloss:
            continue
        word_id += 1
        blob = gzip.compress(
            json.dumps(_detail_obj(w), ensure_ascii=False, separators=(",", ":"))
            .encode("utf-8")
        )
        detail_rows.append((word_id, blob))
        # Index under every kanji form and every kana form so a Kuromoji base
        # form hits whether it's written in kanji or kana.
        keys = {k["text"] for k in kanji} | {k["text"] for k in kana}
        for key in keys:
            entry_rows.append((key, reading, gloss, common, word_id))
    con.executemany(
        "INSERT INTO entries(key, reading, gloss, common, word_id) VALUES(?,?,?,?,?)",
        entry_rows,
    )
    con.executemany("INSERT INTO detail(word_id, json) VALUES(?,?)", detail_rows)
    print(f"  inserted {len(entry_rows)} entry rows, {len(detail_rows)} detail rows")


def build_tags(con, jmdict):
    """The top-level JMdict `tags` map: code -> human label (e.g. v1 -> Ichidan verb)."""
    tags = jmdict.get("tags", {})
    con.executemany(
        "INSERT OR REPLACE INTO tags(code, label) VALUES(?,?)", list(tags.items())
    )
    print(f"  inserted {len(tags)} tag labels")


def build_kanji(con, kanjidic):
    chars = kanjidic.get("characters", [])
    print(f"KANJIDIC2 characters: {len(chars)}")
    rows = []
    for c in chars:
        literal = c.get("literal")
        if not literal:
            continue
        meanings = []
        rm = c.get("readingMeaning")
        if rm:
            for grp in rm.get("groups", []):
                for m in grp.get("meanings", []):
                    if m.get("lang", "en") == "en" and m.get("value"):
                        meanings.append(m["value"])
        if not meanings:
            continue
        level = (c.get("misc") or {}).get("jlptLevel")
        jlpt = LEGACY_JLPT_TO_N.get(level, "") if level else ""
        rows.append((literal, ", ".join(meanings), jlpt))
    con.executemany(
        "INSERT OR REPLACE INTO kanji(literal, meanings, jlpt) VALUES(?,?,?)", rows
    )
    print(f"  inserted {len(rows)} kanji rows")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--jmdict", help="local jmdict-eng .json (skips download)")
    ap.add_argument("--kanjidic", help="local kanjidic2-en .json (skips download)")
    ap.add_argument("--out", default=DEFAULT_OUT, help=f"output db (default {DEFAULT_OUT})")
    args = ap.parse_args()

    if args.jmdict and args.kanjidic:
        version = "local"
        jmdict = _load_json_file(args.jmdict)
        kanjidic = _load_json_file(args.kanjidic)
    else:
        version, jmdict_url, kanjidic_url = _find_latest_assets()
        jmdict = _load_json_from_zip_url(jmdict_url)
        kanjidic = _load_json_from_zip_url(kanjidic_url)

    # Prefer the dictDate baked into the dictionaries for the meta version.
    version = jmdict.get("dictDate") or jmdict.get("version") or version

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    if os.path.exists(args.out):
        os.remove(args.out)

    con = sqlite3.connect(args.out)
    try:
        con.execute("PRAGMA journal_mode=OFF")
        con.execute("PRAGMA synchronous=OFF")
        con.execute(
            "CREATE TABLE entries(key TEXT, reading TEXT, gloss TEXT, common INTEGER, word_id INTEGER)"
        )
        con.execute("CREATE TABLE detail(word_id INTEGER PRIMARY KEY, json BLOB)")
        con.execute("CREATE TABLE tags(code TEXT PRIMARY KEY, label TEXT)")
        con.execute(
            "CREATE TABLE kanji(literal TEXT PRIMARY KEY, meanings TEXT, jlpt TEXT)"
        )
        con.execute("CREATE TABLE meta(version TEXT)")
        build_entries(con, jmdict)
        build_tags(con, jmdict)
        build_kanji(con, kanjidic)
        con.execute("CREATE INDEX idx_entries_key ON entries(key)")
        con.execute("INSERT INTO meta(version) VALUES(?)", (str(version),))
        con.commit()
        con.execute("VACUUM")
    finally:
        con.close()

    size_mb = os.path.getsize(args.out) / 1_048_576
    print(f"\nWrote {args.out} ({size_mb:.1f} MB), version={version}")


if __name__ == "__main__":
    main()
