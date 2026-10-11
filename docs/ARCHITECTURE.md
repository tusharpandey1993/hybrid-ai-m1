# Pickleball Umpire Architecture

The full feature architecture and current phase status are documented in
[`docs/pickleball/ARCHITECTURE.md`](pickleball/ARCHITECTURE.md). The design
separates frame acquisition, temporal preprocessing, model detection, tracking,
court geometry, ground-contact estimation, rules, and logging.

Current implementation includes a Compose/Material 3 match workflow, independent
traditional scoring, manual corrections, JSON match history, CameraX analysis,
optional app-private video recording, manual court mapping, overlay,
JSONL/performance observability, a CPU ONNX Runtime adapter for the real
WASB-SBDT tennis checkpoint, direct YUV preprocessing, a bounded ball tracker,
camera-space contact candidates, local-video replay through the same pipeline,
pure player association/court filtering, and a standalone tested line-decision
engine. It does not contain a person detector, ground-contact
confirmation/localization, or integrated rules evaluation; the line engine has
no live ground-contact input. The ONNX asset is ignored by Git and must be generated locally with
`scripts/setup-wasb-onnx.sh` before the APK contains the model. See
`docs/MODEL_INTEGRATION.md` and `docs/TESTING.md` for validation boundaries.
Automatic rally scoring is not operational: no validated contact/line/rally-winner
event is produced by the camera pipeline, and players are not detected. Those
events remain manual or explicitly review-required; optional recording and
three-use-case camera support have not been device-validated. Phase A adds an
active-court polygon/net/zone model and extended player tracking region from
calibration, with deterministic adjacent-court and step-out tests. No person
detector feeds player assignments, so the live camera cannot establish `COURT
LOCKED` or active-ball ownership.