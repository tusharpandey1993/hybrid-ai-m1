# Existing Pickleball App Audit

Audit date: 2026-10-11. Scope: `hybrid-ai-m1/pickleball-umpire` and the Android
Gradle root that includes it. The adjacent voice/GLiNER application is not part
of this feature and is left unchanged.

## Build and Dependencies

- Root project: `hybrid-ai-m1`, Gradle 9.1.0, AGP 9.0.1, built-in Kotlin
  2.2.0, JDK 17, compile/target SDK 36, min SDK 26.
- The new `:pickleball-umpire` app has application ID `dev.edgeai.pickleball`.
- CameraX 1.6.2: camera-camera2, camera-lifecycle, camera-view.
- OpenCV Android AAR 4.12.0; Apache-2.0 metadata.
- `:pickleball-umpire` uses ONNX Runtime Android 1.31.0 for a locally generated
  WASB ONNX model. The model binary and its external-data sidecar are ignored by
  Git and are only packaged after running the local verified setup/export
  script. The sibling `:app` separately uses LiteRT 2.2.0.
- The manifest requests CAMERA only; no INTERNET permission, video recording,
  database, account, analytics, or backend is present.

## Current Frame and Thread Path

`MainActivity` binds CameraX Preview and YUV_420_888 ImageAnalysis. Analysis
requests 1280x720 with `STRATEGY_KEEP_ONLY_LATEST`; a dedicated frame worker
processes at most one proxy, immediately closes newer proxies, and closes each
accepted proxy in `finally`. It copies YUV planes to reusable storage, converts
through OpenCV to RGB, applies the verified affine/normalization, collects three
timestamped frames, invokes the real ONNX graph, then updates tracking and
candidate generation. Processing duration now includes this pipeline when the
local model is installed; it is still not an Android inference benchmark.

The temporal buffer is a fixed-size three-frame ring and resets on image size or
rotation changes. Dropped frames are timestamp-gap estimates/worker-busy events,
not exact camera sensor sequence counts. A MediaCodec local-video source shares
the YUV and ball pipeline. Pure player association/court filtering and
camera-space contact-candidate cores exist, but no player model feeds the former
and no contact is confirmed as a ground bounce. The UI Activity still coordinates
camera/session/replay UI; JSONL writing remains on a bounded queue/worker.

## Calibration and Coordinate Spaces

The user taps eight named court anchors in the preview. `CalibrationTouchMapper`
uses CameraX `CoordinateTransform` to convert PreviewView points into
ImageAnalysis coordinates and back. `PickleballCourtGeometry` defines court
dimensions in feet (20 by 44), kitchen lines 7 feet from the net, center lines,
and boundary segments. `HomographyTransformer` maps camera pixels to court feet
and computes its inverse. `CalibrationOverlayView` draws the defined court lines
after an accepted calibration.

`CourtCalibrator` calls OpenCV `findHomography` with RANSAC and obtains an
inlier mask. Camera-pixel RMS is computed over inliers only; quality thresholds
are fixed pixel RMS buckets and a 0.5-foot RANSAC threshold. These values are
not validated against labeled calibration sessions or camera resolution.
Quality is a heuristic, not an uncertainty estimate or proof of geometric
accuracy. Validate it before line decisions.

The homography is valid for points on the court plane. Applying it to an
airborne ball does not recover the ball's ground projection. No ground-contact
or landing estimate exists yet.

## UI, Sessions, and Logs

The camera screen defaults to portrait, offers a persisted portrait/landscape
switch, and updates CameraX target rotation. Portrait uses FIT_CENTER so the
wide court is not cropped. Calibration and camera preview can run before a
session. Starting a session logs device/camera configuration, pre-session
calibration, one-second FPS/processing samples, and suspected frame gaps to a
private JSONL file. Stop waits asynchronously for the writer before enabling
sharing. It does not record video.

The current logger timestamp field uses `System.nanoTime`; frame measurements
also retain CameraX `imageInfo.timestamp`. These clock domains should remain
separate and be identified explicitly in future event records.

## Tests and Gaps

Current JVM coverage includes timestamp metrics, homography/court geometry,
official model affine and rotation mapping, temporal order/window resets,
heatmap decoding, observed/predicted/lost/reacquired ball tracking, heuristic
contact-candidate gating, and player association/court filtering/capacity/expiry.
There are no CameraX instrumentation tests, Android model inference tests,
recorded-video replay accuracy tests, confirmed ground-contact tests, line-rule
tests, thermal tests, or on-device performance measurements.

The JVM geometry tests do not exercise OpenCV RANSAC, UI-to-image transforms,
reprojection quality, or an actual calibration session. The ONNX model is
verified on desktop CPU parity only and is tennis-trained. The app now produces
ball detections/tracks and tentative camera-space motion candidates when the
asset is locally present, but it does not yet understand ground contact, player
locations from an ML detector, or play rules well enough to umpire.