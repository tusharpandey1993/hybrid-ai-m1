# Pickleball Umpire Architecture

## Current State

`hybrid-ai-m1/pickleball-umpire` is a separate installable Android app. It is fully offline at runtime and has no `INTERNET` permission. Implemented behavior:

- Compose/Material 3 match setup, live scoreboard, manual scoring controls, summary, bounded JSONL event review, and local match history.
- CameraX rear Preview and YUV_420_888 ImageAnalysis; selected SD/HD/FHD resolution and a device-reported frame-rate target capped at 30 FPS; `STRATEGY_KEEP_ONLY_LATEST`.
- Portrait default with saved portrait/landscape switch.
- Eight labeled manual court correspondences, OpenCV RANSAC homography, approximate court overlay and lines.
- Phase A `ActiveCourtIsolationEngine`: the eight-point calibration selects one court ID and projects its polygon/net, near/far zones, and a 3 ft court-space player tracking margin. It classifies observations as active-court, extended-region, foreign, or unknown and stores assignment, active-ball, calibration-confidence, and court-lock state.
- Session-scoped JSONL logging, camera FPS/timestamps, processing measurements, suspected frame gaps, local log share.
- Real WASB tennis checkpoint exported to ONNX by a hash/source-pinned local script. The model asset is ignored by Git and must be generated locally.
- Reusable YUV_420_888 to RGB/ImageNet-normalized preprocessing using the official WASB centered equal-scale affine transform to the model input.
- Three timestamped frames in a fixed ring feed ONNX Runtime CPU inference on a private frame worker. Camera frames do not queue behind inference; busy frames close/drop and are logged.
- Heatmap peak returned as `DETECTED`, `BELOW_THRESHOLD`, or `SKIPPED_BUSY`; actual peaks can be overlaid and logged.
- Independent `BallTracker` state with `TRACKED`, `PREDICTED`, `LOST`, and `REACQUIRED`; a bounded two-second time trajectory. Predicted positions are visually distinct and never become observations.
- A MediaExtractor/MediaCodec local-video frame source feeds the same YUV frame contract and ball pipeline. It does not create a separate detector path.

Implemented after ball observations: a heuristic observed/predicted/lost/reacquired trajectory tracker, a conservative image-space motion-discontinuity contact-candidate generator, pure player association/court-filtering logic, and a standalone uncertainty-aware line-decision engine with focused unit tests. Traditional singles/doubles side-out scoring, manual rally awards, serve correction, undo, pause/resume/reset, and score snapshots are implemented independently and logged. An AI-supplied rally winner is confidence-gated, but no current pipeline produces validated rally-winner evidence; line-only or low-confidence outcomes remain `RALLY REVIEW REQUIRED`. Match summaries are persisted as small JSON records; detailed events remain JSONL. Optional app-private CameraX video recording is attempted with Preview+ImageAnalysis and falls back to analysis-only if the device rejects the use-case combination. Neither recording compatibility nor performance has been device-validated. Not implemented: a person detector feeding the player core, confirmed ground-contact detection, contact localization, live line-decision/rally-winner integration, player positions, critical-event evidence buffers, thermal response, comprehensive replay benchmarking, or pickleball model training. The current WASB weights are tennis-trained and experimental for pickleball.

## Active Court Phase A

`ActiveCourtIsolationEngine` consumes the existing eight-point homography and records one selected court ID, projected boundary polygon and net, near/far team zones, and a 3 ft extended player-tracking region. Camera points classify as active court, extended tracking region, foreign, or unknown. Calibration confidence is a quality/inlier heuristic, not a probability.

`PlayerTracker` now retains a player foot point within the extended region, so a short step outside the lines does not discard the stable ID. Explicit team assignments include both team and near/far zone. `COURT_LOCKED` requires acceptable calibration and balanced assignments: one player per team in singles or two per team in doubles. Recalibration/clear emits `COURT_TRACKING_LOST`.

The engine is unit-tested against four candidates on one selected court and four on a neighboring court, step-out/reentry, team-balanced lock, reassignment unlock, and invalid calibration. There is still no person detector feeding candidate assignments, so the live camera cannot populate those assignments or show a true court lock. The active-ball slot is not ownership evidence.

## Ball Association Phase B

`BallAssociationEngine` is a pure candidate-ownership state machine. It tracks candidate continuity/velocity, active-court relevance, player-hit evidence, short occlusions, foreign origin, and crossing ambiguity. A foreign-origin candidate cannot become active from location or continuity alone; active ownership needs manual assignment, assigned-player hit evidence, or continuity from an already active trajectory. Ambiguous crossings report `UNKNOWN`.

The engine is not connected to `WasbLiveBallPipeline`. WASB currently returns one global heatmap maximum and is not verified as a multi-ball detector, so no validated per-court candidate stream feeds this engine. Phase B unit tests do not prove live multi-ball isolation.

## Processing Path

```text
CameraX ImageProxy or MediaCodec decoded YUV image
  -> bounded single-flight FrameAnalyzer / video frame consumer
  -> Yuv420Frame(timestamp, size, rotation, Y/U/V plane strides)
  -> WasbYuvPreprocessor (I420 conversion, official centered affine, resize, RGB normalization)
  -> three ordered TemporalRgbFrame values with camera/presentation timestamps
  -> BallDetector interface
  -> WasbOnnxBallDetector (ONNX Runtime CPU)
  -> sigmoid heatmap peak + status + mapped image position
  -> BallTracker (observed/predicted/lost/reacquired state)
  -> JSONL and debug overlay
```

