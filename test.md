# Smoke, Regression and Performance Test Plan

## Project and status

**Application:** C Nanjappa Cloth Center Android inventory app.

**Required capacity:** 100,000 distinct inventory items/variants, with large transaction history.

**Cost:** all test software and local execution must be free, with no paid device farm, cloud service, API, subscription, trial quota, or payment-card requirement. Use an existing computer and phone. Hardware, electricity, physical printing and staff time are separate costs; they cannot be promised free. Printer purchase is not necessary to test the main existing-barcode workflow.

**Status: NOT RUN.** This file specifies tests to implement and execute. It is not evidence that an app, test suite, 100,000-item fixture, benchmark, or production release already exists.

Read `android_inventory_requirements.md` and `agent.md` before implementing this plan. Keep all three files together. Latest decisions: existing barcode linking is the default; printing is optional; the Home Stock overview button exports Excel; the Stock tab retains on-screen quantities; no low/out-of-stock widgets; Full/Half is required; multiple companies and mixed code formats are supported.

## 1. Free tools and test environments

| Purpose | Local tools / approach |
|---|---|
| Domain logic | JUnit/Kotlin tests, deterministic fixtures, reference-model checks |
| Actual database behaviour | AndroidX instrumented Room tests against SQLite, migration testing utilities |
| App UI | Compose UI tests; UI Automator for app/system interactions |
| Startup and scrolling | AndroidX Macrobenchmark against a release-equivalent, non-debuggable profileable build |
| Timing investigation | Perfetto/system traces and monotonic application trace markers |
| Memory / thread / allocation checks | Android Studio profiling, `adb dumpsys meminfo`, bounded repeatable journeys |
| Power / resource checks | Android battery statistics, supported power profiling and system traces; physical-device comparison |
| Workbook verification | A separate local reader, such as Python `openpyxl`, and a compatible free spreadsheet viewer |
| PDF verification | Local PDF renderer/parser plus barcode decoding of rendered label samples |
| Reports | Local JSON/CSV, JUnit XML, trace files and Markdown; no telemetry upload |

Pin compatible versions when the Android project is available. Do not require a paid test platform or an online spreadsheet converter. Build/dependency installation may initially require downloads; app runtime and installed test execution must not depend on internet access.

Use an emulator for deterministic functionality, lifecycle, UI and database checks. Use named physical phones for release performance, optical camera recognition, battery and heat. Aim for the actual shop phone plus a midrange comparison phone; if only one is available, report the narrower coverage. Missing hardware is **BLOCKED**, not PASS. Power rail counters and thermal metrics are device/API dependent; do not fabricate unsupported readings or root a user's phone to obtain them.

Never test against real shop inventory. Use a separate test application ID/data directory and generated data. Test-only seed/reset/fault endpoints must be absent from production builds. Never uninstall, clear, corrupt, or downgrade the real app to run a test.

## 2. Data fixtures: what 100,000 items means

An item here is an independently searchable **product variant record** with its own size/sleeve/colour/company identity. Stock quantity is a separate number. A single record with quantity 100,000 does not satisfy this test.

| Fixture | Records and purpose |
|---|---|
| SMALL | Empty installation, then ~100 variants; fast developer smoke tests |
| CAPACITY | 20,000 product groups, exactly 100,000 distinct variants, 1,000,000 valid historical movements; primary release gate |
| DISTINCT_PRODUCTS | 100,000 distinct product groups with one variant each; confirms grouping/search do not rely on only 20,000 names |
| LARGE_HISTORY | CAPACITY plus >1,048,575 movement rows to exercise Excel history-sheet splitting |
| OPTICAL | At least 200 varied real/printed supported labels, linked to records distributed throughout CAPACITY |

Generate CAPACITY with a fixed random seed, for example `20261007`, and save fixture generator version, seed, record counts and SHA-256 digest. Use a coherent database generated through the same invariants as the app; do not create impossible sales/returns merely to reach a row count. Seed in bounded batches outside timed benchmark sections. Restore the same starting fixture before comparable measurements.

Include:

