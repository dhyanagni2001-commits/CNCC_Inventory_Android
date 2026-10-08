# Coding Agent Instructions — C Nanjappa Cloth Center

## Mission and source of truth

Build a complete, reliable native Android inventory app for **C Nanjappa Cloth Center**, used by shop staff who are not comfortable with technology. Prioritize straightforward taps, correct inventory, fast camera scanning, completely offline operation, and sensible battery/heat use.

Read **android_inventory_requirements.md** in the project root before implementing anything. It contains the detailed workflows, acceptance targets, and test matrix. This file guides implementation; it does not replace that specification. Keep both files together. If the requirements file is missing, ask for it rather than claiming to have implemented its full scope.

Follow the user's latest explicit instructions. For older passages or mockups that conflict, apply the latest decisions below. If a remaining conflict affects data integrity or user-visible behaviour, identify it clearly and ask one focused question; continue independent work meanwhile. Do not silently invent a feature or weaken an invariant.

### Latest decisions that override earlier examples

| Topic | Implement this behaviour |
|---|---|
| Shop name | C Nanjappa Cloth Center, shown briefly on cold launch before Home |
| Default product save | **Save product**, with existing manufacturer codes; no automatic PDF/printing |
| New labels | Optional Code 128/QR label generation and PDF export; no printer purchase required |
| Home Stock overview / Inventory Analytics | Generate and save the complete **.xlsx workbook** |
| Stock bottom tab | Preserve searchable on-screen quantities and size/sleeve breakdowns |
| Low-stock / out-of-stock widgets | None: no cards, badges, thresholds, or dedicated filters; show ordinary zero counts |
| Sleeves | Required **Full** or **Half**, with no silent default and no Not applicable |
| Colours | **White**, then **Cream**, first; other saved colours and **+ More** for custom entry |
| Sizes | S, M, L, XL, XXL, XXXL; every integer 36–50; saved/custom sizes such as 32 |
| Companies | Any company, including Ramraj, Udyam and Puma; no hardcoded brand logic |
| Barcode collisions | Explicit ambiguity and variant choice; never overwrite or choose the first match |
| Offline | Everything core works from first launch, without accounts, internet or scanner downloads |
| UI images | Visual references only; do not embed or modify them in requirements without a request |

Older label tests that describe automatic PDFs apply only when the optional Generate labels flow is selected. Older screens that make Stock overview open stock quantities do not override the Excel-export decision. If several visible codes are verified aliases of one variant, accept at most one operation; distinct or unresolved codes require aiming at one label or explicit selection.

## Engineering judgement and boundaries

- Do not agree with every proposed design automatically. Explain a simpler or safer alternative when evidence supports it.
- Check important decisions twice: first against requirements/platform constraints, then against a concrete failure case or targeted verification. Do not run the same test twice merely to satisfy this rule.
- Reuse existing project components before adding abstractions, libraries, screens, or files. Read the repository and its instructions first.
- Write small, clear functions and purposeful comments explaining non-obvious decisions. Avoid dead code, speculative infrastructure, duplicated logic, and placeholder implementations.
- Do not build billing, payments/refunds, tax, customer accounts, cloud sync, employee roles, AI recognition, or multi-shop features unless explicitly requested.
- One device is the authoritative inventory in version one. Do not imply that independent offline devices synchronize.
- Complete the authorized scope through implementation and relevant validation. Maintain a requirements-to-implementation/test checklist; do not present a partial prototype as the finished app.
- Do not push code, open/merge pull requests, publish a store listing, deploy services, purchase anything, or send messages without explicit authorization. Keep work local unless instructed otherwise.
- Do not erase existing data, run destructive migrations, replace user files silently, or expose signing keys/secrets. Keep secrets outside source control.

## Stack and project structure

Use the specification's baseline: **Kotlin**, **Jetpack Compose/Material**, **CameraX**, **Room/SQLite**, and a **bundled ZXing core decoder**. Use Android file/PDF/print facilities and a verified free local XLSX writer. Do not require the deprecated external Barcode Scanner app.

Inspect the existing build first. Pin mutually compatible supported dependencies and document minimum Android API and dependency licences. Verify any new library's Android compatibility, offline behaviour, size, and memory use before adopting it. If the baseline decoder fails actual device benchmarks, present a measured free offline alternative rather than silently claiming it meets targets.

Prefer a single app module and a simple structure separating UI/state, domain rules, data access, scanner integration, and file exports. Add modules/frameworks only when the repository already uses them or a concrete need justifies them. Keep stock mutation logic in one shared domain/data path used by camera and manual workflows.

Runtime must require no API key, paid SDK, cloud free tier, online activation, subscription, Microsoft account, or Google account. Developer dependency downloads are separate from runtime offline guarantees.

