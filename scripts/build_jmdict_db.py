#!/usr/bin/env python3
"""
Build the offline dictionary SQLite database used by JP Lens' "full-sentence
block + word-by-word" mode (OverlayService.MODE_SENTENCE_DICT).

It downloads the latest release of scriptin/jmdict-simplified
(https://github.com/scriptin/jmdict-simplified) — the *full* English JMdict and
the English KANJIDIC2 — and folds them into a single SQLite file at
    app/src/main/assets/jmdict.db
which the app ships as an asset and copies to internal storage on first run.

Why these sources:
  - JMdict gives word -> readings + English glosses (no JLPT).
  - KANJIDIC2 gives per-kanji meanings + a JLPT level (legacy 1-4 scale).
  - stephenmk/yomitan-jlpt-vocab gives *word-level* JLPT (N5-N1) keyed by JMdict
    entry id, so we can tag each word with its level. Its data is Jonathan
    Waller's JLPT lists (tanos.co.uk) — the same source jisho.org uses — with
    JMdict entry ids added, which lets us join exactly on `id`.

Requires only the Python 3 standard library. Run from anywhere:
    python3 scripts/build_jmdict_db.py
Pass local json files to skip the download:
    python3 scripts/build_jmdict_db.py --jmdict path/to/jmdict-eng.json \
                                       --kanjidic path/to/kanjidic2-en.json \
                                       --jlpt-dir path/to/yomitan-jlpt-vocab/original_data

Smaller APK: build a *lite* db that keeps only common + JLPT-listed words
(~10% of JMdict, the learner-relevant slice) — roughly 10x smaller:
    python3 scripts/build_jmdict_db.py --lite
Or derive the lite subset from an already-built full jmdict.db, offline (no
download) — fastest when you already have the full db on disk:
    python3 scripts/build_jmdict_db.py --shrink app/src/main/assets/jmdict.db

License note: JMdict and KANJIDIC2 are the property of the Electronic
Dictionary Research and Development Group (EDRDG) and are used under the EDRDG
license (CC BY-SA 4.0). The JLPT lists are Jonathan Waller's, used under
CC BY 4.0. The generated jmdict.db is a derived work and carries the same
attribution requirements.
"""

import argparse
import csv
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

# Word-level JLPT lists (Jonathan Waller's, with JMdict entry ids attached). Each
# CSV row is: jmdict_seq, kana, kanji, waller_definition. Keyed by the JMdict
# entry id so we join exactly on JMdict's `id`.
JLPT_RAW_BASE = "https://raw.githubusercontent.com/stephenmk/yomitan-jlpt-vocab/main/original_data"
JLPT_FILES = {"N5": "n5.csv", "N4": "n4.csv", "N3": "n3.csv", "N2": "n2.csv", "N1": "n1.csv"}
# Easiest-first, so a seq that appears on more than one list keeps the easier level.
JLPT_ORDER = ["N5", "N4", "N3", "N2", "N1"]

# Kanji-writing status tags that mean "not the everyday spelling" — a word whose
# only kanji forms carry these is effectively a kana word.
RARE_KANJI_TAGS = {"rK", "sK", "iK", "oK"}

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


def _is_kana(s):
    """True if every char of [s] is hiragana or katakana (incl. the ー mark)."""
    if not s:
        return False
    return all(0x3040 <= ord(c) <= 0x30FF for c in s)


def _is_kana_native(w):
    """True if the word's everyday spelling is kana: it has no 'normal' kanji
    writing (only rare/irregular/search-only forms, or none), or a sense is tagged
    `uk` (usually written in kana). Used to rank a kana surface form's homographs."""
    kanji = w.get("kanji", [])
    normal = [k for k in kanji if not (set(k.get("tags", [])) & RARE_KANJI_TAGS)]
    if not normal:
        return True
    return any("uk" in s.get("misc", []) for s in w.get("sense", []))


def _load_jlpt_from_dir(dir_path):
    return {lv: open(os.path.join(dir_path, fn), encoding="utf-8").read()
            for lv, fn in JLPT_FILES.items()}


def _load_jlpt_from_web():
    out = {}
    for lv, fn in JLPT_FILES.items():
        print(f"Downloading JLPT list {fn} ...")
        out[lv] = _http_get(f"{JLPT_RAW_BASE}/{fn}").decode("utf-8")
    return out


def _parse_jlpt(level_texts):
    """seq(int) -> {"level","kana","kanji"} from the per-level CSVs. Easiest level
    wins if a seq appears on more than one list."""
    by_seq = {}
    for lv in JLPT_ORDER:
        text = level_texts.get(lv)
        if not text:
            continue
        for row in csv.DictReader(io.StringIO(text)):
            seq = row.get("jmdict_seq", "").strip()
            if not seq.isdigit():
                continue
            by_seq.setdefault(int(seq), {
                "level": lv,
                "kana": (row.get("kana") or "").strip(),
                "kanji": (row.get("kanji") or "").strip(),
            })
    print(f"  JLPT-tagged words: {len(by_seq)}")
    return by_seq


