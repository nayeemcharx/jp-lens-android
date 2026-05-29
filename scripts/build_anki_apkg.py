#!/usr/bin/env python3
"""
Build a standalone Anki .apkg from the bundled jmdict.db (NOT used by the app — a
one-off study-deck generator).

Front = a word's JLPT-listed spelling (the exact form Jonathan Waller's list graded;
kanji, or kana for kana-only words). Back = a richly formatted dump of everything
jmdict.db knows about that word: readings, every sense (part-of-speech, misc/field/
dialect tags, notes, cross-refs, antonyms, loanword origin), alternate writings, the
JLPT level, and a per-kanji breakdown (KANJIDIC2 meaning + JLPT).

By default it keeps only the N3 / N4 / N5 words and tags each note with its level
(n3/n4/n5). The JLPT level is word-level (per JMdict entry, from Waller's lists), but
is attached to the specific spelling Waller listed — so filtering by it is effectively
"by writing".

Needs `genanki` (pip install genanki). Run:
    python3 scripts/build_anki_apkg.py
    python3 scripts/build_anki_apkg.py --levels N5 N4 N3 N2 N1 --out deck.apkg

Attribution: JMdict/KANJIDIC2 are EDRDG (CC BY-SA 4.0); JLPT lists are Jonathan
Waller's (CC BY 4.0). Any deck derived from jmdict.db carries the same attribution.
"""

import argparse
import gzip
import html
import json
import os
import sqlite3
import sys

try:
    import genanki
except ImportError:
    sys.exit("genanki is required: pip install genanki (a venv is fine)")

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_DB = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "jmdict.db")
DEFAULT_OUT = os.path.join(REPO_ROOT, "JP_Lens_JLPT_N3-N5.apkg")

# Stable IDs so re-running updates the same deck/model on re-import (no duplicates).
DECK_ID = 1987650143
MODEL_ID = 1987650144

CIRCLED = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳"


def circled(n):
    return CIRCLED[n - 1] if 1 <= n <= len(CIRCLED) else f"({n})"


def esc(s):
    return html.escape(s or "")


CSS = """
.card { font-family: "Hiragino Kaku Gothic ProN","Noto Sans JP",sans-serif;
        font-size: 20px; color: #222; background: #fff; }
.front { font-size: 46px; font-weight: 600; text-align: center; padding: 12px 0; }
.back { text-align: left; font-size: 17px; line-height: 1.55; max-width: 640px;
        margin: 0 auto; }
.reading { font-size: 24px; color: #333; margin-bottom: 2px; }
.jlpt { display: inline-block; background: #2e7d32; color: #fff; border-radius: 4px;
        padding: 0 7px; font-size: 14px; font-weight: bold; vertical-align: middle; }
.label { color: #1565c0; font-weight: bold; margin-top: 10px; }
.sense { margin: 3px 0; }
.snum { color: #1565c0; font-weight: bold; }
.pos { color: #2e7d32; font-style: italic; font-size: 0.85em; }
.chip { color: #777; font-style: italic; font-size: 0.85em; }
.sub { color: #777; font-size: 0.85em; margin-left: 1.4em; }
.tag { color: #999; font-style: italic; font-size: 0.82em; }
.common { color: #2e7d32; }
.krow { margin: 1px 0; }
.lit { font-size: 1.25em; font-weight: 600; }
hr#answer { margin: 10px 0; }
.nightMode.card { color: #ddd; background: #2b2b2b; }
.nightMode .reading { color: #cfcfcf; }
.nightMode .chip, .nightMode .sub, .nightMode .tag { color: #aaa; }
.nightMode .snum, .nightMode .label { color: #7db4ff; }
.nightMode .pos, .nightMode .common { color: #8fd08f; }
"""

MODEL = lambda: genanki.Model(
    MODEL_ID,
    "JP Lens JMdict",
    fields=[{"name": "Front"}, {"name": "Back"}, {"name": "Sort"}],
    templates=[{
        "name": "Recognition",
        "qfmt": '<div class="front">{{Front}}</div>',
        "afmt": '{{FrontSide}}<hr id="answer"><div class="back">{{Back}}</div>',
    }],
    css=CSS,
    sort_field_index=2,
)


def load_tags(con):
    return {code: label for code, label in con.execute("SELECT code, label FROM tags")}


def load_kanji(con):
    return {lit: (meanings, jlpt)
            for lit, meanings, jlpt in con.execute("SELECT literal, meanings, jlpt FROM kanji")}


def is_kanji(ch):
    o = ord(ch)
    return 0x4E00 <= o <= 0x9FFF or 0x3400 <= o <= 0x4DBF


def pick_front(detail):
    """The JLPT-listed spelling: a writing carrying a "j" tag wins; else a reading
    with "j"; else first common writing; else first writing; else first reading."""
    writings, readings = detail.get("k", []), detail.get("r", [])
    for w in writings:
        if w.get("j"):
            return w["t"]
    for r in readings:
        if r.get("j"):
            return r["t"]
    for w in writings:
        if w.get("c"):
            return w["t"]
    if writings:
        return writings[0]["t"]
    return readings[0]["t"] if readings else ""


