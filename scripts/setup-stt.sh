#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
model_dir="$project_dir/models/stt"
archive="$model_dir/model.tar.bz2"
extract_dir="$model_dir/extracted"
asset_dir="$project_dir/app/src/main/assets/stt"
archive_sha="9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9"
model_name="sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
url="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$model_name.tar.bz2"
files=(
    encoder-epoch-99-avg-1.int8.onnx
    decoder-epoch-99-avg-1.int8.onnx
    joiner-epoch-99-avg-1.int8.onnx
    tokens.txt
)

mkdir -p "$model_dir" "$extract_dir" "$asset_dir"
if [[ ! -f "$archive" || "$(shasum -a 256 "$archive" | awk '{print $1}')" != "$archive_sha" ]]; then
    curl --fail --silent --show-error --location --retry 3 "$url" -o "$archive.part"
    actual="$(shasum -a 256 "$archive.part" | awk '{print $1}')"
    [[ "$actual" == "$archive_sha" ]] || { printf 'Model archive checksum mismatch\n' >&2; exit 1; }
    mv "$archive.part" "$archive"
fi

tar -xjf "$archive" -C "$extract_dir" --strip-components=1 \
    $(printf "$model_name/%s " "${files[@]}")
for file in "${files[@]}"; do
    source="$extract_dir/$file"
    [[ -s "$source" ]] || { printf 'Missing model file: %s\n' "$source" >&2; exit 1; }
    cp "$source" "$asset_dir/$file"
done

printf 'Verified %s (SHA-256 %s)\n' "$archive" "$archive_sha"
du -h "$asset_dir"/*