def _xref_str(xref):
    """jmdict-simplified xref is a tuple like ["見る","みる",1]. Render the word
    (+ reading in parens) for display, dropping the sense index."""
    if not xref:
        return ""
    word = str(xref[0])
    if len(xref) > 1 and isinstance(xref[1], str):
        word += f"（{xref[1]}）"
    return word


def _detail_obj(w, jl):
    """The trimmed, runtime-rendered view of a JMdict word. Short keys keep the
    compressed blob small; tag codes stay raw (decoded at runtime via `tags`).
    [jl] is this word's JLPT info ({"level","kana","kanji"}) or None — its level is
    tagged onto the matching writing/reading and kept word-level under "j"."""
    jl_kanji = jl["kanji"] if jl else ""
    jl_kana = jl["kana"] if jl else ""
    jl_level = jl["level"] if jl else ""

    def wj(text):  # JLPT level for a writing/reading spelling, "" if not the listed one.
        return jl_level if jl and text and (text == jl_kanji or text == jl_kana) else ""

    k = [{"t": x["text"], "c": 1 if x.get("common") else 0,
          "tags": x.get("tags", []), "j": wj(x["text"])}
         for x in w.get("kanji", [])]
    r = [{"t": x["text"], "c": 1 if x.get("common") else 0,
          "tags": x.get("tags", []), "ak": x.get("appliesToKanji", []), "j": wj(x["text"])}
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
    return {"k": k, "r": r, "s": s, "j": jl["level"] if jl else ""}


def build_entries(con, jmdict, jlpt_by_seq, lite=False):
    """Builds the lightweight `entries` index (one row per headword spelling, with a
    one-line gloss summary + word-level JLPT + a kana-preference flag) and the
    `detail` table (one gzipped JSON per word).

    When [lite] is True, only words that are *common* or carry a JLPT level are
    kept — that's the learner-relevant ~10% of JMdict, and it shrinks the db (and
    therefore the shipped APK) by roughly an order of magnitude."""
    words = jmdict.get("words", [])
    print(f"JMdict words: {len(words)}" + ("  (lite: common/JLPT only)" if lite else ""))
    entry_rows = []
    detail_rows = []
    word_id = 0
    skipped = 0
    for w in words:
        seq = w.get("id")
        jl = jlpt_by_seq.get(int(seq)) if (seq and str(seq).isdigit()) else None
        jlpt = jl["level"] if jl else ""
        kana_native = _is_kana_native(w)
        kanji = w.get("kanji", [])
        kana = w.get("kana", [])
        # Primary reading = first kana headword.
        reading = kana[0]["text"] if kana else ""
        common = 1 if (
            any(k.get("common") for k in kanji) or any(k.get("common") for k in kana)
        ) else 0
        # Lite build: drop everything that isn't common and isn't JLPT-listed.
        if lite and not common and not jlpt:
            skipped += 1
            continue
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
            json.dumps(_detail_obj(w, jl), ensure_ascii=False, separators=(",", ":"))
            .encode("utf-8")
        )
        detail_rows.append((word_id, blob))
        # Index under every kanji form and every kana form so a tokenizer base
        # form hits whether it's written in kanji or kana. kana_pref = 1 only when
        # this row's key is a kana spelling AND the word is kana-native, so a kana
        # surface ranks the true kana word above kanji homographs sharing the reading.
        keys = {k["text"] for k in kanji} | {k["text"] for k in kana}
        for key in keys:
            kana_pref = 1 if (kana_native and _is_kana(key)) else 0
            entry_rows.append((key, reading, gloss, common, word_id, jlpt, kana_pref))
    con.executemany(
        "INSERT INTO entries(key, reading, gloss, common, word_id, jlpt, kana_pref) "
        "VALUES(?,?,?,?,?,?,?)",
        entry_rows,
    )
    con.executemany("INSERT INTO detail(word_id, json) VALUES(?,?)", detail_rows)
    print(f"  inserted {len(entry_rows)} entry rows, {len(detail_rows)} detail rows"
          + (f" (skipped {skipped} non-common/non-JLPT words)" if lite else ""))


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


def _create_schema(con):
    con.execute("PRAGMA journal_mode=OFF")
    con.execute("PRAGMA synchronous=OFF")
    con.execute(
        "CREATE TABLE entries(key TEXT, reading TEXT, gloss TEXT, common INTEGER, "
        "word_id INTEGER, jlpt TEXT, kana_pref INTEGER)"
    )
    con.execute("CREATE TABLE detail(word_id INTEGER PRIMARY KEY, json BLOB)")
    con.execute("CREATE TABLE tags(code TEXT PRIMARY KEY, label TEXT)")
    con.execute("CREATE TABLE kanji(literal TEXT PRIMARY KEY, meanings TEXT, jlpt TEXT)")
    con.execute("CREATE TABLE meta(version TEXT)")


def _finish(con, version):
    con.execute("CREATE INDEX idx_entries_key ON entries(key)")
    con.execute("INSERT INTO meta(version) VALUES(?)", (str(version),))
    con.commit()
    con.execute("VACUUM")


