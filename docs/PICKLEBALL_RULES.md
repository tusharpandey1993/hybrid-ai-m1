# Pickleball Rules Baseline

## Sources and Scope

The authoritative source is the [2026 USA Pickleball Official Rulebook](https://ebooks.usapickleball.org/books/USAP-Official-Rulebook/), linked from the [official rules page](https://usapickleball.org/rules/). The ebook is a 96-page image-backed viewer and did not expose searchable rule text in this environment. The rules below were checked against USA Pickleball's [2026 Rules Summary PDF](https://usapickleball.org/docs/rules/USAP-Rules-Summary.pdf), marked `Rev. 1/1/2026`. The summary itself says it is abbreviated and that the full rulebook prevails.

Section names below are the official summary headings, not invented numerical rule numbers. Exact rule numbers and edge cases must be checked in the full rulebook before implementing automatic calls. This document describes current code support; a pure state machine or classifier is not evidence that the camera observes the event.

## Implemented State Logic

| Rule / official reference | Required observations | State transition / fault | Confidence and one-camera support | Current support |
|---|---|---|---|---|
| Traditional side-out scoring — `Scoring: Traditional Side-Out Scoring` | Confirmed rally winner, serving team, singles/doubles mode, current score | Serving team earns a point; receiving team winning a rally changes service without a point | Winner and service state must be established. Camera pipeline does not currently establish rally winner | `MANUAL_ONLY`; pure ScoreEngine is unit tested |
| Game target and margin — `Scoring: Traditional Side-Out Scoring` | Score and configured target | First to 11 or 21 with a two-point lead completes the game | Fully observable from trusted score state; does not need CV | `MANUAL_ONLY` in app workflow |
| Doubles server exception and rotation — `Service Sequence` | Serving team, current server number, opening-server flag, fault/rally winner | Opening doubles service allows only the first server; later faults advance server 1 to server 2, then side out | Rally fault and service identity are not produced by CV; correct server side is not modeled yet | `MANUAL_ONLY`; rotation logic is unit tested |
| Undo, serve correction, and manual rally award | User correction | Restores the previous score/service snapshot or applies a manually confirmed outcome | Explicit human confirmation | `MANUAL_ONLY`; correction is logged |
| Active-court selection — eight-point calibration, not a pickleball rule | User-selected calibration points and homography quality | Selects one court polygon, net, near/far zones, and an extended tracking region | Calibration confidence is a heuristic and is not multi-court validated | `ASSISTED`; no automatic adjacent-court recognition claim |

## Rules Not Yet Enforced From Camera Evidence

| Rule / official reference | Required observations | State transition / fault | Confidence and one-camera support | Current support |
|---|---|---|---|---|
| Serve placement and legal service court — `The Serve`, `Service Sequence` | Server position, serving score/parity, diagonal target court, ground-contact landing | Wrong service court, out serve, or kitchen-line contact is a serve fault | Requires calibrated contact localization; one view may be occluded or ambiguous | `NOT_SUPPORTED` automatically; manual review only |
| Serve motion, volley serve, drop serve, and foot faults — `The Serve` | Ball/paddle contact, paddle/wrist/waist positions, server feet at contact, serve type | Violation produces a serve fault | Existing model detects neither paddle nor foot/contact sequence | `NOT_SUPPORTED`; do not infer an illegal motion |
| Two-bounce opening — `Two-Bounce Rule` | Serve bounce, receiving return bounce, serving-side return bounce, player contacts | Volley before both required bounces is a fault | Requires reliable court-space bounce and player/team association | `NOT_SUPPORTED`; mark uncertain until those signals exist |
| Non-volley-zone volley and momentum faults — `Non-Volley Zone` | Player foot contacts, volley/groundstroke classification, ball contact, post-volley momentum and re-establishment | Fault only for a volley with prohibited zone contact/momentum; simply entering the kitchen is legal | Requires pose/foot tracking and contact classification; no such models are connected | `NOT_SUPPORTED`; never fault merely for entering the kitchen |
| Line calls — `Line Calls` | Ground contact point, applicable boundary, serve/rally context, calibration and localization uncertainty | A regular rally ball touching an applicable line is in; a serve touching the non-volley-zone line is short/fault | A planar homography cannot map an airborne image point to its landing point | `NOT_SUPPORTED` operationally. A pure line classifier is disconnected from camera observations |
| Hinder and replay — full rulebook replay/hinder provisions | Foreign-ball/player intrusion, rally effect, player confirmation or official judgment | Confirmed hinder replays the rally; it does not award a point | Single-camera intrusion detection alone cannot establish material effect | `NOT_SUPPORTED`; no automatic replay or point |
| Other faults: second bounce, net contact, ball striking player/object, double hit, two-player hit | Ball, paddle, player, net, object contact and event order | Rule-specific fault and serving-team point/side-out outcome | Several required objects/contacts are not detected; any ambiguous evidence needs review | `NOT_SUPPORTED` automatically |

## Operational Safety

- `ScoreEngine` accepts an AI outcome only as an explicit `RallyResolution.Confirmed` carrying evidence and at least its configured confidence threshold. No connected CV producer currently creates such a validated winner.
- An IN/OUT line result by itself is not a rally winner. Low-confidence, foreign, overlapping, or unsupported observations must yield review/unknown, never an automatic point.
- The current player detector interface has no implementation. Active-court assignments remain empty unless explicitly supplied; therefore `COURT LOCKED` must not be shown from calibration alone.
- Court-isolation margin and confidence values are engineering heuristics, not USA Pickleball rules or measured probabilities.
- Multi-court video validation, person detection, multi-candidate ball tracking, contact localization, serving-position verification, two-bounce sequencing, kitchen-footwork, and hinder review remain future phases.