## Offline and permission contract

- Bundle decoder code, fonts, icons, and required assets inside the APK. No first-use download, remote configuration, online product lookup, or QR URL fetch.
- Inspect the final merged release manifest: no **INTERNET** or **ACCESS_NETWORK_STATE** permissions, including those introduced by dependencies.
- Request camera permission when scanning needs it; retain manual product selection after denial. Use the system file picker rather than broad storage access.
- Do not add location, Bluetooth discovery, background camera, telemetry uploads, ads, permanent foreground services, or unrelated sensors.
- External viewers, sharing applications and printer services are optional. Local PDF/XLSX/backup export must work when those apps/services are unavailable.
- Test a clean installation and first launch in airplane mode, without usable Play services, before declaring offline support complete.

## Identity and existing barcode logic

A variant is the normalized combination of **company + product name + model/sub-name + colour + size + sleeve**. Required: name, one model/sub-name field, size, Full/Half, and nonnegative integer opening quantity. Company and colour remain optional for unknown/unbranded items. Preserve readable display values.

- Different companies, sizes, colours and sleeves keep separate quantities. Treat L/Large, XXL/2XL/Double XL, and XXXL/3XL/Triple XL as aliases of their respective sizes; never convert numeric 40 to alphabetic L.
- Stock belongs to a variant, not its barcode. A variant may have multiple manufacturer-code aliases. Linking a code never adds inventory.
- Detect EAN-13, EAN-8, UPC-A, UPC-E, Code 128, Code 39, QR and Data Matrix without requiring a brand/format switch.
- Store format and exact decoded identity, with narrowly defined tested UPC/EAN equivalence normalization. Preserve leading zeroes/raw values. Never use prefix guessing, numeric conversion or destructive text normalization.
- First delivery: scan one label, enter/select exact variant, enter quantity received, Save product once. Identical pieces with a shared code need only one linking scan.
- Later delivery: scan known code, display variant, enter quantity, Add stock once. Unknown codes offer Link to existing product or Add new product.
- Different codes for identical variants can all link to the same quantity. Each previously unseen code must be registered before it can resolve automatically; manual Sell 1 remains available.
- If one lookup key belongs to several variants or brands, record candidate mappings explicitly and disable automatic mutation for that code. Require the exact variant choice. No silent reassignment.
- Product-specific QR payloads may become local aliases after deliberate linking. Shared promotional/payment/homepage QR codes do not prove product identity. Never open a QR URL automatically.
- This version does not serialize physical garments. Do not promise individual-piece authenticity or traceability from alias mapping.

## Inventory integrity: enforce in the data layer

1. Available stock never becomes negative. Validate integer ranges and overflow.
2. Stock update, sale/return records, operation outcome and movement history commit atomically.
3. Use conditional sale updates that require sufficient quantity and check affected rows inside the transaction. UI checks alone are insufficient.
4. Every intentional mutation has a unique durable operation ID. Duplicate frames/taps/retries return the original outcome rather than repeating a mutation. An ID reused with different input must not mutate different stock.
5. Retain recoverable intent before execution; after interruption, resolve its database outcome before allowing retry. A timeout is not proof that a commit failed.
6. Green text, success haptics and remaining quantity reflect a confirmed commit only. No speculative success or delayed persistence for battery savings.
7. Returns reference the same variant's eligible recorded sale. Allocate to the newest eligible sale by default, using a deterministic tie-break, and revalidate within the transaction. Specific-sale choice stays under More options.
8. Good returns add stock; damaged returns consume eligibility but have zero sellable-stock delta. Returns plus reversals cannot exceed the original sale quantity.
9. Undo creates a linked reversal once; after partial return, reverse only the remainder. Never delete the original history.
10. Stock equals opening movement plus all recorded deltas. Linking/reprinting/exporting cannot change stock. Keep reconciliation outside the interactive scan path.
11. Correct stock requires a reason and confirmation of current/new quantity. Restock is a distinct positive movement. Do not expose silent quantity editing.
12. Enforce normalized variant/code uniqueness and explicit ambiguous mappings in the database. Keep historical descriptions as transaction snapshots.
13. Archive rather than erase history. Block sale/restock of archived items; preserve linked returns and display any resulting stock.
14. Use tested nondestructive migrations. Preserve IDs, code mappings, stock and history across updates and restores.

Model only necessary entities: variants, code identities/aliases and ambiguity candidates, sales, returns, movements, recoverable operations, and modest settings. Avoid duplicating authoritative quantity across uncoordinated stores.

## Simple UI and scan state

