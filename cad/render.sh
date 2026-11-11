#!/usr/bin/env bash
# Renders the printable parts, viewer pieces, preview images and interference checks.
# Usage: bash cad/render.sh   (needs openscad; PNGs need a display, e.g. xvfb-run)
set -u
cd "$(dirname "$0")"
SCAD=smart_glasses.scad
OUT=out
rm -rf "$OUT"; mkdir -p "$OUT/print" "$OUT/viewer" "$OUT/images" "$OUT/checks"
REPORT="$OUT/report.txt"
: > "$REPORT"
FAIL=0

run() {  # run <output> <part> [extra -D]
  local out="$1" part="$2"; shift 2
  openscad -o "$out" --export-format binstl -D "part=\"$part\"" "$@" "$SCAD" > "$out.log" 2>&1
}

echo "== Printable parts ==" >> "$REPORT"
for p in clip arm eyepiece sled front_cap battery_clip; do
  if run "$OUT/print/$p.stl" "$p" -D print_orient=true && [ -s "$OUT/print/$p.stl" ]; then
    echo "OK    $p" >> "$REPORT"
  else
    echo "ERROR $p"; tail -5 "$OUT/print/$p.stl.log" >> "$REPORT"; FAIL=1
    echo "ERROR $p" >> "$REPORT"
  fi
  grep -h "WARNING" "$OUT/print/$p.stl.log" | sed "s/^/      $p: /" >> "$REPORT" || true
done

echo "== Viewer pieces ==" >> "$REPORT"
for p in clip arm eyepiece sled front_cap battery_clip screen_board screen_glass xiao camera lens battery screws glasses eye; do
  if run "$OUT/viewer/$p.stl" "placed_$p" && [ -s "$OUT/viewer/$p.stl" ]; then
    echo "OK    $p" >> "$REPORT"
  else
    echo "ERROR $p" >> "$REPORT"; tail -5 "$OUT/viewer/$p.stl.log" >> "$REPORT"; FAIL=1
  fi
done

echo "== Interference checks (EMPTY = parts don't overlap) ==" >> "$REPORT"
for c in eyepiece-sled-near eyepiece-sled-far cap-sled-far cap-board-far eyepiece-cap eyepiece-lens \
         eyepiece-board eyepiece-glass sled-board sled-glass lens-glass-near cap-xiao cap-camera \
         eyepiece-xiao board-xiao-far arm-eyepiece arm-clip arm-glasses clip-glasses eyepiece-glasses \
         eyepiece-eye batclip-glasses batclip-battery batclip-clip; do
  run "$OUT/checks/$c.stl" "check_$c"
  if [ -s "$OUT/checks/$c.stl" ]; then
    echo "OVERLAP $c" >> "$REPORT"
  else
    echo "EMPTY   $c" >> "$REPORT"
  fi
done

echo "== Echo ==" >> "$REPORT"
grep -h "ECHO" "$OUT/print/eyepiece.stl.log" >> "$REPORT" || true

echo "== Sizes (mm, as printed) ==" >> "$REPORT"
python3 - "$OUT/print" >> "$REPORT" <<'PY'
import struct, sys, os
d = sys.argv[1]
for f in sorted(os.listdir(d)):
    if not f.endswith(".stl"): continue
    b = open(os.path.join(d, f), "rb").read()
    n = struct.unpack_from("<I", b, 80)[0]
    lo = [1e9]*3; hi = [-1e9]*3; vol = 0.0
    for i in range(n):
        o = 84 + i*50
        v = [struct.unpack_from("<3f", b, o + 12 + 12*k) for k in range(3)]
        for p in v:
            for a in range(3): lo[a] = min(lo[a], p[a]); hi[a] = max(hi[a], p[a])
        (x1,y1,z1),(x2,y2,z2),(x3,y3,z3) = v
        vol += (x1*(y2*z3-y3*z2) - x2*(y1*z3-y3*z1) + x3*(y1*z2-y2*z1)) / 6
    size = [hi[a]-lo[a] for a in range(3)]
    print(f"{f:18s} {size[0]:6.1f} x {size[1]:6.1f} x {size[2]:6.1f}   {abs(vol)/1000:5.1f} cm3  ~{abs(vol)/1000*1.27*0.45:4.1f} g PETG at 45% fill")
PY

echo "== Images ==" >> "$REPORT"
shot() {  # shot <name> <part> <camera rot x,y,z>
  if openscad -o "$OUT/images/$1.png" --imgsize=1600,1200 --viewall --autocenter --colorscheme=Tomorrow \
       --camera=0,0,0,$3,300 -D "part=\"$2\"" "$SCAD" > "$OUT/images/$1.log" 2>&1; then
    echo "OK    $1" >> "$REPORT"
  else
    echo "ERROR $1" >> "$REPORT"; tail -3 "$OUT/images/$1.log" >> "$REPORT"
  fi
}
shot iso      assembly 60,0,215
shot outside  assembly 90,0,0
shot top      assembly 0,0,0
shot front    assembly 90,0,90
shot cutaway  section  90,0,180
shot eyepiece_iso eyepiece 55,0,135

cat "$REPORT"
exit $FAIL
