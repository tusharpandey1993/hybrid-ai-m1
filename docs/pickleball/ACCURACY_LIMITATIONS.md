# Accuracy Limitations

This document distinguishes current behavior from future decision logic.

## Current Build

- With a locally generated ONNX asset, camera/replay frames feed the real
  tennis-trained WASB ball detector and heuristic tracker. Detection is not
  pickleball-validated, and player pixels are not processed by a person model.
- The user supplies court correspondences manually. The overlay is a projection
  of the defined geometry, not independent proof that points were tapped
  correctly.
- Reprojection thresholds are heuristic and not validated. Current RMS uses
  RANSAC inliers, but its thresholds remain unvalidated against real setups.
- Phase A court isolation derives one polygon, team halves, and a 3 ft player
  tracking margin from manual calibration. The pure classifier separates a
  neighboring-court point beyond that margin, but no person detector feeds it;
  adjacent-court rejection and court lock are not camera-validated. A player
  track can persist just outside a line and retain its ID on return.
- Traditional side-out scoring supports singles/doubles, 11/21 targets, server
  rotation, win-by-two, manual correction, undo, and review-required gating.
  However, no current camera/umpire path supplies a validated rally winner, so
  the automatic score path is not operational. There is no person detector,
  confirmed ground contact, or contact localization. The standalone tested
  line-decision engine accepts a supplied ground-contact estimate; nothing in
  the live pipeline supplies one.
- Match setup, live scoreboard, controls, summaries, JSON match history, bounded
  event review, and optional app-private recording are implemented. CameraX
  Preview+Analysis+VideoCapture compatibility, recording continuity, and
  recording/inference performance are not validated on a physical device.
- A successful build or geometry unit test does not demonstrate court-scale
  localization or real-time capability.

## Model Transfer and Ground Contact

The available WASB weights are trained for tennis, not pickleball. Pickleball
ball appearance, speed, dimensions, lighting, floor, court geometry, camera
height, and motion blur may cause missed detections or false heatmap peaks.
The repository's reported tennis metrics do not predict pickleball performance.

A planar homography maps court-plane points. The ball is often airborne; mapping
its center through the court homography produces a fictitious ground location.
Line decisions must wait for a ground-contact estimate supported by temporal
and visual evidence. Image-Y reversal by itself is not evidence of a bounce.

The WASB adapter returns one global heatmap maximum; the checkpoint was not
validated for simultaneous multi-ball detection. A pure `BallAssociationEngine`
tests candidate continuity, foreign rejection, crossing ambiguity, occlusion,
and manual active-ball assignment, but no multi-candidate source feeds it. The
live pipeline must not treat its single heatmap peak as verified ownership.

## Decision Uncertainty

When contact evidence, calibration quality, tracking continuity, or localization
uncertainty is poor, the system must report `UNCERTAIN`. Near-line events should
remain distinct from confident IN/OUT candidates. A line call must account for
court line width, ball-contact footprint, and whether the line is relevant to
the active rule context. The pure line-decision engine enforces conservative
confidence/calibration gates and distinguishes uncertain near-line cases, but
its heuristics and serve-line behavior still need rule review and labeled-video
validation. It must not be treated as an operational line-calling system.

Manual score awards are labeled as manual. Low-confidence AI winner candidates
are downgraded to review, but no AI winner candidate producer is connected today.
Line event counts remain zero unless a future validated decision source reports
events; no summary statistics are fabricated.

## Evidence Required Before Claims

Do not claim automated line accuracy until a held-out pickleball video set is
annotated for ball observations and actual ground contacts. Report contact
localization error in centimeters, relevant-line false calls, uncertainty rate,
frame gaps/effective temporal sampling, and device/model-version-specific
latency. No such measurements currently exist.