# Model Integration Status

## Candidate

Candidate: upstream `nttcom/WASB-SBDT` tennis checkpoint
`wasb_tennis_best.pth.tar`, fetched by the upstream `setup_weights.sh` using
Google Drive ID `14AeyIOCQ2UaQmbZLNQJa1H_eSwxUXk7z`. Investigated file size is
6,102,633 bytes; SHA-256 is
`9d391239ab10c733f8e5bfadf16ab72838e7a8ebc88e8ae2038501c03d42b4bb`.

The pinned source revision is `923462cacdeb3353b84ddebdedb3f4b7a8553b0f`.
Its model config is HRNet with `frames_in=3`, `frames_out=3`, input and output
spatial sizes 512x288, and output scale 0. The exported wrapper returns only
the third frame's sigmoid heatmap, shape `[1,1,288,512]`; input is `[1,9,288,512]`
NCHW float32, with each chronological RGB frame ImageNet-normalized.

Checkpoint was loaded with PyTorch's `weights_only=True`; strict state loading
succeeded. It contains 428 state entries. The instantiated model has 1,481,427
trainable parameters; summing all checkpoint tensors gives 1,488,250 because
that includes BatchNorm buffers.

## Export Path

`scripts/export_wasb_onnx.py` imports the model from an external checkout at the
pinned source revision, verifies the checkpoint digest, strict-loads its state,
exports fixed-shape ONNX opset 18, and verifies ONNX Runtime CPU output against
PyTorch. A deterministic normalized random tensor produced max absolute error
`1.38e-7`. This verifies export math only, not localization accuracy.

Prepare a model-enabled local APK with:

```sh
cd hybrid-ai-m1
source ../gliner-router-m1/.venv/bin/activate
./scripts/setup-wasb-onnx.sh
./gradlew :pickleball-umpire:assembleDebug
```

The script stages source/checkpoint in `/tmp` and writes the generated ONNX file
and external-data file to an ignored app-assets directory. No binary weights
are committed. `WasbOnnxBallDetector` invokes the graph on-device when those
assets exist. `WasbYuvPreprocessor` reads YUV_420_888 directly, converts to RGB,
applies upstream ImageNet normalization, and uses the official centered
equal-scale affine transform for the model's 16:9 input geometry. `FrameAnalyzer` invokes preprocessing/model work on its private
single-flight worker and closes every camera proxy. The MediaCodec replay source
feeds the same `Yuv420Frame` contract and the same detector pipeline.

The clean-checkout APK has no model asset until the setup script runs and reports
the model unavailable instead of fabricating output. A separate BallTracker
reports observed/predicted/lost/reacquired state and maintains a bounded time
trajectory. A separate GroundContactDetector can emit low-confidence image-space
motion-discontinuity candidates; it does not confirm a ground bounce or estimate
a court-space landing point. There is no Android device inference, FPS, memory,
thermal, or accuracy measurement yet. The implemented result is a tennis-trained
detection plus heuristic tracking, not a pickleball-validated trajectory or
contact/line call.

## Runtime Choice and Blockers

ONNX Runtime Android 1.31.0 is the current adapter because the real PyTorch graph
exported and numerically matched ONNX Runtime CPU with max absolute error
`1.38e-7` on one deterministic synthetic input. `WasbOnnxBallDetector` uses the
CPU provider and a 2-thread session setting, fixed-shape reusable input/output
buffers, and one-flight backpressure. LiteRT export has not been attempted. This
does not settle runtime performance: no Android device is connected, so Android
inference latency, sustained FPS, peak memory, and thermal behavior are unknown.
Do not enable NNAPI/GPU delegates without output-parity and sustained-device
benchmarks.

The WASB repository's source code is MIT-licensed and its setup script publishes
the checkpoint link. The hosted weight has no separate license file in the
repository. Do not redistribute the weight/ONNX asset until the exact model and
training-data rights are confirmed. The tennis model is not a pickleball model.