def _open_fresh(out):
    """Create a fresh db at a temp path next to [out] (so we can read [out] as a
    source even when out == src), returning (connection, temp_path)."""
    os.makedirs(os.path.dirname(out), exist_ok=True)
    tmp = out + ".tmp"
    if os.path.exists(tmp):
        os.remove(tmp)
    con = sqlite3.connect(tmp)
    _create_schema(con)
    return con, tmp


def shrink_from_db(src_path, out):
    """Offline lite build: copy only the common-or-JLPT subset of an *existing*
    full jmdict.db into a new db — no network, no source JSON. Keeps the same
    schema; `kanji`/`tags`/`meta` are copied verbatim (they're already small)."""
    if not os.path.exists(src_path):
        sys.exit(f"--shrink source not found: {src_path}")
    print(f"Shrinking {src_path} -> common/JLPT subset ...")
    src = sqlite3.connect(f"file:{src_path}?mode=ro", uri=True)
    con, tmp = _open_fresh(out)
    try:
        # word_ids worth keeping: any spelling that's common or JLPT-listed.
        keep = [r[0] for r in src.execute(
            "SELECT DISTINCT word_id FROM entries WHERE common=1 OR jlpt<>''"
        )]
        keep_set = set(keep)
        print(f"  keeping {len(keep_set)} of "
              f"{src.execute('SELECT COUNT(DISTINCT word_id) FROM entries').fetchone()[0]} words")

        entry_rows = [r for r in src.execute(
            "SELECT key, reading, gloss, common, word_id, jlpt, kana_pref FROM entries"
        ) if r[4] in keep_set]
        con.executemany(
            "INSERT INTO entries(key, reading, gloss, common, word_id, jlpt, kana_pref) "
            "VALUES(?,?,?,?,?,?,?)", entry_rows,
        )
        detail_rows = [r for r in src.execute("SELECT word_id, json FROM detail")
                       if r[0] in keep_set]
        con.executemany("INSERT INTO detail(word_id, json) VALUES(?,?)", detail_rows)
        con.executemany("INSERT INTO tags(code, label) VALUES(?,?)",
                        src.execute("SELECT code, label FROM tags").fetchall())
        con.executemany("INSERT INTO kanji(literal, meanings, jlpt) VALUES(?,?,?)",
                        src.execute("SELECT literal, meanings, jlpt FROM kanji").fetchall())
        version = (src.execute("SELECT version FROM meta").fetchone() or ["?"])[0]
        print(f"  entries={len(entry_rows)} detail={len(detail_rows)}")
        _finish(con, f"{version}-lite")
    finally:
        con.close()
        src.close()
    os.replace(tmp, out)
    size_mb = os.path.getsize(out) / 1_048_576
    print(f"\nWrote {out} ({size_mb:.1f} MB), lite of {src_path}")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--jmdict", help="local jmdict-eng .json (skips download)")
    ap.add_argument("--kanjidic", help="local kanjidic2-en .json (skips download)")
    ap.add_argument("--jlpt-dir", help="local yomitan-jlpt-vocab original_data dir (skips download)")
    ap.add_argument("--out", default=DEFAULT_OUT, help=f"output db (default {DEFAULT_OUT})")
    ap.add_argument("--lite", action="store_true",
                    help="keep only common/JLPT words (smaller db for a small APK)")
    ap.add_argument("--shrink", metavar="EXISTING_DB",
                    help="offline lite build: derive the common/JLPT subset from an "
                         "already-built jmdict.db (no download). Implies --lite.")
    args = ap.parse_args()

    # Offline path: shrink an existing db in place (or to --out).
    if args.shrink:
        shrink_from_db(args.shrink, args.out)
        return

    if args.jmdict and args.kanjidic:
        version = "local"
        jmdict = _load_json_file(args.jmdict)
        kanjidic = _load_json_file(args.kanjidic)
    else:
        version, jmdict_url, kanjidic_url = _find_latest_assets()
        jmdict = _load_json_from_zip_url(jmdict_url)
        kanjidic = _load_json_from_zip_url(kanjidic_url)

    jlpt_by_seq = _parse_jlpt(
        _load_jlpt_from_dir(args.jlpt_dir) if args.jlpt_dir else _load_jlpt_from_web()
    )

    # Prefer the dictDate baked into the dictionaries for the meta version.
    version = jmdict.get("dictDate") or jmdict.get("version") or version
    if args.lite:
        version = f"{version}-lite"

    con, tmp = _open_fresh(args.out)
    try:
        build_entries(con, jmdict, jlpt_by_seq, lite=args.lite)
        build_tags(con, jmdict)
        build_kanji(con, kanjidic)
        _finish(con, version)
    finally:
        con.close()
    os.replace(tmp, args.out)

    size_mb = os.path.getsize(args.out) / 1_048_576
    print(f"\nWrote {args.out} ({size_mb:.1f} MB), version={version}")


if __name__ == "__main__":
    main()
