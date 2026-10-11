# Testing and Replay Status

## Available Checks

From `hybrid-ai-m1`:

```sh
./gradlew :pickleball-umpire:assembleDebug
./gradlew :pickleball-umpire:testDebugUnitTest
./gradlew :pickleball-umpire:lintDebug
```

The current JVM suite covers camera-timestamp metrics, court geometry and
homography round-trip, temporal frame order/windowing/orientation resets, WASB
heatmap peak interpretation, source-to-model affine and rotation mapping, ball
track transitions, contact-candidate gating, player ID association, singles/
doubles capacity, court filtering, and stale-track expiry. Builds and unit tests
have passed during implementation. Android instrumentation tests and physical
device checks have not been run.

## Model Export Verification

Run `scripts/setup-wasb-onnx.sh` to clone a pinned WASB source revision,
SHA-verify the tennis checkpoint, strict-load its PyTorch state, export ONNX,
check the ONNX protobuf, and compare ONNX Runtime CPU against PyTorch on one
seeded synthetic normalized input. The observed max absolute difference was
`1.38e-7`. This is not a video detection accuracy benchmark.

## Replay

The app's local video picker decodes via MediaExtractor/MediaCodec. Decoded
YUV frames enter the same `Yuv420Frame`, preprocessing, temporal buffer, and
WASB detector used by live camera input. Replay records detections/performance
in the same session JSONL. No video is copied, recorded, or uploaded by the app.

The core tracker/contact/player tests feed recorded-style observations, not
camera frames. The MediaCodec-to-ONNX replay path has compiled but has not yet
been exercised with a real annotated clip. There is no accuracy harness or
ground-truth annotation reader yet.

A labeled, reproducible pickleball video corpus and annotation reader are not
included yet. Before claiming accuracy, add held-out clips and annotations for
ball centers, player boxes/IDs, actual contact times/locations, and applicable
lines; report contact localization error and false IN/OUT decisions separately
from ball detection metrics.
