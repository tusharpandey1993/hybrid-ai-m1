# Voice Lab: Milestone 0 Baseline

Audit date: 2026-10-07. Scope: the existing Android project in `hybrid-ai-m1`,
not the parent VS Code extension or the separate Python experiment.

## Milestone Report

**MILESTONE:** 0 - Baseline and Project Audit.

**STATUS:** PASS for the existing baseline gates, with the pre-existing optional
external-oracle test skip and coverage limitations explicitly recorded below.

**CHANGES:** this document only. No production code, tests, dependencies,
permissions, model files or routing behavior were changed. Build outputs were
regenerated, both APKs reinstalled on the existing emulator, and the existing
instrumentation test rewrote its synthetic smoke-result JSON on that emulator.

**TESTS:** complete existing JVM suite rerun; both existing instrumentation tests
rerun; debug and androidTest APKs rebuilt; lint rerun; model checksums checked;
the reopened GLiNER screen reached `Ready: on-device CPU`.

**RESULTS:** 21 JVM tests passed, 1 optional JVM test skipped, 0 failures/errors;
2 instrumentation tests passed. Routing still selects `LOCAL_CODE` for the
existing `What is 27 * 14?` fixture, with unchanged scores.

**PERFORMANCE:** one warmed emulator adapter sample: 472.133 ms total,
465.334 ms graph; loaded-process snapshot: 1.998 GiB PSS / 2.123 GiB RSS.
These are not physical-phone measurements, percentile benchmarks or loading peaks.

**KNOWN LIMITATIONS:** per-adapter model ownership, deferred native cancellation
and cleanup, resident graphs, incomplete parity/lifecycle coverage, limited error
diagnostics, 16 existing lint warnings, and no voice subsystem yet.

**FILES CHANGED:** this document only.

**NEXT MILESTONE:** 1 - Pure Kotlin Conversation Domain and State Machine,
only after explicit approval. No Milestone 1 files have been created.

## 1. Existing Architecture

```text
GlinerActivity (native Android widgets, lifecycle-aware state collection)
  -> GlinerViewModel (StateFlow, operation Job, generation ID)
  -> AiRouter (suspend API)
  -> GlinerAiRouter (per-adapter serial coroutine worker)
  -> DecideClassifier
       Kotlin tokenizer + schema + token/label-marker inputs
       memory-mapped FP16 embedding table -> FP32 input tensors
       process-wide native worker + LiteRT Environment
       smallest fitting CPU FP32 graph: 128 / 256 / 512 tokens
       logits -> Kotlin softmax decoder
  -> five scores + top-two margin + timing -> Android UI
```

The unchanged taxonomy is `LOCAL_CODE`, `LOCAL_RAG`, `LOCAL_LLM`, `CLOUD_LLM`,
`WEB_TOOL`. It predicts a task category; none of these predictions executes a
tool, calls the cloud, retrieves knowledge or generates an answer.

The separate Calculator screen uses `TextRouter` and `BigDecimal` for supported
exact arithmetic. Its cloud/privacy/connectivity/budget controls demonstrate
deterministic policy only; no cloud provider is connected.

There is one Gradle `:app` module. No microphone, audio capture, VAD, STT, TTS,
endpoint detector, voice classifier, conversation controller, local generative
LLM, RAG or voice SDK module exists in the Android project.

## 2. Relevant Files and Configuration