- Home: large Sell, Return, Add Product, Stock overview actions. Bottom tabs: Home, Products, Stock. History and Backup stay in a small menu.
- Cold launch shows exact shop name briefly then automatically opens Home; no long artificial delay or splash replay on background resume.
- Use large labelled controls with at least 48 dp touch targets, readable text, restrained blue/neutral styling and green success. Entire button surfaces are tappable.
- White/Cream appear first; + More allows saving a typed colour for future taps. Cancelled forms do not pollute saved options.
- Saved companies/models, Full/Half, sizes and draft quantity are tap-based. Selecting a field or tapping draft +/− does not itself mutate stock.
- Preserve form data and scroll position on rotation/recreation. Handle large text, small screens, keyboards and accessibility announcements.
- Use short inline errors with one useful next action. Examples: Choose Full or Half; Choose a size; Product not found; No pieces available; Checking your last action… . Never display stack traces, technical codes, giant repeated dialogs, or a cause that has not been detected.

Use explicit scanner states such as **preparing → scanning → resolving/committing → result → rearming**. Return/link/restock scanning identifies an item; it does not automatically sell it. Only a known, unambiguous Sell scan commits one-piece deduction automatically.

Lock acceptance immediately on a valid Sell scan. Pause/release camera at success and show **1 item reduced**, product details and remaining count with Undo, Scan next, Done. Scan next deliberately rearms; require the prior label to leave the frame before accepting it again. No timed auto-resume or repeated sale from a held label. Ignore stale callbacks after navigation; rotation/process recreation must not repeat the operation.

## Speed, capacity, battery and heat

Never perform decoding, database operations, XLSX/PDF/backup work, or heavy computation on the main thread. Use bounded concurrency, backpressure, correct cancellation and lifecycle-aware state. Always close camera frames, including error/cancellation paths.

- Page product/variant/history queries, use stable list keys and indexed code/return/search lookups. Load expanded size data only when needed. Do not load the whole catalog or ledger into RAM.
- Keep the decoded-code-to-sale path minimal. No analytics aggregation, export, image loading or unrelated refresh before green feedback. No arbitrary timers or animations gating results.
- Reuse bounded decode resources and buffers. Start with modest resolution around 720p and benchmark useful 10–15 fps analysis; adjust based on actual label recognition, latency, power and thermal data. Preview cadence is separate.
- Release camera and torch on success, navigation, screen lock and backgrounding. Torch defaults off. Pause unused scanner around 30 seconds and offer Tap to scan again. Preserve durable stock work while cancelling unrelated tasks.
- No recurring background refresh, busy polling, constant redraw, unnecessary wake locks, forced brightness/refresh rate, or always-on screen.
- Respect Battery Saver, low memory, and supported thermal callbacks. Reduce optional work and caches; pause camera/torch at severe thermal stress and offer manual search. Do not bypass OS protections or abandon transaction recovery.
- Bound temporary files/images/queues. Generate labels only on request. Exports must not hold long write locks or stall sales; test the snapshot strategy under concurrent activity.

### Targets to measure, never claim without evidence

| Measure | Release target |
|---|---|
| Cold launch to usable Home | ≤ 3 seconds, including brief branding |
| Scanner ready | ≤ 2 seconds |
| Focused readable label to green committed result | p50 ≤ 300 ms; p95 ≤ 600 ms |
| Valid known-code decode to commit and green | p95 ≤ 100 ms |
| Stock search | p95 ≤ 500 ms after 150 ms debounce |
| Return/restock commit | p95 ≤ 200 ms |
| Scrolling/navigation | ≥ 95% frames within device refresh deadline; no frozen frame > 700 ms |
| Capacity | 20,000 products, up to 100,000 variants, 1,000,000 movements |
| Sustained use | 8 hours / 20,000 mixed operations; no crash, ANR, duplicate mutation or unbounded memory growth |

Measure release builds on named low-cost and midrange physical phones, with at least 200 varied labels and recognition failures/timeouts included. Record device, Android version, dataset, conditions and workload. Poor labels, startup, thermal throttling and camera restart latency must be reported separately. Test mixed brands/formats rather than disabling required decoders to improve results.

Measure power/thermal trends with controlled brightness, ambient temperature, charging state and workloads, including a background-idle baseline. Set justified device-specific budgets. Do not promise literal zero delay, zero drain, no battery aging, or smoothness on all phones. If targets fail, profile the bottleneck and report unresolved limits honestly.

## Exports and recovery

### Excel: Home Stock overview

Generate a real offline **.xlsx**, complete shop inventory regardless of current screen filters. Include Summary, Inventory, Product totals, Size breakdown, Movements, and Barcodes sheets as specified. Include zero/archived inventory, timestamp/timezone and movement date coverage; do not invent revenue from absent payment data.