- Ramraj, Udyam, Puma and other synthetic companies; same descriptions across brands without shared stock.
- Full/Half; alphabetic sizes, every integer 36–50, custom 32/52/Free Size; White/Cream and custom colours.
- At least 10% zero-stock and 5% archived variants, with both zero and remaining stock. Keep expected counts in the fixture manifest.
- Direct code mappings, multiple aliases on one variant, explicit ambiguous codes across sizes/brands, unsupported and unknown codes.
- EAN/UPC leading-zero identities and carefully validated equivalences; Code 128/39, QR and Data Matrix; realistic payload lengths. Generate valid checksums using a verified generator, not arbitrary numeric strings labelled EAN.
- Partial/full good returns, damaged returns, reversals, restocks and counted adjustments with valid related-sale eligibility.
- Hot frequently sold variants and cold variants at the beginning, middle and end of the dataset; long readable names, custom text beginning with `=`, `+`, `-` and `@`, and non-ASCII company/model text.

Barcode count need not equal variant count: aliases and ambiguity candidates have their own recorded counts. Use realistic alias density and add a separate high-alias stress case. Optional thumbnail tests use bounded generated image assets; photos are not required for ordinary inventory tests.

### Independent correctness oracle

Maintain a simple independent reference model for generated operations. Do not test a DAO solely by calling the same DAO again and trusting its quantity. Compare stock, counts and return eligibility with the reference model and reconciled ledger.

For each variant:

`current stock = opening stock + restocks + sellable returns + reversals + signed corrections − sales`

Damaged returns have zero stock delta but consume sale eligibility. For every sale, returned quantity plus reversed quantity cannot exceed sold quantity. Linking aliases, exporting, printing and opening screens have zero stock delta. At each checkpoint verify no negative stock, duplicate committed operation ID, orphan references, ambiguous direct resolution, or incorrect cross-brand totals. Persisted stock and ledger must reconcile exactly.

## 3. Test tiers and run policy

| Tier | Run when | Scope |
|---|---|---|
| Smoke | After a candidate build or focused change | Core journey; SMALL plus a CAPACITY smoke pass before release |
| Focused regression | After changing a feature | Its relevant database/UI tests plus shared mutation/scanner checks |
| Full regression | Before release and after shared schema/scanner/export changes | All required cases at CAPACITY; DISTINCT_PRODUCTS coverage |
| Performance comparison | After code that can affect speed/rendering | Repeated controlled baseline/candidate journeys on physical phone |
| Soak, power and recovery | Before release and after lifecycle/resource changes | Eight-hour session and controlled idle/scan/thermal checks |

Durations are measured when a suite exists, not invented guarantees. An eight-hour test is intentional sustained verification, not something to rerun after every visual edit. Stop release work on data-loss/duplicate-mutation failures and fix those before broader testing.

Implement separate suite selection/tags or test packages so smoke does not accidentally invoke an overnight test. Document discovered Gradle tasks and test selectors. Do not disable flaky tests silently or keep rerunning until green; preserve the first failure, determine the cause and report any justified rerun.

## 4. Smoke test checklist

Run from a clean test installation with radios disabled. After seeding CAPACITY separately, repeat applicable steps with the large dataset; count seeding time separately from startup/search measurements.

| ID | Action | Required result |
|---|---|---|
| SM01 | Cold launch | Exact C Nanjappa Cloth Center branding, then usable Home; no login/download/network error |
| SM02 | Add existing code, company/model/White/40/Full, quantity 4 | One variant and alias; stock 4; no automatic PDF/print |
| SM03 | Save without sleeves or size | Short inline hint, retained form data, no write |
| SM04 | Scan in Sell, keep label still | Exactly one sale; green after commit; stock 3; held code does not sell again |
| SM05 | Scan next, clear frame, intentionally rescan | One additional sale; stock 2 |
| SM06 | Undo the second sale twice | Stock 3; second undo blocked; history retained |
| SM07 | Return one eligible good item | Stock 4; repeated submission creates no second return |
| SM08 | Add stock quantity 6 | Stock 10 with one restock; scanning/linking itself does not add pieces |
| SM09 | Link a different code to the same variant | Alias added, stock stays 10; either code resolves correctly |
| SM10 | Stock tab | Correct brand/size/sleeve quantities; no low-stock/out-of-stock widgets |
| SM11 | Home Stock overview | Genuine complete `.xlsx` workbook saved locally; app does not navigate to Stock instead |
| SM12 | Back up, restart, restore to disposable test data | IDs, aliases, ledger and stock preserved; old label resolves |
| SM13 | Search CAPACITY for beginning/middle/end and missing records | Correct result/empty state with responsive UI |
| SM14 | Scan labels from different companies/formats | Correct linked variant, no brand-mode switch or wrong deduction |
| SM15 | Background/lock after scan | No live camera/torch or replayed sale on resume |
| SM16 | Camera permission denied | Manual Find product and Sell 1 work; brief guidance |

