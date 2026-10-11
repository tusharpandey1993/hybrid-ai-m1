# Accuracy Limitations

See [`docs/pickleball/ACCURACY_LIMITATIONS.md`](pickleball/ACCURACY_LIMITATIONS.md)
for the complete list. With a locally generated model asset, the app emits
actual WASB heatmap-peak ball detections from camera or replay frames. It has a
heuristic single-ball trajectory tracker and a camera-space motion-discontinuity
contact-candidate generator, plus a standalone uncertainty-aware line-decision
engine with unit tests. It has no person detector feeding its player
association core or ground-contact confirmation/localization, and the line
engine is not connected to the live pipeline. ONNX export parity checks graph
conversion on a synthetic normalized input; it says nothing about ball accuracy
on real video.

The current single-ball tracker is a lightweight alpha-beta-style heuristic with
fixed timeout/confidence thresholds; it has no labeled trajectory validation,
occlusion model, or multi-candidate association. The tennis-trained checkpoint
may not generalize to pickleballs. The eight-point
calibration's current RMS includes RANSAC outliers and has unvalidated pixel
thresholds. Most importantly, a court homography is only valid on the court
plane; an airborne ball's image coordinate is not its ground-contact location.
Until contact localization is validated on labeled pickleball footage, any
near-line result must remain uncertain and no automatic IN/OUT decision should
be presented.

Camera and replay input pixels are rotated into upright orientation before model
preprocessing, and detections are mapped back to raw camera-buffer coordinates.
This mapping has pure coordinate tests but has not been checked against actual
rotated videos or CameraX device frames; validate against annotated frames before
using its positions for contact or court geometry.