"""Repacks sherpa-onnx's Kokoro voice archive (tar.bz2) into a zip phones can unpack quickly.

Usage: python3 repack_voicepack.py <kokoro-multi-lang-v1_0 dir> <out.zip>
The model is stored uncompressed (it barely compresses); everything else is deflated.
Only the files the app uses are kept.
"""
import os, sys, zipfile

src, out = sys.argv[1], sys.argv[2]
files = ["model.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt", "LICENSE", "README.md"]
for d in ("espeak-ng-data", "dict"):
    for root, _, names in os.walk(os.path.join(src, d)):
        files += [os.path.relpath(os.path.join(root, n), src) for n in names]
with zipfile.ZipFile(out, "w") as z:
    for f in sorted(files):
        if f == "model.onnx":
            z.write(os.path.join(src, f), f, compress_type=zipfile.ZIP_STORED)
        else:
            z.write(os.path.join(src, f), f, compress_type=zipfile.ZIP_DEFLATED, compresslevel=6)
print(f"{len(files)} files, {os.path.getsize(out) / 1e6:.0f} MB")
