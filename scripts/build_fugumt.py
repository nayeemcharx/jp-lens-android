#!/usr/bin/env python3
"""
Build the offline Japanese->English translation assets used by JP Lens (replacing
the old Google ML Kit on-device translator, which was poor for Japanese).

Model: FuguMT (staka/fugumt-ja-en), a MarianMT ja->en model. We export it to ONNX
(encoder + decoder, *no* KV-cache so the on-device decode loop stays simple),
int8-dynamic-quantize the weights, and dump the tokenizer as plain data so the app
can run a pure-Kotlin SentencePiece Unigram tokenizer with no native tokenizer dep.

Outputs (bundled in the APK, copied to internal storage on first use):
    app/src/main/assets/fugumt/
        encoder.onnx     int8-quantized Marian encoder
        decoder.onnx     int8-quantized Marian decoder (no past)
        source_spm.json  {"pieces": [[piece, score], ...]}  -> JA Viterbi tokenizer
        vocab.json       Marian shared vocab: piece -> id  (encode + decode)
        config.json      special-token ids (eos/pad/unk/decoder_start) + sizes

None of these are committed (too large / derived). Run once:

    pip install "optimum[exporters]" onnx onnxruntime transformers sentencepiece \
                huggingface_hub torch
    python3 scripts/build_fugumt.py

Flags:
    --model PATH     use a local model dir instead of downloading staka/fugumt-ja-en
    --fp32           skip int8 quantization (bigger, for A/B-ing quality)
    --out DIR        override the asset output dir
    --keep-work      don't delete the temp export dir (for debugging)

Bump Translator.MODEL_ASSET_VERSION in the app whenever a regenerated bundle should
replace an already-copied one across in-place updates.

License note: FuguMT is released by Satoshi Takahashi under CC BY-SA 4.0; it derives
from Marian/OPUS-MT (Helsinki-NLP, MIT / CC-BY). The generated assets are a derived
work and carry the same attribution. ONNX Runtime (Microsoft) is MIT-licensed.
"""

import argparse
import json
import os
import shutil
import sys
import tempfile

MODEL_ID = "staka/fugumt-ja-en"
DEFAULT_OUT = os.path.join("app", "src", "main", "assets", "fugumt")