def build_back(detail, front, tags, kanji_map):
    w_label = lambda code: tags.get(code, code)
    out = []
    writings, readings, senses = detail.get("k", []), detail.get("r", []), detail.get("s", [])
    level = detail.get("j", "")

    # Readings (most common first as stored) + JLPT badge on the same line.
    if readings:
        prim = "、".join(esc(r["t"]) for r in readings)
        line = f'<div class="reading">{prim}'
        if level:
            line += f' <span class="jlpt">{esc(level)}</span>'
        out.append(line + "</div>")
    elif level:
        out.append(f'<div><span class="jlpt">{esc(level)}</span></div>')

    # Senses.
    for i, s in enumerate(senses, 1):
        parts = [f'<span class="snum">{circled(i)}</span> ']
        if s.get("pos"):
            parts.append('<span class="pos">' +
                         esc(", ".join(w_label(p) for p in s["pos"])) + "</span> ")
        parts.append(esc("; ".join(s.get("g", []))))
        chips = [w_label(c) for c in (s.get("misc", []) + s.get("field", []) + s.get("dialect", []))]
        if chips:
            parts.append(' <span class="chip">' + esc(" ".join(f"[{c}]" for c in chips)) + "</span>")
        for ls in s.get("ls", []):
            t = "from " + ls.get("lang", "")
            if ls.get("text"):
                t += ": " + ls["text"]
            if ls.get("wasei"):
                t += " (wasei)"
            parts.append(f' <span class="chip">{esc(t)}</span>')
        sub = []
        if s.get("info"):
            sub.append('<div class="sub">note: ' + esc("; ".join(s["info"])) + "</div>")
        if s.get("xref"):
            sub.append('<div class="sub">→ see ' + esc("、".join(s["xref"])) + "</div>")
        if s.get("ant"):
            sub.append('<div class="sub">⇄ ' + esc("、".join(s["ant"])) + "</div>")
        out.append(f'<div class="sense">{"".join(parts)}{"".join(sub)}</div>')

    # Alternate writings (everything other than the front spelling).
    others = [w for w in writings if w["t"] != front]
    if others:
        out.append('<div class="label">Other writings</div>')
        for w in others:
            row = '<div class="krow">' + esc(w["t"])
            if w.get("c"):
                row += ' <span class="common">●</span>'
            for t in w.get("tags", []):
                row += f' <span class="tag">{esc(w_label(t))}</span>'
            out.append(row + "</div>")

    # Reading detail when any reading carries tags / kanji restriction.
    detailed = [r for r in readings if r.get("tags") or [a for a in r.get("ak", []) if a != "*"]]
    if detailed:
        out.append('<div class="label">Readings</div>')
        for r in detailed:
            row = '<div class="krow">' + esc(r["t"])
            if r.get("c"):
                row += ' <span class="common">●</span>'
            for t in r.get("tags", []):
                row += f' <span class="tag">{esc(w_label(t))}</span>'
            ak = [a for a in r.get("ak", []) if a != "*"]
            if ak:
                row += f' <span class="sub">→ {esc("、".join(ak))}</span>'
            out.append(row + "</div>")

    # Kanji breakdown for the kanji in the front spelling.
    seen, krows = set(), []
    for ch in front:
        if not is_kanji(ch) or ch in seen:
            continue
        seen.add(ch)
        info = kanji_map.get(ch)
        if not info:
            continue
        meanings, kjlpt = info
        row = f'<div class="krow"><span class="lit">{esc(ch)}</span> — {esc(meanings)}'
        if kjlpt:
            row += f' <span class="jlpt">{esc(kjlpt)}</span>'
        krows.append(row + "</div>")
    if krows:
        out.append('<div class="label">Kanji</div>')
        out.extend(krows)

    return "".join(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--db", default=DEFAULT_DB, help=f"jmdict.db (default {DEFAULT_DB})")
    ap.add_argument("--levels", nargs="+", default=["N3", "N4", "N5"],
                    help="JLPT levels to include (default: N3 N4 N5)")
    ap.add_argument("--out", default=DEFAULT_OUT, help=f"output .apkg (default {DEFAULT_OUT})")
    ap.add_argument("--deck-name", default="JP Lens JMdict (JLPT N3–N5)")
    args = ap.parse_args()

    if not os.path.exists(args.db):
        sys.exit(f"{args.db} not found — run scripts/build_jmdict_db.py first.")

    con = sqlite3.connect(args.db)
    tags = load_tags(con)
    kanji_map = load_kanji(con)

    placeholders = ",".join("?" * len(args.levels))
    rows = con.execute(
        f"SELECT DISTINCT e.word_id, e.jlpt, d.json FROM entries e "
        f"JOIN detail d ON e.word_id = d.word_id WHERE e.jlpt IN ({placeholders})",
        args.levels,
    ).fetchall()
    print(f"Words at {'/'.join(args.levels)}: {len(rows)}")

    deck = genanki.Deck(DECK_ID, args.deck_name)
    model = MODEL()
    made = skipped = 0
    for word_id, level, blob in rows:
        detail = json.loads(gzip.decompress(blob))
        front = pick_front(detail)
        if not front:
            skipped += 1
            continue
        back = build_back(detail, front, tags, kanji_map)
        # Sort by level then reading so the browser groups sensibly.
        first_reading = detail["r"][0]["t"] if detail.get("r") else front
        sort = f"{level} {first_reading}"
        note = genanki.Note(
            model=model,
            fields=[front, back, sort],
            tags=[level.lower()],
            guid=genanki.guid_for(word_id, "jplens-jmdict"),
        )
        deck.add_note(note)
        made += 1

    genanki.Package(deck).write_to_file(args.out)
    con.close()
    size_kb = os.path.getsize(args.out) / 1024
    print(f"Wrote {args.out} ({size_kb:.0f} KB): {made} cards"
          + (f", {skipped} skipped (no spelling)" if skipped else ""))


if __name__ == "__main__":
    main()