| Existing file | Responsibility |
| --- | --- |
| [AiRouter.kt](../app/src/main/java/dev/edgeai/prototype/AiRouter.kt#L1) | Stable suspend `initialize`/`route` API, `Closeable`, five-route score contract and label descriptions. |
| [GlinerAiRouter.kt](../app/src/main/java/dev/edgeai/prototype/GlinerAiRouter.kt#L1) | CPU adapter, background worker, model warm-up, timing and queued cleanup. |
| [GlinerViewModel.kt](../app/src/main/java/dev/edgeai/prototype/GlinerViewModel.kt#L1) | Loading/ready/running/unavailable states, generation counter, cancellation, retries and error mapping. |
| [GlinerActivity.kt](../app/src/main/java/dev/edgeai/prototype/GlinerActivity.kt#L1) | Router/ViewModel ownership and text-classification UI. |
| [DecideClassifier.kt](../app/src/main/java/com/gliner25decide/DecideClassifier.kt#L1) | LiteRT graphs, shape validation, native buffers, shared Environment and native serialization. |
| [GlinerTokenizer.kt](../app/src/main/java/com/gliner25decide/GlinerTokenizer.kt#L1) | Kotlin SentencePiece Unigram/NFC/added-token processing. |
| [DecideSchema.kt](../app/src/main/java/com/gliner25decide/DecideSchema.kt#L1) | Ordered tasks, descriptions, structural markers and schema validation. |
| [DecideInputs.kt](../app/src/main/java/com/gliner25decide/DecideInputs.kt#L1) | Word splitting, token IDs, label routing, padding and mapped embedding lookup. |
| [DecideDecoder.kt](../app/src/main/java/com/gliner25decide/DecideDecoder.kt#L1) | FP32 softmax/sigmoid, tie and threshold semantics. |
| [DecideGateFixtures.kt](../app/src/main/java/com/gliner25decide/DecideGateFixtures.kt#L1) | Published fixture parsing and comparison helpers. |
| [MainActivity.kt](../app/src/main/java/dev/edgeai/prototype/MainActivity.kt#L1) | Separate deterministic Calculator/policy screen. |
| [RouterViewModel.kt](../app/src/main/java/dev/edgeai/prototype/RouterViewModel.kt#L1) | Synchronous bounded arithmetic/policy state, separate from GLiNER. |
| [TextRouter.kt](../app/src/main/java/dev/edgeai/prototype/TextRouter.kt#L1) | Full-expression arithmetic and deterministic cloud policy gates. |
| [AndroidManifest.xml](../app/src/main/AndroidManifest.xml#L1) | GLiNER launcher, internal Calculator activity, permissions and backup flag. |
| [app/build.gradle.kts](../app/build.gradle.kts#L1) | ARM64 app, runtime and test dependencies, SDK and test runner configuration. |
| [setup-gliner.sh](../scripts/setup-gliner.sh#L1) | Pinned downloads, host-side SHA-256 verification and private debug-app model staging. |

Build configuration inspected:

- AGP 9.0.1, built-in Kotlin; Gradle wrapper 9.1.0; JDK 17.0.19 on this host.
- `compileSdk=36`, `targetSdk=36`, `minSdk=26`; only `arm64-v8a` is packaged.
- `com.google.ai.edge.litert:litert:2.2.0`; actual app adapter selects CPU FP32
  with four requested LiteRT CPU threads. GPU support exists in imported host
  code but is not selected by the app adapter or this audit.
- AndroidX Activity 1.10.1; Lifecycle runtime/ViewModel 2.9.4.
- JUnit 4.13.2; test JSON 20240303; coroutine-test 1.10.2; AndroidX test runner
  1.6.2, core-ktx 1.6.1 and ext-junit 1.2.1.
- `android.uniquePackageNames=false` accommodates LiteRT runtime/API AARs sharing
  a namespace; the corresponding manifest warning still appears.
- Wrapper `validateDistributionUrl=false` is existing setup configuration, not a
  change made during this audit. It does not disable HTTPS certificate checking.
- Source manifest requests `ACCESS_NETWORK_STATE` only. No `INTERNET` or
  `RECORD_AUDIO`; the device test verifies absence of internet permission in the
  installed package. No model download happens inside the app.
- Native Android XML/widget UI, not Compose. The launcher shows input, five scores,
  margin, total/graph timing, token window and model-ready timing. The retry control
  is enabled only when unavailable. The Calculator remains a separate activity.

## 3. GLiNER Lifecycle and Memory Ownership

1. The GLiNER Activity's ViewModel factory creates a new `GlinerAiRouter` using
   application context. A ViewModel survives configuration recreation of its
   owning Activity; a different ViewModel can create another router.
2. ViewModel construction calls `load`. `initialize` switches to the adapter's
   serial dispatcher and constructs `DecideClassifier` there, not on Main.
3. Classifier construction checks that all three graphs, the tokenizer and table
   exist; it validates the table byte length, loads the Kotlin tokenizer vocabulary
   and maps the embedding table read-only.
4. The adapter performs one full warm-up using the real five-label described
   taxonomy. It does not call the imported five-pass startup helper or eagerly
   compile every window. The fitting window is selected by encoded length.
5. Each classifier caches graphs/buffers by `(window, backend)`. Other windows are
   compiled on first use, receive an untimed first pass, and remain cached until
   that classifier is closed. More than 512 encoded tokens is rejected.
6. ViewModel clearance calls adapter `close`. An atomic flag prevents duplicate
   adapter-close scheduling. Native graph/buffer/table-channel close runs queued
   on the adapter worker and then on the native worker.
7. The process-wide LiteRT Environment/native executor deliberately outlives
   Activity/ViewModel instances. Closing the table channel is not a promise of
   immediate unmapping or an immediate reduction in Android RSS.

**A shared Environment is not a shared model.** `DecideClassifier`, its tokenizer,
mapped table, and graph map are per `GlinerAiRouter` instance. There is no shared
reference-counted decision runtime or process-wide resource manager. No voice
consumer exists yet, so this audit adds no second consumer or model copy.

## 4. Threading, Coroutines and Cancellation

- UI handling and ViewModel state transitions occur on Main through
  `viewModelScope`; state writes use atomic `MutableStateFlow.update`.
- `initialize`/`route` use `withContext` on a per-adapter single-thread executor.
- Native graph creation, input building, execution/readback and close use
  `ProcessRuntime.call`: one process-wide executor plus blocking `Future.get` on
  the calling adapter worker. That waiting is off Main in the current adapter.
- The CPU runtime requests four threads for numerical execution; that is not a
  cap on total process threads. A runtime thread snapshot showed the adapter pool
  worker and `Decide-LiteRT`-named threads, alongside Android/ART threads.
- The ViewModel increments `generation` for loads/submissions and cancels the
  previous operation. Results/errors update state only when their generation is
  current. Editing input during a request cancels its operation and clears output.
- Already-running native inference is not preempted by coroutine cancellation;
  queued adapter/native work can delay a newer request or cleanup.
- State collection uses `repeatOnLifecycle(STARTED)`. This stops background UI
  collection, not model residency or necessarily ongoing native work.

No `GlobalScope` is used in the current routing path. The instrumentation test's
`runBlocking` bridges suspend calls on the test thread, not the app UI thread.

## 5. Existing Test Inventory

Complete fresh JVM suite, from the generated JUnit XML:

| Suite | Discovered | Passed | Skipped | Failures/errors | Scope |
| --- | ---: | ---: | ---: | ---: | --- |
| `TextRouterTest` | 12 | 12 | 0 | 0 | Arithmetic, unsupported inputs, divisions, input limits and all eight policy combinations. |
| `AiRouterTest` | 4 | 4 | 0 | 0 | Five-label contract, alternative scores/margin, normalization and non-finite rejection. |
| `GlinerViewModelTest` | 3 | 3 | 0 | 0 | Fake-router loading/results/cleanup, cancelled stale result, unavailable/retry and blank input. |
| `DecideDecoderTest` | 3 | 2 | 1 | 0 | Tie/activation and float-vs-double threshold semantics; optional external oracle parity. |
| **Total** | **22** | **21** | **1** | **0** | Entire existing JVM suite invoked. |

The skipped method is `oracleLogitsGiveOfficialDecisionsForEveryFixture`.
`ExternalTestData.resolve` uses an existing JUnit assumption requiring a configured
external `fixtures/oracle_fp32.json` corpus. That external corpus is not configured
here. No tests were disabled, assumptions changed or tolerances weakened in this
milestone. The 361-fixture decoder comparison has **not** been established by this
run; it is an explicit pre-existing verification gap, not a passed test.

All existing instrumentation tests passed in one unfiltered direct runner invocation:

1. `cpuInputsAndDecisionsMatchPublishedOracle`: the first three published fixtures,
   at each fixture's first window; exact token IDs/label positions; exact selected
   labels; finite scores within the existing absolute 0.02 oracle tolerance.
2. `fiveRouteAdapterRunsWithoutInternetPermission`: real CPU adapter and described
   five-label schema; arithmetic route, winner score within the existing 0.02
   reference tolerance, and no internet permission; exports synthetic result JSON.

This is not full fixture coverage, all-window parity, calibrated accuracy, concurrent
native cleanup validation, rotation/process-death testing, or voice evaluation.
The fake-router stale test uses cancellable `CompletableDeferred`; it does not
establish behavior for every non-cancellable native completion sequence.

## 6. Fresh Build and Test Commands

Run from the workspace root; all production/test inputs remained unchanged:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" hybrid-ai-m1/gradlew -p hybrid-ai-m1 \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:lintDebug --rerun-tasks --console=plain

adb -s emulator-5554 install -r hybrid-ai-m1/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r \
  hybrid-ai-m1/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w \
  dev.edgeai.prototype.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 exec-out run-as dev.edgeai.prototype cat files/gliner-smoke.json
```

Results: `BUILD SUCCESSFUL`, all 80 actionable build tasks executed in the fresh
run; direct instrumentation `OK (2 tests)`, reported test time 4.677 seconds.
Direct instrumentation was selected because prior Gradle connected-test cleanup
removed this app and its 2.45 GB private model data. Reinstall with `-r` and the
direct runner preserved the existing staged assets; no model reinstall was needed.

Report locations are the existing `app/build/test-results/testDebugUnitTest/`,
`app/build/reports/tests/testDebugUnitTest/` and Android lint XML/HTML reports.
Direct instrumentation reports to the terminal rather than generating a fresh
Gradle connected-test XML report. Its existing debug test rewrites only the
synthetic app-private smoke JSON.

Lint: **0 errors, 16 warnings**. Categories: `OldTargetApi` (1), `UnusedAttribute`
(2, API-28 accessibility heading with min API 26), `AndroidGradlePluginVersion`
(1), `GradleDependency` (6), `NewerVersionAvailable` (2), `PluralsCandidate` (1),
`ChromeOsAbiSupport` (1), `DataExtractionRules` (1), `MissingApplicationIcon` (1).
The AAR shared-namespace warning is separately emitted by manifest merging.
No dependency, SDK, localization, icon, backup or ABI changes were made to remove
these warnings. Passing lint does not mean the project is warning-free.

## 7. Device, Artifacts and Inference Baseline

**Current target:** `emulator-5554`, AVD `Pixel_10_Pro`, virtual model
`sdk_gphone16k_arm64`, Android 17 / API 37, `arm64-v8a`, 16-KiB-page emulator image.
Guest `/proc/meminfo` reports `MemTotal: 6144672 kB`; the emulator was configured
with a 6-GiB memory limit. This is not a physical Pixel or a physical-phone SoC.
No physical phone was connected or measured.

| Freshly measured artifact | Bytes | Interpretation |
| --- | ---: | --- |
| Debug app APK | 13,721,833 | Approximately 13.09 MiB; excludes external model assets. |
| Debug androidTest APK | 366,279 | Approximately 357.69 KiB. |
| s128 graph | 660,383,872 | FP16 weight storage, FP32 CPU computation. |
| s256 graph | 710,715,520 | Same contract at the larger token window. |
| s512 graph | 811,378,816 | Same contract at the largest token window. |
| FP16 embedding table | 262,166,528 | Read-only mapped, `[128011,1024]`, upcast to FP32. |
| Tokenizer | 8,333,952 | Kotlin host reads vocabulary/normalization. |
| **Five model assets** | **2,452,978,688** | About 2.45 GB decimal / 2.28 GiB; not runtime RAM. |

All five host-cached assets were hashed and matched the existing pinned manifest.
No model download was required. Conversion/source package revision remains
`600fe62bfd9a374e48874f02ae0d94850d11c193`; Fastino source-model revision is
`7ee5da4c2415e32259bcdc0b1a7367c32ce8d6f6`. The Android and separate Python Hub
revision IDs differ; parity should not be assumed from identical model names.

Fresh existing adapter fixture:

| Field | Observed |
| --- | --- |
| Request | Synthetic `What is 27 * 14?` |
| Winner | `LOCAL_CODE` |
| Winner score | 0.35081786 |
| Other scores | `LOCAL_LLM` 0.2298328; `LOCAL_RAG` 0.20808059; `CLOUD_LLM` 0.10936328; `WEB_TOOL` 0.10190542 |
| Total adapter latency | 472.132917 ms |
| Graph latency | 465.334167 ms |
| Encoded request/schema | 144 tokens, 256-token graph |
| Backend | CPU, FP32 computation, four requested threads |

The five scores match the previous recorded Android result to the serialized
precision. This is preservation of the existing fixture behavior, not a claim
of task-routing accuracy across natural speech or any large dataset.

The adapter timer starts inside its worker, so total time excludes the initial
UI/coroutine dispatch queue wait. It includes preprocessing and waiting on the
native executor. Graph timing is first input write through output readback, not
just the asynchronous `run` enqueue. Initialization/warm-up precedes this sample.
Graph timing excludes lazy compilation/first-pass warm-up, while adapter total
can include them if a new window is requested. Do not interchange these timings.

After the instrumentation run the unchanged Activity was reopened. Its screen
was subsequently confirmed `Ready: on-device CPU`. `am start -W` reported 660 ms
Activity launch, which is **not** model-ready or inference latency. A first UI
snapshot during startup said Loading; a refreshed snapshot confirmed Ready.

Loaded-process memory snapshot, PID 6326, one Activity:

- PSS: 2,094,904 KiB = approximately 1.998 GiB.
- RSS: 2,226,192 KiB = approximately 2.123 GiB.
- Native-heap PSS: 1,338,244 KiB; mapped-region PSS: 700,254 KiB.
- Snapshot is after reopening/loading and warm-up, not continuous peak monitoring.
- The earlier 2.02-GiB PSS / 2.15-GiB RSS snapshot was a different interactive
  process state; neither snapshot establishes a leak or a sustained-memory trend.

**NOT MEASURED:** physical-phone latency; p50/p95; true cold model load with
controlled filesystem cache; peak loading memory; release-build performance;
idle-vs-loaded repeated comparison; energy, battery, thermals; combined audio
resources; and every future VAD/STT/endpoint/TTS latency boundary. No model accuracy
conclusion is drawn from the three fixtures or one arithmetic example.

## 8. Risks Discovered, No Production Fixes Applied

| Priority | Evidence and risk | Consequence / appropriate later gate |
| --- | --- | --- |
| High for voice | `GlinerActivity` creates an adapter per owning ViewModel; `GlinerAiRouter` creates a classifier per instance. Only `ProcessRuntime.Environment` is process-wide. | Creating a second voice classifier by copying this pattern can duplicate substantial model memory. Preserve `AiRouter` and prove a single shared model lifecycle before adding a second GLiNER consumer in the approved shared-runtime milestone. |
| High for barge-in | Native calls use a queued executor and blocking `Future.get`; cancellation invalidates UI work but does not preempt native execution. | Never make VAD-triggered suppression wait for GLiNER. Native backlog and cleanup completion need separate tests/measurements before live voice. |
| High for resources | Graphs cache per window/backend until close; no background-idle eviction, memory-pressure callback or shared resource budget exists. Current loaded process is about 2 GiB PSS. | Additional STT/TTS/VAD models must not be assumed to fit. Do not optimize or alter ownership during this audit; measure concurrency/residency at the appropriate milestones. |
| Medium for diagnostics | ViewModel catches load/inference exceptions and maps them to generic failure enums, discarding the original cause. Close is asynchronously queued with no completion result exposed. | Missing assets, unsupported input, native failures and cleanup errors are not fully distinguishable from UI state. Future diagnostics should preserve sanitized causes without logging private text. |
| Medium for UI recovery | `clearResult` clears `failure` even when status is Unavailable. | Typing can remove the detailed failure-state message while the model remains unavailable; retry still exists. This is not fixed or claimed covered by current tests. |
| Medium for verification | One optional 361-fixture test is skipped; instrumentation samples only three fixtures/their first window; fake cancellation is cooperatively cancellable. | Do not claim full parity, native cancellation/cleanup, all-window correctness or shared-runtime readiness from these tests. |
| Medium for privacy | Normal UI does not explicitly write prompts to files; existing instrumentation writes fixed synthetic smoke results. No audio permission or capture exists. | Do not reuse fixture/result persistence for live transcripts without an explicit development-only policy. Baseline UI inspection extracted status only, not private input text. |
| Deployment | ARM64-only packaging, target API 36 on API 37 emulator, LiteRT namespace compatibility flag, 16 lint warnings; installer fetches the checksum manifest even with cached models. | Physical API-26+ devices, backup behavior, APK distribution, offline host setup and other ABIs are not established by this emulator run. No release-readiness claim. |

The current text classifier is not a fast audio speech detector, a semantic turn
controller, or an always-on voice classifier. It must not be invoked on every
audio frame or STT partial. The current five-route contract must remain intact.

## 9. Exact Proposed Milestone 1 Files

**Proposal only; none of the following files or voice packages exists yet.**
Adapt the current `dev.edgeai.prototype` package rather than creating a new app,
Gradle module, dependency stack or premature SDK abstraction:

```text
app/src/main/java/dev/edgeai/prototype/voice/turn/TurnType.kt
app/src/main/java/dev/edgeai/prototype/voice/conversation/ConversationState.kt
app/src/main/java/dev/edgeai/prototype/voice/conversation/ConversationContext.kt
app/src/main/java/dev/edgeai/prototype/voice/conversation/ConversationEvent.kt
app/src/main/java/dev/edgeai/prototype/voice/conversation/ConversationAction.kt
app/src/main/java/dev/edgeai/prototype/voice/conversation/ConversationController.kt
app/src/test/java/dev/edgeai/prototype/voice/conversation/ConversationControllerTest.kt
```

Responsibilities:

- `TurnType`: the seven requested semantic labels only; no model/scores inference.
- `ConversationState`: Idle, Listening, UserSpeaking, Thinking, AssistantSpeaking,
  TemporarilyPaused and Interrupted with immutable associated data.
- `ConversationContext`: thread/turn/response IDs, the original assistant text,
  a validated logical text resume position and interruption transcript. This is
  not a promise of sample-perfect audio/TTS resume.
- `ConversationEvent`: the requested synthetic listening/speech/transcript/turn/
  response/playback/cancel events, with IDs needed to reject stale completions.
- `ConversationAction`: CONTINUE, TEMPORARILY_PAUSE, CANCEL, ANSWER,
  ANSWER_THEN_RESUME and KEEP_LISTENING. Actions are declarative outputs only.
- `ConversationController`: a synchronous deterministic state/event transition
  function returning next state and actions; no Android, coroutine scheduler,
  LiteRT, GLiNER, microphone, speech engine, network or UI dependency.
- Tests: a documented state/event matrix, every supported semantic label in
  applicable states, response/position/thread preservation, backchannel recovery,
  clarification action, stop/new-topic cancellation, incomplete-turn waiting,
  late turn/response events and cancel/idempotency behavior. Explicitly include
  old turn 41 arriving after current turn 42. Unsupported transitions must have
  an explicit policy rather than silently corrupting saved state.

No existing production/test file or permission is proposed for modification in
Milestone 1. The current JUnit dependency is sufficient. Microphone, STT, TTS,
GLiNER voice inference, shared-runtime refactoring, response generation and nested
spoken clarification execution remain outside that milestone.

## 10. No-Change Verification and Acceptance

Before the audit, a deterministic aggregate SHA-256 was calculated over 39
existing files: sources/tests/assets, the inspected Gradle/wrapper configuration,
setup script, ignore file and existing README. Generated build output, models,
and this new docs directory are outside that source fingerprint.

Baseline fingerprint:

```text
a17f5d4f4298e100297588d8091f8dffde313f3da28069ccdc900da50746ae40
```

After this document was written, the same fingerprint was confirmed across all
39 existing inputs; all 16 documentation links resolved. That check verifies no
edits to the enumerated existing inputs; it is not a Git commit or a proof about
files outside this inspected Android scope.

Acceptance observed: both APK builds succeed; all runnable existing tests pass;
the existing documented optional oracle skip remains unchanged; native routing
works with the recorded scores; lint has no errors; production behavior is unchanged.
The baseline does not certify future voice functionality or production readiness.

**Stop after Milestone 0. Await explicit approval before creating Milestone 1 files.**