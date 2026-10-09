# Release checklist — results (9 Oct 2026)

App version for every check: **1.0 (versionCode 1)**, commit `0c0299d` plus a lint fix.

**Where each check was done:**
- **B**: build Mac, clean checkout
- **E**: emulator, Pixel-type phone with Android API 37, `.debug` or `preview` build
- **P**: Samsung Galaxy M32 5G, Android 13, `preview` build (optimised, debug-signed)
- **C**: code or config review

**Results:** Pass · Fail · N/A · **Not tested** (not checked yet; neither a pass nor a fail).

The app records piece counts only: no prices, money, imports, networking or accounts. Items about those are N/A.

## Blockers before release
1. **Release APK is unsigned.** It needs the shop's own key (1.4, 1.14).
2. **R8 mapping files are not archived.** Crashes in the release build can't be decoded (1.13, 19.10).
3. **No automated smoke test on the optimised build.** Today's scanner bug appeared only in optimised builds (18.6).
4. **The app doesn't show its version** (19.2).
5. **Label PDF and printed labels are untested** (6.12–6.14). Real-label scanning on the phone has not been reported yet (6.5).

## 1. Build and release
| # | Check | Result | Dev | Evidence |
|---|---|---|---|---|
|1.1|Clean build|Pass|B|`clean assembleRelease assemblePreview` succeeds|
|1.2|Unit/integration tests, Lint|Pass|B,E|26 unit + 30 device tests pass. Lint showed 1 error (from today's camera change), now fixed: 0 errors|
|1.3|Warnings resolved|Fail|B|14 Lint warnings: 8 newer dependencies, 5 `AutoboxingStateCreation`, 2 ChromeOS ABI|
|1.4|Release signed correctly|Fail|B|`app-release-unsigned.apk`; no shop key set up yet|
|1.5|Keys outside repo|Pass|C|No keystore or password in git|
|1.6|versionCode/versionName|Pass|C|1 / 1.0 for the first release; must increase on every update|
|1.7|App ID, name, icon, splash|Pass|E,P|`com.cnanjappa.inventory`, "C Nanjappa Cloth Center", CN icon and splash|
|1.8|Min/target SDK intentional|Pass|C|min 26 (Android 8), target 37|
|1.9|Release not debuggable|Pass|B|aapt badging: no `debuggable` flag|
|1.10|Test screens/sample data removed|Pass|C|Demo and stress data exist only in `androidTest`|
|1.11|Production config|Pass|C|No config needed; INTERNET permission removed|
|1.12|R8 doesn't break scan/DB/exports|Fail|E,P|Scanning broke under R8 and is now fixed (keep rules); the scanner test passes on R8 code. Database, Excel and backup are Not tested on the R8 build|
|1.13|Mapping files retained|Fail|B|Only in `build/`, which `clean` deletes|
|1.14|Signed release tested on devices|Not tested|—|Needs 1.4|

## 2. Installation and upgrades
| # | Check | Result | Dev | Evidence |
|---|---|---|---|---|
|2.1|Fresh install opens|Pass|E|After `pm clear`|
|2.2|First launch offline|Pass|C|App has no network permission|
|2.3|Upgrade from every DB version|Pass|E|`MigrationTest` v1→v2 (only 2 versions so far)|
|2.4|Settings preserved|N/A|C|No user settings|
|2.5|Failed migration doesn't wipe DB|Pass|C|No destructive fallback|
|2.6|Insufficient storage error|Not tested|—||
|2.7|Reinstall/data recovery understood|Pass|C|`allowBackup=false`: uninstalling deletes the data; recovery is through the app's backup file|
|2.8|Backup restore into current version|Pass|E|`BackupTest` round trip|

## 3. Core functionality
| # | Check | Result | Dev | Evidence |
|---|---|---|---|---|
|3.1|Every feature end to end|Not tested|—|Printing is unverified|
|3.2|Create/view/edit/delete|Pass|E|RepositoryTest. "Delete" is archive|
|3.3|Required fields validated|Pass|B|`missingRequiredFieldsReportedInOrder`|
|3.4|Blank/whitespace rejected|Pass|B|Validation tests|
|3.5|Invalid/negative/huge numbers|Pass|B,E|`quantityParsing`, `invalidQuantitiesRejected`|
|3.6|Duplicates intentional|Pass|E|`duplicateIdentityIsRejected`|
|3.7|Long text/Unicode/special chars|Pass|B|Length and control-character limits; XML escaping|
|3.8|Repeated taps no duplicates|Pass|E|`replayedOperationIdNeverAppliesTwice`|
|3.9|Cancel leaves data consistent|Pass|E|`createWithTakenLabelRollsBackEverything`|
|3.10|Destructive actions confirm/undo|Pass|C|Undo after a sale; confirm dialogs for undo sale and restore|
|3.11|Failures never show success|Pass|C|UI shows success only on an OK result code|

## 4. Inventory and products
| # | Check | Result | Dev | Evidence |
|---|---|---|---|---|
|4.1|All brands/categories|Pass|C|Free-text company and name|
|4.2|Variants distinct|Pass|B|Identity and size tests (letter, number, age, custom)|
|4.3|Barcode uniqueness rules|Pass|E|`linkingNeverStealsACodeAndSharingNeedsChoice`|
|4.4|Leading zeros preserved|Pass|B|`leadingZeroesAndCaseArePreserved`|
|4.5|Initial stock correct|Pass|E|RepositoryTest|
|4.6|Stock added to correct variant|Pass|E|RepositoryTest|
|4.7|Adjustment needs reason|Pass|E|`correctionNeedsReasonAndFreshCount`|
|4.8|Zero/low stock shown correctly|Not tested|—||
|4.9|Negative stock prevented|Pass|E|Database triggers, tested against raw SQL|
|4.10|Edits don't rewrite history|Pass|C,E|Sales store a description snapshot; notes test|
|4.11|Delete keeps past sales|Pass|E|Archive only; archived items blocked from sale|
|4.12|Totals match records|Pass|E|Stress test at 10k/100k: 0 mismatches|

## 5. Sales, returns, money
| # | Check | Result | Dev | Evidence |
|---|---|---|---|---|
|5.1|Single/multi-item sales|Pass|E|1 piece per scan by design; multi-item N/A|
|5.2|Stock checked at commit|Pass|E|`cannotSellBelowZero…`|
|5.3|Sale + deduction atomic|Pass|E|Single transaction|
|5.4|No oversell under races|Pass|E|`concurrentSellsNeverOversell`; 50 stress races|
|5.5–5.7|Discounts, tax, money, rounding|N/A|C|No prices|
|5.8|Cancelled sale no deduction|Pass|E|Undo restores once|
|5.9|No partial transactions|Pass|E|Transactions and triggers|
|5.10|Full/partial returns|Pass|E|RepositoryTest|
|5.11|Returns ≤ sold|Pass|E|`returnsNeedAMatchingSaleAndCannotExceedIt`|
|5.12|Returned to correct variant|Pass|E|RepositoryTest|
|5.13|Refund totals|N/A|C|No money|
|5.14|Damaged return policy|Pass|E|`damagedReturnUsesUpSaleButKeepsStock`|
|5.15|No duplicate submissions|Pass|E|Operation IDs|
|5.16|History auditable|Pass|E|History rows can't be updated or deleted (triggers)|

## 6. Scanning and labels
| # | Check | Result | Dev | Evidence |
|---|---|---|---|---|
|6.1|Known barcode → correct product|Pass|E|Resolve tests (UPC/EAN, internal codes)|
|6.2|Unknown barcode → next action|Pass|C|"Product not found" offers Link, Add, Find|
|6.3|Multiple barcodes predictable|Pass|C|Accepted only if all visible codes belong to 1 item; not device-tested|
|6.4|No repeat-adding the same item|Pass|C|The label must leave the frame before it counts again|
|6.5|Small/damaged/poorly lit labels|Not tested|P|Only recorded frames (ScanEngineTest); no live real-label scan reported yet|
|6.6|Permission denied fallback|Pass|C|"Allow camera", "Open settings" and "Find product" shown|
|6.7|Permission revoked mid-use|Not tested|—||
|6.8|No camera → manual entry|Pass|E|Tested today: front-only and no-camera emulators|
|6.9|Camera released on leaving|Pass|E|Unbinds when the scanner closes; camera closed in background (stress run)|
|6.10|Works after backgrounding|Pass|E|Tested today|
|6.11|Scanning in minified build|Pass|E,P|Fixed today; camera opens on P; ScanEngineTest passes on R8 code|
|6.12|Labels encode intended ID|Not tested|—||
|6.13|Printed labels scan|Not tested|—||
|6.14|Label text not clipped|Not tested|—||

## 7. Search
| # | Check | Result | Dev | Evidence |
|---|---|---|---|---|
|7.1|Every advertised field|Pass|B,E|Wildcard tests; notes searchable|
|7.2|Case/extra spaces|Pass|B|Normalisation tests|
|7.3|Exact barcode search|Not tested|—||
|7.4–7.5|Combined/cleared filters|N/A|C|Text search only|
|7.6|Sorting|Pass|B|`sortOrderIsLettersNumbersAgesCustom`|
|7.7|Empty-result message|Not tested|—||
|7.8|No stale results on fast typing|Not tested|—||
|7.9|List updates after sale/edit|Not tested|—||
|7.10|Paging for large results|Not tested|—||
|7.11|Responsive at max size|Pass|E|100k products: slowest search 299 ms for 1 in 20 (95th percentile)|

## 8. Database
| # | Check | Result | Dev | Evidence |
|---|---|---|---|---|
|8.1|Constraints block bad records|Pass|E|`databaseTriggersGuardInvariantsEvenAgainstRawSql`|
|8.2|Related writes in transactions|Pass|E|Repository tests|
|8.3|Foreign keys verified|Not tested|—||
|8.4|Indexes|Not tested|—|Latency fine at 100k|
|8.5|DB off main thread|Pass|C|Room suspend DAOs (Room blocks main-thread queries)|
|8.6|Concurrent writes consistent|Pass|E|Race tests|
|8.7|Exceptions no partial update|Pass|E|Rollback test|
|8.8|Process kill during write|Not tested|—|SQLite transactions should protect this; no test|
|8.9|Migrations with realistic data|Pass|E|MigrationTest|
|8.10|No routine destructive migration|Pass|C||
|8.11|Historical descriptions reproducible|Pass|C|Snapshot stored on each sale and return; no prices|
|8.12|No numeric overflow|Pass|B|Quantity limits|
|8.13|Dates/time zones/day boundary|Not tested|—||

## 9. Exports (no import feature: 9.9–9.13 N/A)
| # | Check | Result | Dev | Evidence |
|---|---|---|---|---|
|9.1|Export totals = DB totals|Not tested|—||
|9.2|Sales/returns/adjustments shown|Not tested|—||
|9.3|Date-filter boundaries|Not tested|—||
|9.4|Empty dataset|Not tested|—||
|9.5|Large export memory|Pass|E|100k products: 23 s, memory bounded|
|9.6|Opens in spreadsheet apps|Not tested|—|Workbook structure validated by test, but not opened in Excel or Sheets|
|9.7|Unicode/leading zeros/long names|Pass|B|XlsxWriter tests|
|9.8|No formula injection|Pass|B|`userTextIsNeverAFormula`|
|9.9–9.13|Import checks|N/A|C|No import|
|9.14|File-picker cancel harmless|Not tested|—||
|9.15|Low storage/write failure|Not tested|—||
|9.16|Sharing permissions|Pass|C|FileProvider for the labels cache only, temporary read grant|

## 10. Backup and restore
| # | Check | Result | Dev | Evidence |
|---|---|---|---|---|
|10.1|Includes all records|Pass|E|Round-trip test|
|10.2|Recognisable format/version|Pass|C|`.cncbak` checked on restore|
|10.3|Consistent during writes|Not tested|—||
|10.4|Max data|Pass|E|100k products: 22 s, 22 MB|
|10.5|Restore on fresh install|Pass|E|Stress backup restored into a separate install|
|10.6|Counts match after restore|Pass|E|Round-trip test|
|10.7|Malformed/truncated rejected|Pass|B,E|`truncatedFileFails…`, `corruptedBackupIsRejected…`, `nonBackupFileIsRejected`|
|10.8|Validated before replacing|Pass|C|Validates, then swaps in|
|10.9|Merge vs replace clear|Pass|C|Replace only, with a confirm dialog|
|10.10|Recovery path before replace|Not tested|—||
|10.11|Interrupted restore safe|Not tested|—||
|10.12|Older backup versions restore|Not tested|—|v1 backup into v2 app|
|10.13|Sensitive content protected|N/A|C|No personal or customer data; file not encrypted|

## 11. Kotlin / architecture
| # | Check | Result | Dev | Evidence |
|---|---|---|---|---|
|11.1|Business rules in one place|Pass|C|`InventoryRepository` is the single path for every stock change|
|11.2|No DB logic in composables|Pass|C|ViewModels|
|11.6|Blocking I/O off main thread|Pass|C||
|11.10|Observers cleaned up|Pass|C|Camera state observer removed (today)|
|11.13|Retries don't duplicate|Pass|E|Operation IDs|
|11.3–11.5, 11.7–11.9, 11.11, 11.12, 11.14|Scopes, cancellation, exception handling, races, lifecycle, leaks, `!!` (about 45 uses), abstractions|Not tested|—|Needs a code review|

## 12. Lifecycle
| # | Check | Result | Dev | Evidence |
|---|---|---|---|---|
|12.2|Background and reopen|Pass|E||
|12.6|Back doesn't duplicate operations|Pass|E|NavigationTest; operation IDs|
|12.10|Completed sale survives restart|Pass|E|Committed transaction; replay test|
|12.1, 12.3–12.5, 12.7–12.9|Rotation, lock/unlock, process death, unsaved forms, camera and file-picker results, phone calls, long-operation progress|Not tested|—||

## 13. Performance
| # | Check | Result | Dev | Evidence |
|---|---|---|---|---|
|13.1|Startup measured|Pass|E|LaunchUiTest time budget|
|13.2|Operation latency measured|Pass|E|Stress results in README|
|13.3|Smooth scrolling|Not tested|E|Inconclusive on the emulator; needs a phone|
|13.4|No ANRs|Pass|E|3,000 random taps at 10k and 100k: no ANR|
|13.5|Memory bounded|Pass|E,P|About 20 MB Java in stress; 78 MB total on P|
|13.6|Repeated navigation memory|Not tested|—||
|13.7|Recomposition cost|Not tested|—||
|13.8|Camera off after leaving|Pass|E||
|13.9|No background drain|Pass|E|No wakelocks; 0 CPU in background|
|13.10|APK/DB size reasonable|Pass|B|Release APK about 15 MB; DB 9.9 MB at 10k products|
|13.11|Low-end phone|Not tested|—||
|13.12|Measured on release build|Pass|E|`preview` (optimised) stress UI run|

## 14. UI and accessibility
| # | Check | Result | Dev | Evidence |
|---|---|---|---|---|
|14.12|Edge-to-edge/cutouts|Pass|E,P|Screenshots|
|14.13|Navigation/back predictable|Pass|E|NavigationTest|
|14.1–14.11|Screen sizes, font scaling, dark theme, contrast, touch targets, TalkBack, colour-only meaning, keyboard, numeric keyboards, states, feedback|Not tested|—|Needs a manual pass|

## 15. Device compatibility
| # | Check | Result | Dev | Evidence |
|---|---|---|---|---|
|15.1|Min Android (8.0) tested|Not tested|—||
|15.2|Recent Android|Pass|E,P|API 37; Android 13|
|15.3|Manufacturers|Pass|E,P|Google emulator, Samsung|
|15.4|Small/large screens|Not tested|—||
|15.5|Low-memory device|Not tested|—||
|15.6|Camera capabilities|Pass|E,P|Back, front-only, none (today); Samsung phone|
|15.7|Architectures|Pass|P|arm64 tested; armeabi-v7a included but not tested|
|15.8|Native libs compatible|Not tested|—|ML Kit and SQLite native libraries on 32-bit ARM|
|15.9|No unnecessary hardware requirement|Pass|C|Camera `required=false`|
|15.10|Locale formatting|Not tested|—||

## 16. Security and privacy
| # | Check | Result | Dev | Evidence |
|---|---|---|---|---|
|16.1|No secrets committed|Pass|C||
|16.2|No sensitive logs|Pass|C|Only warnings for camera and DB failures|
|16.3|Minimal permissions|Pass|C|CAMERA only|
|16.4|Exported components reviewed|Pass|C|Launcher activity only; provider not exported|
|16.5|Incoming files validated|Pass|E|Backup validation tests|
|16.6|Sharing only intended files|Pass|C|Labels cache only|
|16.7|Safe SQL binding|Pass|C|Room parameters|
|16.8|Backup/transfer behaviour|Pass|C|`allowBackup=false`, extraction rules|
|16.9|Threat-model protection|Not tested|—|No threat model written|
|16.10|Dependency vulnerabilities|Not tested|—||
|16.11, 16.13|Privacy disclosures, auth|N/A|C|Not on Play Store; no accounts|
|16.12|Data deletion as described|Not tested|—||

## 17. Offline
| # | Check | Result | Dev | Evidence |
|---|---|---|---|---|
|17.1|All features offline|Pass|C|INTERNET permission removed, so nothing can use the network|
|17.2|No hidden network at startup|Pass|C|Same|
|17.3|Models/assets offline|Pass|C|ML Kit model bundled in the app|
|17.4–17.8|Network errors, sync, retries|N/A|C|No networking|

## 18. Testing coverage
| # | Check | Result | Dev | Evidence |
|---|---|---|---|---|
|18.1|Stock/return rules unit-tested|Pass|B,E||
|18.2|DB transactions/constraints/migrations|Pass|E||
|18.3|Export/backup integration tests|Pass|B,E||
|18.4|UI tests for key journeys|Fail|—|Only launch and navigation; no sell or return UI test|
|18.5|Regression tests for found bugs|Fail|—|Today's R8 scanner bug and no-back-camera bug have no tests|
|18.6|Release-build smoke tests|Fail|—|None automated|
|18.7|Stress tests|Pass|E|10k and 100k|
|18.8|Failure tests (storage, permission, interruption)|Fail|—|Missing|
|18.9|Disposable test data|Pass|C|Separate `.debug` app|
|18.10|Failures investigated|Pass|—|Both of today's bugs found and fixed|

## 19. Distribution and support
| # | Check | Result | Dev | Evidence |
|---|---|---|---|---|
|19.1|Install instructions accurate|Pass|B|README (needs JAVA_HOME, as written)|
|19.2|User can see app version|Fail|C|Not shown anywhere in the app|
|19.3|Screenshots match app|Pass|E|README screenshots from the real app|
|19.4, 19.5, 19.8|Store requirements, data declarations, diagnostics data|N/A|C|Sideloaded; no data collection|
|19.6|Pilot group|Not tested|—||
|19.7|Crash reports/diagnostics|Fail|—|No crash log or diagnostics export|
|19.9|Release notes|Fail|—|None|
|19.10|Old artifacts and mapping kept|Fail|—|See 1.13|
|19.11|Faulty-update recovery plan|Fail|—|Not written (back up first, reinstall previous APK)|
|19.12|Users know backup/restore/report|Fail|C|Backup reminder in the app; no way to report problems documented|
