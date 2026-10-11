#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SCRATCH="${TMPDIR:-/tmp}/pickleball-wasb"
SOURCE_DIR="${WASB_SOURCE_DIR:-$SCRATCH/WASB-SBDT}"
CHECKPOINT="$SCRATCH/wasb_tennis_best.pth.tar"
OUTPUT="$ROOT/pickleball-umpire/src/main/assets/models/wasb_tennis_v1.onnx"
SOURCE_REVISION=923462cacdeb3353b84ddebdedb3f4b7a8553b0f
CHECKPOINT_SHA256=9d391239ab10c733f8e5bfadf16ab72838e7a8ebc88e8ae2038501c03d42b4bb
CHECKPOINT_URL=https://drive.google.com/uc?id=14AeyIOCQ2UaQmbZLNQJa1H_eSwxUXk7z
PYTHON="${PYTHON:-python3}"

mkdir -p "$SCRATCH" "$(dirname "$OUTPUT")"

if [ ! -d "$SOURCE_DIR/.git" ]; then
    if [ -n "${WASB_SOURCE_DIR:-}" ]; then
        printf '%s\n' "WASB_SOURCE_DIR must point to a WASB-SBDT git checkout." >&2
        exit 1
    fi
    git clone --filter=blob:none https://github.com/nttcom/WASB-SBDT.git "$SOURCE_DIR"
fi
git -C "$SOURCE_DIR" fetch --depth 1 origin "$SOURCE_REVISION"
git -C "$SOURCE_DIR" checkout --detach FETCH_HEAD

if [ ! -f "$CHECKPOINT" ] || [ "$(shasum -a 256 "$CHECKPOINT" | awk '{print $1}')" != "$CHECKPOINT_SHA256" ]; then
    curl -fL --retry 2 "$CHECKPOINT_URL" -o "$CHECKPOINT.tmp"
    ACTUAL_SHA256=$(shasum -a 256 "$CHECKPOINT.tmp" | awk '{print $1}')
    if [ "$ACTUAL_SHA256" != "$CHECKPOINT_SHA256" ]; then
        rm -f "$CHECKPOINT.tmp"
        printf '%s\n' "Checkpoint SHA-256 mismatch: $ACTUAL_SHA256" >&2
        exit 1
    fi
    mv "$CHECKPOINT.tmp" "$CHECKPOINT"
fi

"$PYTHON" "$ROOT/scripts/export_wasb_onnx.py" \
    --wasb-root "$SOURCE_DIR" \
    --checkpoint "$CHECKPOINT" \
    --output "$OUTPUT"