Smoke pass requires all applicable assertions, reconciliation and zero app crashes/ANRs. No real scanner or build available means the corresponding cases remain NOT RUN/BLOCKED. Synthetic decoder injection cannot certify optical camera recognition.

## 5. Regression suite

Map these cases to detailed IDs in the requirements file so both checklists remain traceable. Expand parameterized tests instead of copying near-identical code. Tests must assert user-visible state and persisted invariants.

| Area / IDs | Cases | Required result |
|---|---|---|
| RG-P01 Product validation | Missing required values; negative/fractional/overflow quantity; oversized/control-character text | Precise short hint, no partial write, input retained |
| RG-P02 Variant identity | Case/spaces, L/Large, XXL aliases, numeric vs letter sizes, companies/colours/sleeves | Intended deduplication only; unrelated variants remain distinct |
| RG-P03 Choices | White/Cream priority, custom colour cancellation/save, every size 36–50 plus 32/52 | Tappable saved values, no false default, cancelled entries not saved |
| RG-P04 Edit/archive | Spelling edit, attempted merge, archive with stock, linked return | Stable codes/history; sale/restock blocked while archived; returned stock visible |
| RG-B01 Existing-code stocking | Shared code on identical pieces; different codes on one variant; batch linking | Quantity entered once; alias registration never alters stock |
| RG-B02 Mixed formats | All required formats, leading zeroes, tested UPC/EAN equivalence, first use offline | Exact correct lookup; no guessed identity, downloads or brand switch |
| RG-B03 Ambiguity | Same code across companies/sizes; two unrelated labels in frame; verified aliases visible together | Explicit choice/aim guidance where needed; at most one mutation |
| RG-B04 Nonproduct codes | Unknown, malformed, encrypted/unsupported code, shared promotional/payment/homepage QR | No automatic sale, URL opening or internet lookup; manual fallback |
| RG-S01 Last-piece race | Two sale requests attempt stock 1 concurrently | One committed sale, other rejected; stock 0 |
| RG-S02 Idempotency | 100 callbacks, duplicate taps/retries, same operation ID with different payload | One original operation only; conflicting payload rejected |
| RG-S03 Scan state | Scan next rearm, old label held, navigation/rotation with stale callbacks | One sale per intentional operation; no automatic delayed repeat |
| RG-S04 Failed writes | Failure before commit and between update/history steps | Transaction rolls back; no green/haptic success; stock/history consistent |
| RG-S05 Recovery | Process death before commit, after commit before display, unresolved retry | Resolve durable outcome; committed sale recovered once, never replayed blindly |
| RG-R01 Returns | Good/damaged, partial/full, limit exceeded, no eligible sale, wrong variant | Correct eligibility; damaged return does not add sellable stock |
| RG-R02 Return race/undo | Two requests for last eligible return; partly returned sale followed by undo | Atomic limits; only remaining quantity reversed once |
| RG-A01 Restock/correction | Quantity changes, required reason, zero count correction, duplicate retry | One correct signed movement; no invisible direct stock edit |
| RG-V01 Stock view | Group/size totals, search, archived remaining stock, zero counts, timezone boundary | Counts reconcile; company totals separated; stable scroll position |
| RG-X01 Excel scope | Current filter, zero/archive variants, several aliases, all movement types | Complete shop snapshot; no alias double counting or invented revenue |
| RG-X02 Excel data | Leading-zero/long codes, formula-like names, dates/timezone, totals | Literal identity/user text, correct numeric totals; workbook opens independently |
| RG-X03 Excel limits/failure | LARGE_HISTORY, concurrent sale, cancel/write error, viewer absent | Sheet splitting, consistent snapshot, bounded memory; safe local file fallback |
| RG-L01 Optional labels | Explicit 4-copy PDF, Full/Half codes, 3-piece restock count, zero quantity | Correct requested copies; never prints automatically in default flow |
| RG-L02 Label retry/reprint | PDF failure after product save, reprint, print cancel/no printer | No duplicated inventory; PDF fallback; no false completed-print claim |
| RG-BK01 Backup/restore | Valid, corrupt, truncated, incompatible, checksum/uniqueness failure | Validate first; restore all-or-nothing; current data remains usable on failure |
| RG-BK02 Safety/migration | Safety backup fails; every supported old-schema upgrade; WAL activity | No unsafe replacement/destructive fallback; IDs/ledger/aliases preserved |
| RG-U01 UI/accessibility | Small display, large fonts, keyboard, TalkBack, full button hit surface | Readable/tappable; brief feedback announced; no clipped mandatory fields |
| RG-U02 Lifecycle/launch | Cold vs warm start, rotation, lock, permission denial, camera occupied | Correct state recovery and resource release; no splash replay/data reset |
| RG-O01 Offline | First launch/reboot/all flows with no SIM/Wi-Fi/account/Play services | Every core feature works; merged manifest lacks network permissions |
| RG-F01 Local file safety | Cancel/save errors, repeated exports, failed/cancelled restore | No stock mutation; no misleading complete output or uncontrolled temp growth |

