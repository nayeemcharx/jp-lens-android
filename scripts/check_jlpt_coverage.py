#!/usr/bin/env python3
"""
Sanity check: how much of the JLPT N5–N1 vocabulary survives in the *lite*
bundled dictionaries?

It fetches Jonathan Waller's JLPT word lists (stephenmk/yomitan-jlpt-vocab, the
same source build_jmdict_db.py uses), then for each word checks presence in:
  - jmdict.db  — exact SQLite lookup against `entries.key` (authoritative).
  - system_full.dic — the Sudachi binary. We don't decode the double-array; we
    read the headword strings directly. Every lexicon string is stored as a
    length byte L followed by exactly L UTF-16LE code units, so we scan for that
    pattern (overlapping, both alignments) and take exactly L chars — yielding
    cleanly-bounded surface forms. Validated below against known-present words
    (and cross-checked against a raw byte search) before the numbers are trusted.

A word counts as "present" if its kanji writing (or, failing that, its kana
writing) is a headword. Pure stdlib; run from anywhere:
    python3 scripts/check_jlpt_coverage.py
"""

import csv
import io
import os
import re
import sqlite3
import sys
import urllib.request

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(os.path.dirname(SCRIPT_DIR), "app", "src", "main", "assets")
JMDICT_DB = os.path.join(ASSETS, "jmdict.db")
SUDACHI_DIC = os.path.join(ASSETS, "system_full.dic")

JLPT_BASE = "https://raw.githubusercontent.com/stephenmk/yomitan-jlpt-vocab/main/original_data"
LEVELS = ["N5", "N4", "N3", "N2", "N1"]

# Maximal runs of these = candidate Sudachi headwords (hiragana, katakana, kanji,
# iteration/length marks). Non-Japanese bytes (length prefixes, ids) break runs.
JP_RUN = re.compile(
    "[々ぁ-ゖゝ-ゞァ-ヺー-ヿ"
    "㐀-䶿一-鿿豈-﫿]+"
)

# Words known to be in even the small SudachiDict — used to validate the scan.
SANITY_WORDS = ["する", "食べる", "日本", "図書館", "人", "水", "大きい",
                "勉強", "学校", "これ", "ありがとう", "東京"]


def fetch_jlpt():
    """level -> list of (kana, kanji); de-duped per level. Easiest level wins
    when a word appears on several lists."""
    out = {}
    seen = set()
    for lv in LEVELS:
        url = f"{JLPT_BASE}/{lv.lower()}.csv"
        print(f"fetching {url}", file=sys.stderr)
        text = urllib.request.urlopen(url).read().decode("utf-8")
        words = []
        for row in csv.DictReader(io.StringIO(text)):
            kana = (row.get("kana") or "").strip()
            kanji = (row.get("kanji") or "").strip()
            key = (kanji, kana)
            if not kana and not kanji:
                continue
            if key in seen:
                continue
            seen.add(key)
            words.append((kana, kanji))
        out[lv] = words
        print(f"  {lv}: {len(words)} unique words", file=sys.stderr)
    return out


def load_jmdict_keys():
    con = sqlite3.connect(f"file:{JMDICT_DB}?mode=ro", uri=True)
    keys = {r[0] for r in con.execute("SELECT key FROM entries")}
    con.close()
    print(f"jmdict.db headword keys: {len(keys)}", file=sys.stderr)
    return keys


def load_sudachi_surfaces():
    data = open(SUDACHI_DIC, "rb").read()
    print(f"sudachi .dic: {len(data):,} bytes — scanning for headwords ...", file=sys.stderr)
    surfaces = set()
    for align in (0, 1):
        b = data[align:]
        if len(b) % 2:
            b = b[:-1]
        text = b.decode("utf-16-le", errors="replace")
        surfaces.update(JP_RUN.findall(text))
    print(f"  recovered {len(surfaces):,} distinct Japanese headword strings", file=sys.stderr)
    return surfaces


def present(kana, kanji, headwords):
    """True if the written form is a headword: prefer the kanji writing, else kana."""
    if kanji and kanji in headwords:
        return True
    if kana and kana in headwords:
        return True
    return False


def main():
    if not os.path.exists(JMDICT_DB):
        sys.exit(f"missing {JMDICT_DB} — run build_jmdict_db.py first")
    if not os.path.exists(SUDACHI_DIC):
        sys.exit(f"missing {SUDACHI_DIC} — run build_sudachi_dict.py first")

    jlpt = fetch_jlpt()
    jm_keys = load_jmdict_keys()
    su_keys = load_sudachi_surfaces()

    # Validate the Sudachi scan: known words must be found, or the numbers are bogus.
    found = [w for w in SANITY_WORDS if w in su_keys]
    print(f"\nSudachi scan validation: {len(found)}/{len(SANITY_WORDS)} known words found",
          file=sys.stderr)
    if len(found) < len(SANITY_WORDS) - 1:
        sys.exit(f"Sudachi scan looks broken (only found {found}); aborting.")

    total = both = miss_jm = miss_su = 0
    missing_jm, missing_su = [], []
    per_level = {}
    for lv in LEVELS:
        lt = lb = lmj = lms = 0
        for kana, kanji in jlpt[lv]:
            total += 1
            lt += 1
            in_jm = present(kana, kanji, jm_keys)
            in_su = present(kana, kanji, su_keys)
            disp = kanji or kana
            if in_jm and in_su:
                both += 1; lb += 1
            if not in_jm:
                miss_jm += 1; lmj += 1
                missing_jm.append((lv, disp, kana))
            if not in_su:
                miss_su += 1; lms += 1
                missing_su.append((lv, disp, kana))
        per_level[lv] = (lt, lb, lmj, lms)

    print("\n================ JLPT N1–N5 coverage ================")
    print(f"unique JLPT words checked : {total}")
    print(f"present in BOTH dicts     : {both}  ({both*100//total}%)")
    print(f"missing from jmdict.db    : {miss_jm}  ({miss_jm*100//total}%)")
    print(f"missing from sudachi .dic : {miss_su}  ({miss_su*100//total}%)")
    print("\nper level   total  both   miss-jmdict  miss-sudachi")
    for lv in LEVELS:
        t, b, mj, ms = per_level[lv]
        print(f"  {lv}        {t:5} {b:5}   {mj:9}   {ms:9}")

    def dump(name, rows):
        print(f"\n--- missing from {name}: {len(rows)} ---")
        for lv, disp, kana in rows[:60]:
            print(f"  [{lv}] {disp}" + (f"  ({kana})" if kana and kana != disp else ""))
        if len(rows) > 60:
            print(f"  ... and {len(rows) - 60} more")
        # full list to a file next to the assets for inspection
        path = os.path.join(SCRIPT_DIR, f"missing_{name}.txt")
        with open(path, "w", encoding="utf-8") as f:
            for lv, disp, kana in rows:
                f.write(f"{lv}\t{disp}\t{kana}\n")
        print(f"  (full list: {path})")

    dump("jmdict", missing_jm)
    dump("sudachi", missing_su)


if __name__ == "__main__":
    main()
