# Existing Pickleball App Audit

The detailed audit is maintained at
[`docs/pickleball/EXISTING_ARCHITECTURE.md`](pickleball/EXISTING_ARCHITECTURE.md).
The existing app is a separate `:pickleball-umpire` Android application with
CameraX, manual eight-point court calibration, OpenCV homography, court overlay,
portrait/landscape preference, session logging, and camera performance
measurements. It does not yet detect objects or events.

Key risks found in the existing code:

- `FrameAnalyzer` passes open camera frames to a single-flight background worker;
  real YUV pixels enter WASB preprocessing/detection and then the separate
  observed/predicted/lost/reacquired tracker when the generated local model
  asset is present.
- `CourtCalibrator` computes reprojection RMS over RANSAC inliers after the latest
  correction; its fixed thresholds are still not validated against labeled
  court setups or varied camera resolutions.
- A court-plane homography cannot map an airborne ball to its true ground
  projection.
- `MainActivity` currently owns use-case binding, calibration orchestration,
  session controls, and UI/log coordination; these need extracting as the live
  perception pipeline is added.
- Local video replay now decodes through MediaCodec into the same YUV frame and
  ball pipeline. A pure player association/filtering core and a camera-space
  contact-candidate heuristic exist; no person detector feeds the former, and
  the latter is not ground-contact confirmation. There is still no
  device-connected Android validation or annotated accuracy video run. Rules
  and line decisions are not implemented.