Run meaningful randomized sequences against the oracle with multiple fixed seeds. Include sequences with invalid attempts, race/retry cases and interruption checkpoints, not only valid happy paths. Log the seed and shortest reproducible sequence on failure. Verify actual on-device SQLite behaviour; pure mocks cannot certify transaction or migration correctness.

## 6. Smoothness and latency acceptance gates

Use a release-equivalent build with the same shrinker/compilation/profile settings as the comparison build. No attached debugger during acceptance measurements. Keep test seeding, compilation setup and artificial fixtures outside timed sections; do not exclude real navigation, SQL work or render time from the journey.

| Metric | Gate at CAPACITY |
|---|---|
| Cold launch to usable Home | ≤ 3 s, including branding; report p50/p95 over ≥ 20 cold launches |
| Tap Sell to focused/usable scanner | ≤ 2 s; report startup separately from barcode recognition |
| Clear readable label in already-focused scanner to visible committed green | p50 ≤ 300 ms; p95 ≤ 600 ms over ≥ 200 varied labels |
| Known decoded code to commit and visible green | p95 ≤ 100 ms |
| First-pass recognition of clean supported optical labels | ≥ 95%; log failures and timeouts separately |
| Search after 150 ms debounce | p95 ≤ 500 ms for ≥ 200 varied queries; also report whole typing-to-result delay |
| Return/restock commit | p95 ≤ 200 ms |
| Scrolling/navigation | ≥ 95% eligible frames meet actual device refresh deadline; no app frozen frame > 700 ms |
| Integrity | Zero negative stock, duplicate committed mutations, lost confirmed writes, or reconciliation mismatch |
| Stability | Zero app crashes/ANRs in prescribed journeys and soak |

Frame deadlines depend on device refresh rate; 16.7 ms is a 60 Hz example. Use frame deadline/overrun information where supported and report unavailable metrics rather than inventing them. CPU frame duration alone is not full GPU/display latency. Do not exclude janky frames to make the percentage pass.

### Repeatable smoothness journeys

1. Load Stock, scroll slowly through successive pages, fling, reverse direction, and repeat. Test slow scrolling explicitly: it can expose flickering that fast flings hide.
2. Expand/collapse size breakdowns while scrolling. Verify stable row keys, no blank jumps, duplicate rows, wrong item reuse or position resets.
3. Search common and rare brand/model/size terms, no-match terms, beginning/middle/end records, rapid query changes and long text. Ensure stale results cannot overwrite the latest query.
4. Alternate Home/Products/Stock, edit a product, use +/−, close keyboard and return. Required controls remain responsive with no delayed touch processing.
5. Complete scan → result → Scan next on mixed labels, including identical codes after deliberate clearance. Record camera restart/rearm latency; do not hide it behind the already-focused metric.
6. Sell/return/restock while an XLSX/backup/optional-label export is running. Measure mutation latency and frame timing, not just export completion.