Use one consistent snapshot, bounded batches and a verified free Android-compatible writer. Avoid large in-memory workbooks and long write locks. Preserve barcode IDs as text, quantities as numbers and user input as literal text rather than formulas. Do not double-count stock through alias joins. Split movement history when exceeding Excel's sheet row limit; never silently omit rows. Use readable headings, filters, frozen rows, and correct totals/cached formulas or calculated snapshot totals.

Validate the completed temporary file before saving via local file picker. Report Excel saved only after successful output. Open/Share are optional; generation must work without a viewer/account. Cancellation or failure changes no stock and leaves no misleading partial file. Excel is an analysis export, not the restorable backup.

### Optional barcode/QR labels

Create stable internal variant identities; default printable code is Code 128, QR optional. Existing manufacturer labels remain the primary no-cost flow.

When Generate labels is explicitly chosen, produce exactly the requested count: four identical garments get four copies of one variant code; restocking three defaults to three new labels, not the entire stock. Zero opening quantity creates no automatic label PDF. Reprinting creates neither new stock nor new identity.

Render crisp black-on-white labels with quiet zones and readable model/colour/size/sleeve. Honour calibrated media dimensions/margins; reuse code patterns and render pages in bounded batches. Verify exported/printed labels decode correctly. Save PDF works offline. Print uses Android's printer selection and an available compatible service; no universal Bluetooth/USB/nearby-printer claim. Distinguish ready, saved, submitted and reported completion. A PDF failure after product save must never re-add stock.

### Backup, restore and migrations

Back up all identity/alias/ambiguity, stock/history, operation and schema/catalog data from a consistent snapshot to a user-selected location outside app-private storage. Preserve format/version/checksum and stable IDs. Remind users to keep a copy outside the phone.

Validate backups before replacement; show date/item count and confirm replacement. Make a safety backup first; if it fails, do not replace current data. Restore atomically or leave the current database usable. Reject incompatible/corrupt data with short feedback. Never copy only a live SQLite main file while ignoring WAL state. Use tested migrations; test old labels and quantities after upgrade/restore. Do not merge independent inventories or implement Excel import by default.

## Delivery workflow and verification

1. Inspect repository instructions, current implementation/build, and the complete requirements. Create a short plan and a scope/test checklist. Resolve latest-decision conflicts above without rebuilding obsolete flows.
2. Establish a reproducible Android build and a small UI foundation. Implement variant/code identity and transactional mutations with meaningful database tests first.
3. Deliver working vertical flows: first-time barcode linking and quantity entry, restock, manual sell/return, then real camera sell with safe recovery and deliberate rearming.
4. Complete Stock, Excel, optional labels, backup/restore, lifecycle recovery, short errors and accessible premium UI. Keep ordinary interactions simple while correctness resides in the data layer.
5. Run applicable unit/database/UI tests, static/build checks and a release build. Discover actual Gradle tasks rather than inventing command names. Record commands and outcomes.
6. Execute required offline, camera, migration, restore, mixed-code, performance and power checks on supported devices. Profile and fix regressions rather than adding delays or weakening safety.
7. Complete the requirements/test checklist. Report completed, failed and blocked items distinctly, with evidence and remaining dependencies. Provide source, installable APK, licence notices and short usage/build/backup instructions.

Tests must cover invariants and user-visible failure modes, not mirror implementation details. Reuse helpers/fixtures. Include concurrency on last-piece sale and return limits, repeated operation IDs/taps/frames, unknown/ambiguous codes across brands, alias linking without stock changes, size/colour normalization, damaged returns and partial undo, process death before/after commit, permissions and lifecycle, low storage and migration failures, XLSX/PDF correctness, restore safety, and true first-launch offline use. Include randomized mutation/reconciliation tests where useful.

If an emulator, physical device, Android SDK, printer or signing key is unavailable, finish independent work and document precisely what could not be verified. Do not mark camera quality, battery performance, production signing, or release acceptance as passed without the corresponding evidence. Do not generate a production signing identity or publish anything silently; protect any user-provided key.

Automated tests and benchmarks are required by this project, but no result is implied merely because a test was written. Never fabricate screenshots of a running app, real-device metrics, test passes, user adoption or production readiness.

## Final handoff

Describe what works, how it was checked, APK/build location, and any material limits. Use concise plain language. The deliverable is complete only when required functionality and available checks are actually finished; unresolved device/release checks must remain visible. Leave real shop data untouched.

This file is named **agent.md** as requested. Tools that discover only **AGENTS.md** must be explicitly pointed to this file, or the user can rename it to AGENTS.md in the repository root. Do not assume every coding tool loads a lowercase singular filename automatically.
