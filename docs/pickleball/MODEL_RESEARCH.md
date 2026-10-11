# Pickleball Model Research

Research checked 2026-10-11. The model/source measurements in this document
come from the cited projects. They are not reproduced benchmarks.

## Swing / TennisVision Findings

Source: [AtomGradient/Swing](https://github.com/AtomGradient/Swing), especially
its README, `TennisVision/Camera/FrameProcessor.swift`,
`TennisVision/Inference/BallTrackerInference.swift`, and
`Scripts/convert_wasb.py`.

The repository describes TennisVision as an iOS app using Core ML and Apple
Vision. Its ball path is a WASB-SBDT HRNet implementation:

- Three consecutive camera frames are resized to 512x288, held in a FIFO, and
  stacked channel-wise as RGB planar FP32 in `[0,1]`.
- Model input is `[1,9,288,512]`. The converter's HRNet emits three heatmaps;
  a wrapper selects the final frame's heatmap and applies sigmoid, yielding
  `[1,1,288,512]`.
- The Swift inference code copies into a reused `MLMultiArray`, finds the
  heatmap maximum with Accelerate/vDSP, and treats that maximum as the position
  and confidence. A threshold is documented in README; this is a peak score,
  not calibrated probability.
- The frame processor uses Core Image to resize into BGRA and loops over pixels
  to build planar RGB floats. This works as a reference but should not be copied
  as the Android preprocessing path; Android should benchmark direct YUV-plane
  preprocessing or a fused native path.
- The README reports 1.48M parameters, 2.6 MB Core ML package, 94% tennis F1,
  and approximately 3-5 ms WASB inference on iPhone 15 Pro Max. Those are
  author-reported iOS/tennis results; no Android or pickleball figures were
  verified.
- The README describes a serial inference engine with backpressure that drops
  incoming frames while work is in flight. It runs ball and pose inference on
  each accepted frame and court detection every tenth frame. Source timestamps
  should be preserved in our temporal buffer; an arbitrary processed-frame
  sequence is not a substitute for time.
- Swing includes a Core ML package and conversion code that takes an external
  checkpoint path. The checked-in source does not pin the exact checkpoint hash
  used to create its package, so that package cannot be proven identical to the
  upstream tennis model downloaded below. No Android LiteRT/ONNX artifact is
  supplied by either repository.
- The Swing repository page did not expose a root license file. Do not copy its
  app/conversion source or redistribute its converted model without obtaining
  and recording explicit licensing/provenance for those exact artifacts.

## Upstream WASB Findings

Source: [nttcom/WASB-SBDT](https://github.com/nttcom/WASB-SBDT),
[MODEL_ZOO](https://github.com/nttcom/WASB-SBDT/blob/main/MODEL_ZOO.md),
[license](https://github.com/nttcom/WASB-SBDT/blob/main/LICENSE.md).

The project describes WASB as a sports-ball detection/tracking baseline and its
source repository declares MIT licensing. `src/setup_scripts/setup_weights.sh`
maps the tennis model name to the Google Drive object used below. The repository
does not include a separate license statement for that externally hosted model
file. Treat this checkpoint as a local experiment; do not redistribute it until
the weight and underlying dataset rights are confirmed.

### Verified Tennis Checkpoint

- Upstream name: `wasb_tennis_best.pth.tar`.
- Source: `nttcom/WASB-SBDT/src/setup_scripts/setup_weights.sh`, Google Drive ID
  `14AeyIOCQ2UaQmbZLNQJa1H_eSwxUXk7z`.
- Downloaded locally for investigation only; 6,102,633 bytes.
- SHA-256: `9d391239ab10c733f8e5bfadf16ab72838e7a8ebc88e8ae2038501c03d42b4bb`.
- Loaded using PyTorch `weights_only=True`: checkpoint key `model_state_dict`,
  428 state entries. Instantiating the upstream HRNet reports 1,481,427 trainable
  parameters; the raw sum of checkpoint tensors is 1,488,250 because it also
  includes BatchNorm running statistics/counters. Strict checkpoint loading
  succeeds; ONNX export passes CPU numerical parity with maximum absolute error
  `1.38e-7` on a deterministic normalized random tensor. This does not establish
  real-video accuracy or Android performance.
- The upstream `src/configs/model/wasb.yaml` specifies HRNet, 3 input frames, 3
  output heatmaps, input/output resolution 512x288, `rgb_diff: false`, and output
  scale 0. The official dataset transform applies RGB `ToTensor` followed by
  ImageNet normalization (mean `[0.485,0.456,0.406]`, std
  `[0.229,0.224,0.225]`).
- Official HRNet returns three raw heatmaps at scale 0. The WASB
  `TracknetV2Postprocessor` applies sigmoid, then threshold/NMS/blob logic. Swing
  wraps the graph to sigmoid only the last frame into `[1,1,288,512]`. Do not
  conflate raw model logits, activated heatmaps, and postprocessed detections.

### Android Export Decision

The PyTorch checkpoint has been strictly loaded from the official source and
exported to ONNX. Desktop ONNX Runtime CPU parity passed, and the Android app now
has a CPU adapter. This does not complete runtime selection for production:
Android inference parity, sustained latency/FPS, memory, and thermals remain
unmeasured because no device is connected. LiteRT is not selected unless an
equivalent converter path is demonstrated and measured.

## Candidate Decision

Use WASB-SBDT/HRNet as the first *experimental architecture*, not as an assumed
pickleball solution. The temporal heatmap task aligns with tiny, fast-moving
balls, and the model is materially smaller than the TrackNet comparison in
Swing's README. But tennis-to-pickleball transfer is an explicit risk: ball
diameter, color, court scale, camera angle, motion blur, lighting, and bounce
appearance differ. A high heatmap peak can still be a false positive or a
poorly localized impact.

Keep `BallDetector` runtime-neutral. The local setup/export script can prepare
an offline APK with this model in the app assets. The checkpoint and generated
ONNX file remain ignored/uncommitted while redistribution rights are unresolved.
The default APK still has no bundled model until that local preparation step is
run.

## Size, Memory, and Latency

The instantiated model has 1,481,427 trainable parameters. At FP32, trainable
weights alone occupy 5,925,708 bytes (about 5.65 MiB), calculated as parameter
count times four bytes. The README's 2.6 MB refers to a converted Core ML package and is not an
Android package-size estimate. Quantized/FP16 conversion may reduce storage but
requires accuracy/parity testing.

The source input tensor uses 5.06 MiB at FP32; its heatmap output uses about
0.56 MiB. Peak runtime RAM is unknown because HRNet intermediate activation
buffers and LiteRT delegate/runtime allocations are not documented by Swing.
Measure peak RSS on an actual Android device after export.

No Android latency estimate is asserted. The `<10 ms` value is a desired target
for later device testing, not a current result. Benchmark warm and cold inference,
preprocessing, total observation latency, sustained FPS, memory, and thermal
change on the same devices and recorded clips.

## Player Models

Do not choose a person detector by model-family popularity alone. Phase 7 should
benchmark at least one mobile-native detector (EfficientDet-Lite0 or MobileNet
SSD) and one tiny YOLO export with the same clips and measure person recall at
court scale, false spectators, latency, model size, runtime support, and license
for both code and weights. Person tracks are secondary to ball/bounce information
and should be filtered by foot-point court position. This comparison is not yet
performed, so no winner or inference timing is claimed.

## Benchmark Plan

Use the same timestamped videos and annotation split for every model. Report:

- ball detection precision/recall and center localization error in camera pixels
- court-coordinate bounce localization error, especially at baselines/sidelines
- track-loss duration and reacquisition time
- bounce candidate precision/recall, including non-bounce direction changes
- near-line classification and uncertainty coverage
- preprocessing/inference/end-to-end p50 and p95 latency
- peak RSS and sustained thermal/FPS drift

Keep a held-out pickleball set across indoor/outdoor, ball/court colors, lighting,
camera placements, occlusion, and shot speed. Tennis F1 is not a proxy for the
eventual metric: bounce localization error relative to a boundary.