Run at least five measured repetitions of each standard journey after a documented warm-up. Keep cold/warm and profile/no-profile results separate. Report per-run and aggregated p50/p95/p99, worst frame and sample counts. Keep compilation mode and actual refresh-rate/thermal conditions consistent. A single lucky trace is not a pass.

### Measuring scan-to-green accurately

Instrument test builds with low-overhead monotonic markers for frame eligibility/receipt, decode, lookup, transaction start, commit, result state and rendered success. Attribute decode vs database vs rendering time separately. A callback that sets state is not evidence the screen already displayed green.

For optical timing, use a repeatable external observation or synchronized capture method to establish when a readable label enters the already-focused view and when the display changes. Account for any recording overhead and timestamp uncertainty. If only frame-receipt timestamps are available, label that interval **frame received to result**, not **label presented to result**. Include glare, blur and poor lighting as separate diagnostic runs.

Injected decoder events are useful for mutation latency and correctness but cannot establish real camera recognition speed. Successful scans alone are insufficient: count all attempts, misses and timeouts. Never display green before persistence to achieve a faster benchmark.

## 7. Memory, database and storage tests

- Measure process PSS/RSS, managed/native heap where available, allocations/GC and resource counts at stable checkpoints. Compare the same journey at 1,000, 20,000 and 100,000 records, after startup warm-up.
- No OOM, cursor/frame/descriptor leak, or steadily growing retained resource count. Lists and searches must not hold a full 100,000-record result or all transaction objects in memory.
- Repeat the navigation/scanning/export cycle ≥ 100 times. At matching idle checkpoints, memory should approach a repeatable plateau; investigate monotonic retention after cache warm-up rather than judging a single peak. Establish device-specific peak budgets before release; report measured values instead of assuming a universal MB limit.
- Inspect query plans for barcode identity, variant search, sale/return eligibility and history paging. Confirm appropriate indexes and absence of accidental full-ledger work on every scan. Count query work outside timing overhead-heavy diagnostic sessions.
- Validate count/total queries across high alias density. Check both hot and cold records; cache-only lookup measurements do not establish all-item performance.
- Monitor database, WAL, exported/temp-file and free-storage size through repeated writes/exports. Checkpoints and cleanup must not produce prolonged UI blocking or delete active exports. Test low-space handling using a disposable emulator/test device configuration, never by filling a real user's phone.
- Large XLSX/backup generation must use bounded memory and a consistent snapshot while preserving ordinary sale responsiveness. Test cancellation and interrupted output. A safety pause, if necessary, must be explicit and measured; it cannot silently lose actions.
- Validate generated PDFs/XLSX using independent readers. For optional physical-label checks, an existing printer/sample print is enough; unavailable physical printing stays BLOCKED, while local PDF assertions can pass separately.

## 8. Eight-hour soak at 100,000 items

Start with CAPACITY and 1,000,000 coherent historical movements. Run **20,000 intentional mixed mutations over eight hours**, distributed throughout the dataset with realistic pauses. A recommended deterministic mix is 8,000 sales, 4,000 eligible returns, 5,000 restocks, 1,000 eligible reversals and 2,000 counted corrections. Generate operations with valid preconditions; record invalid attempts separately rather than quietly changing expected counts.

At least 2,000 of the sales must exercise the scanner state through replayed frame/decoder fixtures, plus ≥ 200 actual optical scans in controlled physical-device sessions. Be explicit that fixture replays do not mean 8,000 physical camera scans. Include some barcode linkage and ambiguity attempts separately; these are zero-stock-delta actions.

Every hour:

1. Reconcile all inventory/eligibility with the oracle and verify committed-operation counts.
2. Run a short stock scroll/search journey and sample scan/mutation latency.
3. Record memory, CPU/power/thermal state, storage/WAL/temp files, camera/torch state and frame counters.
4. Perform a local XLSX or backup export with concurrent mutations, plus a cancellation scenario on alternating checkpoints.

Include deliberate background/lock/resume intervals and rotation/process-recovery checkpoints at safe reproducible points. Complete a final backup, restart and restore into isolated test data, then verify IDs/codes and all balances.

Acceptance: all 20,000 intended mutation outcomes accounted for exactly once, matching oracle/ledger, zero crashes/ANRs/OOM, no unbounded resource growth, no persistent camera/torch outside scanning, and no sustained unexplained latency degradation. Physical thermal protection may lower speed; report it and verify safe fallback rather than treating a throttled run as an ideal-condition latency pass.

