# Performance Status

No Android device/emulator is connected in this workspace, so there are no
physical-device FPS, detector latency, memory, thermal, or battery measurements.
Do not use Swing's iPhone 15 Pro figures as Android estimates.

## Current Measurement Path

- CameraX timestamp cadence and frame dimensions are measured over one-second
  windows.
- Frame processing duration currently includes YUV copying/conversion,
  preprocessing, temporal buffering, and ONNX inference for accepted analyzer
  frames. Suspected frame gaps are inferred from timestamp intervals, not exact
  sensor sequence IDs.
- Replay reports decoded-frame progress and logs each detector observation.
- ONNX Runtime is configured for CPU with two intra-op threads; GPU/NNAPI are
  not enabled.
- ONNX export parity was checked on a deterministic synthetic tensor with
  desktop CPU runtime. This is correctness of export math, not Android speed.

## Required Device Benchmark

Record device model, Android build, camera lens/resolution/FPS range, model hash,
orientation, court position, lighting, and video version. Measure p50/p95
preprocess, inference, end-to-end time, effective temporal FPS, frame drops,
peak RSS, sustained thermal state, and battery over a long rally/replay. Compare
CPU with any delegate only after numerical output parity and sustained testing.

No latency target is claimed until this benchmark exists. Prioritize fresh ball
frames over player analytics and UI rendering once those pipelines are added.