def _die(msg):
    print("ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


def _require(mod, pip):
    try:
        return __import__(mod)
    except ImportError:
        _die(
            f"missing Python package '{mod}'. Install the build deps:\n"
            f"  pip install \"optimum[exporters]\" onnx onnxruntime transformers "
            f"sentencepiece huggingface_hub torch"
        )


def resolve_model_dir(local):
    if local:
        if not os.path.isdir(local):
            _die(f"--model dir does not exist: {local}")
        print(f"Using local model dir: {local}")
        return local
    _require("huggingface_hub", "huggingface_hub")
    from huggingface_hub import snapshot_download
    print(f"Downloading {MODEL_ID} …")
    return snapshot_download(MODEL_ID)


def export_onnx(model_dir, work):
    _require("optimum", "optimum[exporters]")
    _require("torch", "torch")
    from optimum.exporters.onnx import main_export
    print("Exporting to ONNX (encoder + decoder, no past) …")
    # task without "-with-past" => encoder_model.onnx + decoder_model.onnx (no KV cache)
    main_export(model_dir, output=work, task="text2text-generation", opset=14)
    enc = os.path.join(work, "encoder_model.onnx")
    dec = os.path.join(work, "decoder_model.onnx")
    if not (os.path.exists(enc) and os.path.exists(dec)):
        _die(
            "optimum did not produce encoder_model.onnx / decoder_model.onnx. "
            f"Files present: {sorted(os.listdir(work))}"
        )
    return enc, dec


def quantize(src, dst, fp32):
    if fp32:
        shutil.copyfile(src, dst)
        return
    try:
        from onnxruntime.quantization import quantize_dynamic, QuantType
        print(f"Quantizing (int8) -> {os.path.basename(dst)} …")
        quantize_dynamic(src, dst, weight_type=QuantType.QInt8)
    except Exception as e:  # fall back to fp32 so the build still produces a model
        print(f"  WARNING: int8 quantization failed ({e}); shipping fp32 copy.")
        shutil.copyfile(src, dst)


def dump_tokenizer(model_dir, out):
    _require("transformers", "transformers")
    from transformers import AutoTokenizer, AutoConfig

    tok = AutoTokenizer.from_pretrained(model_dir)
    config = AutoConfig.from_pretrained(model_dir)

    # Shared Marian vocab: piece -> id (used for both encode and decode).
    vocab = tok.get_vocab()
    with open(os.path.join(out, "vocab.json"), "w", encoding="utf-8") as f:
        json.dump(vocab, f, ensure_ascii=False)

    # Source SentencePiece model: pieces + unigram scores drive the Kotlin Viterbi.
    sp = getattr(tok, "spm_source", None)
    if sp is None:
        _require("sentencepiece", "sentencepiece")
        import sentencepiece as spm
        spm_path = os.path.join(model_dir, "source.spm")
        if not os.path.exists(spm_path):
            _die("could not locate source SentencePiece model (tok.spm_source is None "
                 f"and {spm_path} missing).")
        sp = spm.SentencePieceProcessor()
        sp.Load(spm_path)

    n = sp.get_piece_size()
    pieces = [[sp.id_to_piece(i), float(sp.get_score(i))] for i in range(n)]
    with open(os.path.join(out, "source_spm.json"), "w", encoding="utf-8") as f:
        json.dump({"pieces": pieces}, f, ensure_ascii=False)

    dec_start = getattr(config, "decoder_start_token_id", None)
    if dec_start is None:
        dec_start = tok.pad_token_id

    # Token ids the model must never emit. FuguMT sets bad_words_ids=[[32000]] (pad),
    # which is the top logit at most decode steps — the app masks these before argmax
    # or greedy decoding stalls immediately. Flatten single-token entries.
    bad_word_ids = []
    try:
        from transformers import GenerationConfig
        gc = GenerationConfig.from_pretrained(model_dir)
        for entry in (gc.bad_words_ids or []):
            if len(entry) == 1:
                bad_word_ids.append(int(entry[0]))
    except Exception as e:
        print(f"  note: could not read bad_words_ids ({e}); app defaults to masking pad.")

    cfg = {
        "eos_id": int(tok.eos_token_id),
        "pad_id": int(tok.pad_token_id),
        "unk_id": int(tok.unk_token_id) if tok.unk_token_id is not None else int(tok.pad_token_id),
        "decoder_start_id": int(dec_start),
        "bad_word_ids": bad_word_ids,
        "vocab_size": int(getattr(config, "vocab_size", len(vocab))),
        "d_model": int(getattr(config, "d_model", 0)) or None,
        "model": MODEL_ID,
    }
    with open(os.path.join(out, "config.json"), "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    print(f"Tokenizer: {len(vocab)} vocab, {n} source pieces; "
          f"eos={cfg['eos_id']} pad={cfg['pad_id']} unk={cfg['unk_id']} "
          f"dec_start={cfg['decoder_start_id']} bad_word_ids={cfg['bad_word_ids']}")


def human(nbytes):
    return f"{nbytes / (1024 * 1024):.1f} MB"


def main():
    ap = argparse.ArgumentParser(description="Build FuguMT offline translation assets.")
    ap.add_argument("--model", help="local model dir (skip download)")
    ap.add_argument("--out", default=DEFAULT_OUT, help="asset output dir")
    ap.add_argument("--fp32", action="store_true", help="skip int8 quantization")
    ap.add_argument("--keep-work", action="store_true", help="keep the temp export dir")
    args = ap.parse_args()

    out = args.out
    os.makedirs(out, exist_ok=True)
    model_dir = resolve_model_dir(args.model)

    work = tempfile.mkdtemp(prefix="fugumt_onnx_")
    try:
        enc_src, dec_src = export_onnx(model_dir, work)
        quantize(enc_src, os.path.join(out, "encoder.onnx"), args.fp32)
        quantize(dec_src, os.path.join(out, "decoder.onnx"), args.fp32)
        dump_tokenizer(model_dir, out)
    finally:
        if args.keep_work:
            print(f"Left work dir: {work}")
        else:
            shutil.rmtree(work, ignore_errors=True)

    total = 0
    print("\nWrote:")
    for name in ("encoder.onnx", "decoder.onnx", "source_spm.json", "vocab.json", "config.json"):
        p = os.path.join(out, name)
        sz = os.path.getsize(p)
        total += sz
        print(f"  {name:16s} {human(sz)}")
    print(f"  {'total':16s} {human(total)}")
    print("\nDone. Bump Translator.MODEL_ASSET_VERSION if replacing an in-place install, "
          "then rebuild the app.")


if __name__ == "__main__":
    main()