## 9. Battery, heating and thermal regression

Use the same physical device, brightness, ambient conditions, refresh rate, battery level range and charging state for comparable runs. Test on battery where possible; USB charging can distort energy/heat comparisons. Do not bypass OS thermal/battery protections or deliberately overheat a phone. Allow normal cooling between repeats and record it.

| Test | Duration / workload | Assertions |
|---|---|---|
| PW01 Background idle | 60 min after leaving app and locking | No app camera/torch, app-held wake lock, repeating refresh, sustained CPU loop or unexpected jobs |
| PW02 Manual use | 30 min browse/search/restock/return | Responsive UI, bounded work, energy/thermal data recorded |
| PW03 Realistic scanning | 30 min intermittent scans with pauses | Camera and torch released after success; idle pause around 30 s; repeat scans remain usable |
| PW04 Continuous scan stress | Up to 30 min, stop safely if required | Adaptive workload; no bypassed thermal restriction, duplicate sale or abandoned commit |
| PW05 Battery Saver / low memory | Controlled mode and signal tests | Reduced optional work/caches; reliable stock writes and manual fallback |
| PW06 Thermal response | Test-only injected moderate/severe/recovery states, plus observed real status where available | Work reduces; severe status releases camera/torch; state recovers without replayed sale |
| PW07 Whole working day | Eight-hour soak | Record consumption/thermal trend, sustained performance and idle-resource behaviour |

Injected thermal signals certify handler logic only, not real thermal performance. Missing thermal sensor/counter support must not crash the app. Different phones have different limits; do not set one fabricated maximum battery temperature or percent-per-hour promise.

Before release establish controlled baseline medians from ≥ 3 comparable runs on each available phone. Record energy/charge data where available, battery percentage as coarse corroboration, active camera/torch time and workload counts. If counters are unsupported, report the limitation and still test resource release.

For repeatable regression screening, investigate an increase exceeding **both 10% of the baseline and the observed baseline variation range** in energy per matched workload. This is an engineering investigation trigger, not a universal battery-health limit. Reproduce the change and explain or fix it before release. No meaningful baseline means the comparison is NOT RUN, not zero drain.

Accept background-idle energy only when consistent with the device's repeatable idle baseline and no unexplained app activity. Battery percentage alone cannot prove efficient power use or long-term battery health.

## 10. Failure injection and process recovery

Use debug/test-only fault controls around database commit and file generation. Never ship these controls or enable them in the shop build.

- Failure before transaction; failure after quantity update but before movement insert; failure after commit before UI result.
- Process termination at defined checkpoints: recover and check durable outcome. Android force-stop/background/resume scenarios differ; record which was used. Do not use coroutine cancellation alone as evidence of process-death safety.
- Duplicate and conflicting operation IDs; rapid repeated UI touches; stale decoder callbacks after navigation.
- Return races and correction/restock concurrent with sale; correct SQL outcomes, no unsafe UI-only validation.
- Barcode collision discovered after prior valid linkage: mark ambiguity, preserve history and existing counts.
- Interrupted/corrupt backup, incompatible migration, failed safety backup, failed PDF/XLSX writes and cancelled system dialogs.
- Scanner permission revoked, camera unavailable, screen locked, no printer/viewer, airplane mode from fresh installation.

Every failure test must end with stock/ledger reconciliation and a resource-release check. Preserve entered data and show the short appropriate message, not a technical dump. Never translate an unresolved commit into an automatic retry with a new ID.

## 11. Benchmark regression comparison

Compare baseline and candidate on the **same device, fixture digest, build configuration, compilation state and workload**. Alternate run order where practical to reduce warming/thermal bias. Record at least five runs for standard journeys and three controlled runs for power workloads. Keep raw samples and failed runs.

- Any stock-integrity failure, crash, ANR, incorrect code mapping, failed restore/migration, or offline violation blocks release immediately.
- Any established absolute acceptance target failure blocks release until fixed or explicitly reviewed with measured device limitations.
- A reproducible latency increase exceeding both **10% and the baseline run-to-run variation** needs investigation even when still under the absolute target. Do not reject on a noise-sized percentile difference without checking samples.
- Any new app frozen frame > 700 ms, missing state after lifecycle change, or persistent idle resource activation requires investigation.
- Memory increases require retained-object/resource analysis and a device-budget check; one transient allocation spike is not automatically a leak. Do not change the budget to hide a regression.
- Golden UI screenshots may check layout, but do not prove frame smoothness, tap response, barcode recognition or power efficiency.

