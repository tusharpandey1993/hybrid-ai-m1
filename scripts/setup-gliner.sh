#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
model_dir="$project_dir/models/gliner"
revision="600fe62bfd9a374e48874f02ae0d94850d11c193"
base_url="https://huggingface.co/litert-community/GLiNER2.5-Decide-LiteRT/resolve/$revision"
package_name="dev.edgeai.prototype"
mode="${1:---download-only}"
if [[ "$mode" != "--download-only" && "$mode" != "--install" ]]; then
    printf 'Usage: bash scripts/setup-gliner.sh [--download-only|--install]\n' >&2
    exit 2
fi
mkdir -p "$model_dir/host_assets"
curl --fail --silent --show-error --location "$base_url/SHA256SUMS" -o "$model_dir/SHA256SUMS"
files=(
    gliner25_decide_s128_wfp16.tflite
    gliner25_decide_s256_wfp16.tflite
    gliner25_decide_s512_wfp16.tflite
    host_assets/word_embeddings_fp16.bin
    host_assets/tokenizer.json
)
for remote in "${files[@]}"; do
    expected="$(awk -v name="$remote" '$2 == name {print $1}' "$model_dir/SHA256SUMS")"
    [[ ${#expected} == 64 ]] || { printf 'Missing checksum: %s\n' "$remote" >&2; exit 1; }
    target="$model_dir/$remote"
    if [[ -f "$target" && "$(shasum -a 256 "$target" | awk '{print $1}')" == "$expected" ]]; then
        printf 'Verified cached %s\n' "$remote"
        continue
    fi
    printf 'Downloading %s\n' "$remote"
    curl --fail --silent --show-error --location --retry 3 "$base_url/$remote" -o "$target.part"
    actual="$(shasum -a 256 "$target.part" | awk '{print $1}')"
    [[ "$actual" == "$expected" ]] || { printf 'Checksum mismatch: %s\n' "$remote" >&2; exit 1; }
    mv "$target.part" "$target"
done
if [[ "$mode" == "--download-only" ]]; then
    printf 'Verified model assets: %s\n' "$model_dir"
    exit 0
fi
command -v adb >/dev/null
adb get-state >/dev/null
adb shell run-as "$package_name" true
adb shell am force-stop "$package_name"
adb shell mkdir -p /data/local/tmp/edgeai-gliner
adb shell run-as "$package_name" mkdir -p files
for remote in "${files[@]}"; do
    filename="${remote##*/}"
    temporary="/data/local/tmp/edgeai-gliner/$filename"
    adb push "$model_dir/$remote" "$temporary"
    adb shell run-as "$package_name" cp "$temporary" "files/$filename"
    adb shell rm "$temporary"
done
adb shell rmdir /data/local/tmp/edgeai-gliner
printf 'Model installed. Launching GLiNER Router.\n'
adb shell am start -n "$package_name/.GlinerActivity"