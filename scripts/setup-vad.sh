#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
model_cache="$project_dir/models/vad/silero_vad.onnx"
aar="$project_dir/app/libs/sherpa-onnx-1.13.8.aar"
asset="$project_dir/app/src/main/assets/vad/silero_vad.onnx"

download_verified() {
    local url="$1" expected="$2" target="$3"
    mkdir -p "$(dirname "$target")"
    if [[ -f "$target" && "$(shasum -a 256 "$target" | awk '{print $1}')" == "$expected" ]]; then
        printf 'Verified cached %s\n' "$target"
        return
    fi
    printf 'Downloading %s\n' "$target"
    curl --fail --silent --show-error --location --retry 3 "$url" -o "$target.part"
    local actual
    actual="$(shasum -a 256 "$target.part" | awk '{print $1}')"
    [[ "$actual" == "$expected" ]] || { printf 'Checksum mismatch: %s\n' "$target" >&2; exit 1; }
    mv "$target.part" "$target"
}

download_verified \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar" \
    "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96" \
    "$aar"
download_verified \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx" \
    "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6" \
    "$model_cache"

mkdir -p "$(dirname "$asset")"
cp "$model_cache" "$asset"
printf 'Sherpa-ONNX runtime and Silero VAD model verified. APK asset staged at %s\n' "$asset"