## 12. Execution instructions for the coding agent

Implement the fixtures, selectors and assertions alongside the app using reusable helpers. Add tests that prove failure behaviour and outcomes; do not write redundant tests that mirror private function structure. Keep instrumentation/trace overhead out of acceptance timing where possible and document it.

Discover actual modules/variants/tasks first:

```bash
./gradlew tasks --all
adb devices -l
```

Then document repository-specific commands for unit/database/UI smoke, full regression, fixture seeding and Macrobenchmark. Do not claim the following tooling examples are a runnable suite until the application/package/tasks exist. For diagnostics, substitute the selected test package and select the intended device when more than one is connected:

```bash
adb shell dumpsys meminfo <test-package>
adb shell dumpsys gfxinfo <test-package> framestats
adb shell dumpsys batterystats <test-package>
```

Availability/output varies by OS; use diagnostics as evidence with supported metrics, not a universal proof of performance. Avoid global reset/clear/uninstall commands against real shop data. Capture bounded traces and logs; an unlimited eight-hour raw trace can itself exhaust storage or change performance.

Store reproducible test/benchmark reports locally with build ID, timestamp and device ID. No uploading to paid services. Keep test-generated labels and exports clearly marked as synthetic and avoid customer information in logs.

## 13. Evidence and result template

For each suite record:

| Field | Required entry |
|---|---|
| Build | Commit/local revision, APK SHA-256, build variant, dependency/Android API configuration |
| Device | Model, OS, RAM, refresh rate, emulator/physical, available counters |
| Dataset | Fixture version/seed/digest, product/variant/alias/movement counts, initial balances |
| Conditions | Ambient/brightness/battery/charging/Battery Saver/thermal state; compilation/warm-up settings |
| Results | PASS / FAIL / BLOCKED / NOT RUN for each case; assertion evidence, not only screenshots |
| Timing | Sample count, definition, p50/p95/p99, worst, recognition attempts/misses/timeouts |
| Resources | Memory/CPU/storage trends, active/idle camera/torch, measured power and limitations |
| Correctness | Oracle/ledger reconciliation, operation outcomes, migration/restore/export validation |
| Artifacts | JUnit reports, bounded traces, workbook/PDF validation, raw samples and reproduction seed |
| Comparison | Baseline ID, change, variation, unresolved failures and justified reruns |

Suggested local report directory: `test-results/<build-id>/<device-id>/`. Include a brief `summary.md` and machine-readable results. Seeded databases and large traces should be reproducible; retain final evidence needed for comparison without unlimited duplicate outputs.

Initial summary:

| Suite | Status |
|---|---|
| SMALL smoke | NOT RUN |
| CAPACITY smoke at 100,000 items | NOT RUN |
| Full correctness/UI regression | NOT RUN |
| Release smoothness / scan timing | NOT RUN |
| Excel/PDF, backup and migration | NOT RUN |
| Eight-hour soak | NOT RUN |
| Battery / thermal / idle resource checks | NOT RUN |

## 14. Completion gate

The app is ready for shop use only when required functional/offline/recovery cases pass, inventories reconcile, the 100,000-record workload is actually loaded and exercised, device measurements meet or transparently resolve acceptance gates, and evidence is retained. Missing physical-device/printing/power coverage remains visible. Never report production-ready, zero-delay, zero-heating or free hardware based only on this document or mocked tests.

## 15. Official references

- [Android testing](https://developer.android.com/training/testing)
- [Room database testing](https://developer.android.com/training/data-storage/room/testing-db)
- [Macrobenchmark setup](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
- [Macrobenchmark metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)
- [System tracing](https://developer.android.com/topic/performance/tracing)
- [Android Power Profiler and device support](https://developer.android.com/studio/profile/power-profiler)
- [Android Thermal API](https://developer.android.com/games/optimize/adpf/thermal)

Use currently compatible APIs when implementing the plan; do not rely on a stale copied dependency version or suppress benchmark environment warnings to claim a physical-device pass.
