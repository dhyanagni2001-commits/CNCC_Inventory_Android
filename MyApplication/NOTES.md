# C Nanjappa Cloth Center — Inventory app

## Build
```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug      # installable: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # unsigned: app/build/outputs/apk/release/app-release-unsigned.apk
./gradlew :app:assemblePreview    # optimised like release, debug-signed: for smoothness testing only
```
Judge smoothness only on `preview`/release builds. Debug Compose builds stutter. Run the emulator with `-gpu host`, because the default software renderer stutters regardless of the app.
Release APK needs signing with the shop's own key (not generated here). minSdk 26, target/compile 37.

## Structure (`app/src/main/java/com/cnanjappa/inventory`)
- `domain/` — size/colour/identity normalisation, barcode keys (UPC-A/UPC-E/EAN-13 equivalence only), internal label codes.
- `data/` — Room entities, DAO, `InventoryRepository` (the single mutation path: op IDs, conditional updates, ledger). DB triggers also block negative stock, over-returns and ledger edits.
- `scan/` — CameraX + bundled ZXing analyser (8 formats, scan-box crop, reused buffer, multi-label check).
- `export/` — streaming XLSX writer + report, label PDF (vector Code 128/QR), backup/restore.
- `ui/` — Compose screens.

## Dependencies and licences (all free, offline at runtime)
Jetpack Compose/Material3, Navigation, Lifecycle, Paging, Room 2.8.5, androidx.sqlite bundled driver, CameraX 1.6.2, core-splashscreen — Apache 2.0.
ZXing core 3.5.4 — Apache 2.0. Inter font — SIL OFL 1.1 (`INTER_FONT_LICENSE.txt`). XLSX writer is in-house (no third-party library).
Release merged manifest permissions: CAMERA only (INTERNET/ACCESS_NETWORK_STATE explicitly removed).

## Design notes
- Excel and backup read from one DEFERRED read transaction on a WAL reader connection: consistent snapshot without blocking sales.
- Backup file `.cncbak` = zip(manifest.json with SHA-256 + snapshot SQLite db). Restore validates checksum, schema version, integrity_check and ledger reconciliation, makes a safety copy (app storage, last 3 kept), then swaps the file atomically.
- Search uses SQL `LIKE` over an indexed table (not FTS); measured at 100k variants on the emulator: Stock search p95 299 ms, Products 104 ms (2026-10-08), so FTS is not needed yet. Switch to FTS4 if a real phone exceeds 500 ms.

## Not done / not verified yet
- Tests: 26 JVM + 28 instrumented, all passing on emulator API 37 (2026-10-08). 10k and 100k stress runs pass (scripts/stress.sh). Still missing: full restore swap-in test, scanner tests with real labels.
- Schema v2 adds `variants.notes` (AutoMigration 1→2, covered by MigrationTest). Every future bump needs a migration + test; schemas are in `app/schemas`.
- `kotlinx-serialization-core` is pinned to 1.8.1 via a constraint because room-testing needs it.
- Not run on any device or emulator: camera recognition, latency, battery/thermal, offline first launch and printing are all **unverified**.
- Not built: "Print several products" (multi-product label PDF), custom label dimensions (3 presets only), "Print completed" status (only "Print submitted").