`BallDetector` is runtime-neutral. ONNX Runtime is the first adapter because the real upstream checkpoint exported and passed CPU numerical parity. LiteRT is not evaluated. The ONNX session expects one static input tensor named `frames`, `[1,9,288,512]`, and output `heatmap`, `[1,1,288,512]`. The wrapper selects the third per-frame heatmap and applies sigmoid. `BallTracker` consumes detector results but retains explicit observed versus predicted status.

The WASB training transform is three chronological RGB frames, each normalized per ImageNet mean/std after conversion to a tensor. Its source configuration specifies 3 frames in/out, 512x288, `rgb_diff=false`, scale 0. Upstream `get_affine_transform` uses one scale based on the longer image dimension and translates the source center to the model center; wide images are cropped and portrait images are padded. The app uses the same affine geometry and maps heatmap peaks through its inverse to raw frame coordinates. Rotation is retained in frame/result metadata; rotated-file pixel normalization is not yet validated against annotated frames.

## Frame and Resource Ownership

CameraX analyzer callback does not execute model inference. It takes at most one `ImageProxy` in flight on a private executor; newer callbacks while busy are closed immediately. The accepted proxy is converted/copied before it is closed in `finally`. OpenCV Mats and RGB/YUV buffers are reused. Temporal storage is limited to three float tensors. The ONNX session copies the current window into a reusable direct tensor buffer. Optional recording is video-only, app-private, file-size/storage-limited, and disabled by default; audio capture is not enabled.

At stop, analyzer shuts down, waits for its worker, then releases ONNX/OpenCV resources. A requested recording is finalized before the match summary is persisted; failed finalizations do not produce a review path. A MediaCodec replay owns and releases its extractor, decoder, and output images; cancellation requests stop and the callback receives the same core frame representation.

## Court Calibration Caveat

The existing court geometry uses 20x44 feet with kitchen lines seven feet from the net. Manual camera points are mapped by OpenCV `findHomography` with RANSAC, and the inverse is used to draw line geometry. Current calibration quality is not production validated: reprojection RMS now uses RANSAC inliers, but fixed thresholds have not been calibrated against real setups or resolutions. A homography maps points on the court plane only. Airborne-ball coordinates must not be treated as true ground coordinates.

## Model Research and Artifact Provenance

See [`MODEL_INTEGRATION.md`](../MODEL_INTEGRATION.md) and
[`ACCURACY_LIMITATIONS.md`](../ACCURACY_LIMITATIONS.md).

The local exporter pins upstream `nttcom/WASB-SBDT` source revision
`923462cacdeb3353b84ddebdedb3f4b7a8553b0f` and tennis checkpoint SHA-256
`9d391239ab10c733f8e5bfadf16ab72838e7a8ebc88e8ae2038501c03d42b4bb`. The actual
checkpoint strictly loaded with 1,481,427 trainable parameters. PyTorch vs ONNX
Runtime desktop CPU max absolute difference was `1.38e-7` on a deterministic
synthetic input. This is conversion parity only, not visual-model accuracy.

The upstream WASB source is MIT-licensed, but the linked weight does not have an
independent license statement in that repository. Do not redistribute its ONNX
conversion until model/checkpoint/data rights are verified. No model binary is
committed.

## Threading, Logging, and Failure Behavior

Camera frame work is single-flight and logs `FRAME_DROPPED` on worker saturation. The JSONL logger uses a bounded asynchronous queue. Model initialization is asynchronous; missing/invalid local model files yield an explicit “Ball model unavailable” state and a log record, never a fake detection. Camera performance numbers are not detector FPS. Replay and live detections log camera/presentation timestamp, peak x/y, mapped image x/y, score, result status, and temporal intervals.

## Build and Preparation

From `hybrid-ai-m1`:

```sh
PYTHON=../gliner-router-m1/.venv/bin/python ./scripts/setup-wasb-onnx.sh
./gradlew :pickleball-umpire:assembleDebug
```

The export helper requirements are in `scripts/wasb-export-requirements.txt`. Generated `.onnx` and `.onnx.data` files land under ignored app assets for local APK packaging; no runtime download is performed.

## Next Phases

1. Validate YUV plane/color/affine/rotation preprocessing against annotated frames, including actual contact-quality video.
2. Measure ONNX Runtime Android correctness, latency, memory, sustained FPS, and thermals on a named physical device; compare LiteRT only if equivalent export is available.
3. Validate the heuristic tracker on footage, then tune or replace it using measured loss/reacquisition/continuity.
4. Benchmark a licensed person detector on local court recordings, then add independent multi-player association.
5. Fix calibration quality to use inlier-only residuals and validate thresholds against known correspondences.
6. Implement ground-contact candidate/estimation, explicitly not using the court homography on airborne points.
7. Validate the standalone uncertainty-aware line-decision engine against official line/serve rules, then connect it only after ground-contact localization is validated.
8. Expand shared replay to player/contact/decision processing, critical-event packages, and accuracy benchmarking.

Do not mark later phases complete without their behavior, focused tests, device/video evidence, and documented metrics.
