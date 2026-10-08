# C Nanjappa Cloth Center — Inventory

An offline Android app for keeping a clothing shop's stock. Staff scan a garment's barcode to sell one piece and see a green confirmation with the pieces left. The app also adds products and stock, records returns, and saves Excel reports, labels and backups. Everything stays on the phone. It needs no internet connection, account or subscription.

## Features

- **Sell by scanning.** Tap **Sell** and point the camera at a label. Each scan sells exactly 1 piece. The green "1 item reduced" result appears only after the sale is saved, with **Undo**, **Scan next** and **Done**. Holding a label in front of the camera never sells it twice.
- **Existing barcodes, no printing needed.** The app reuses the manufacturer's barcode or QR code already on the tag. It reads EAN-13, EAN-8, UPC-A, UPC-E, Code 128, Code 39, QR and Data Matrix.
- **Returns.** Scan or find the item, then tap **Return 1**. A return must match a recorded sale. Under **More options** you can record a damaged return (stock stays the same), return several pieces, or pick a specific sale.
- **Notes on products.** Add Product (and Edit details) has an optional Notes box, up to 500 characters, for anything extra: fabric, supplier, rack, price tag. Notes appear on the item, are searchable, and are included in the Excel Inventory sheet.
- **Products and variants.** Each combination of company, product, model, colour, size and sleeve (Full/Half) has its own stock count. Sizes include S–XXXL, 36–50, age sizes (0–1 to 18–20 years) and any custom size. White and Cream are always the first colours.
- **Add stock and correct stock.** Choose the quantity with large −/+ buttons. A stock correction needs a reason: Count correction, Damaged, Lost or Other.
- **Stock tab.** Search all products and see Full, Half and Total counts, with a size breakdown for each product.
- **Stock overview (Excel).** Saves the whole shop's stock as an `.xlsx` file with these sheets: Summary, Inventory, Product totals, Size breakdown, Movements and Barcodes.
- **Labels (optional).** Makes a PDF of Code 128 or QR labels in the exact number of copies you choose. You can save it, print it, or share it.
- **History.** Every stock change is listed. A sale can be undone from here.
- **Backup and restore.** Saves a checked backup file to a place you choose. Before a restore replaces anything, the app checks the backup file and saves a safety copy of the current data.

## Daily use (shop staff)

| Task | Steps |
|---|---|
| Sell | Home → **Sell** → scan the label → **Scan next** for the next item |
| Sell without the camera | **Products** → tap the item → **Sell 1** |
| Return | Home → **Return** → scan or find the item → **Return 1** |
| New product | Home → **Add Product** → tap the choices → set the quantity → **Save product** |
| New delivery of a known item | **Products** → **Scan to add stock**, or open the item → set the quantity → **Add stock** |
| Link another barcode to an item | Open the item → **Link barcode** |
| Check quantities | **Stock** tab → tap a product to see its sizes |
| Excel report | Home → **Stock overview** → choose where to save |
| Backup | Menu (⋮) → **Backup** → **Back up data** |

> **Keep a backup outside this phone.** Uninstalling the app or losing the phone loses its local data. The Home screen shows a reminder when there are changes since the last backup.

## Build

You need Android Studio, or its bundled JDK, and the Android SDK (compile SDK 37).

```bash
cd MyApplication
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assemblePreview   # optimised and debug-signed, for testing smoothness
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release-unsigned.apk
```

### Tests

```bash
./gradlew :app:testDebugUnitTest            # 26 JVM tests, a few seconds, no device needed
./gradlew :app:connectedDebugAndroidTest    # 28 device tests, needs a running emulator/phone
```

- **JVM tests:** size/colour/identity rules, input validation, barcode keys (UPC/EAN equivalence, leading zeroes), label codes, search escaping and the Excel writer.
- **Device tests** (real SQLite driver and triggers):
  - Every stock rule: no double sale on a repeated op ID, no overselling under 25 parallel sells, returns limited to real sales, undo once, stale corrections, barcode linking and sharing, notes, and triggers rejecting raw SQL.
  - Database migration v1 → v2, plus a backup round trip and rejection of corrupted backups.
  - The opening animation and a launch smoke test.
- **Your data is safe during tests.** Debug and test builds install as `com.cnanjappa.inventory.debug`, so running tests never touches the preview or release app's data on the same phone.

- **Release:** sign the release APK with the shop's own signing key. The project doesn't include a key.
- **Start-up:** the app ships a baseline profile (`app/src/main/baseline-prof.txt`), so Android pre-compiles the start-up code. The opening animation runs for 0.7 s while the database opens.
- **Testing smoothness:** use the `preview` or release build, because debug builds of Compose stutter. On the emulator, start it with `-gpu host`.
- **Phone requirement:** Android 8.0 (API 26) or newer.

### Run on the emulator

```bash
~/Library/Android/sdk/emulator/emulator -avd <your_avd> -gpu host &
adb install -r app/build/outputs/apk/preview/app-preview.apk
adb shell am start -n com.cnanjappa.inventory/.MainActivity
```

## Offline and privacy

- The app needs no internet, Google account or Play services. It asks only for **camera** permission, and you can still find items by name if you refuse it.
- The release build has no `INTERNET` or `ACCESS_NETWORK_STATE` permission. The manifest strips any that a library adds.
- The barcode decoder and fonts are bundled in the app, so nothing is downloaded on first use.
- Android's automatic cloud backup is turned off. Backups go only where the user chooses.
- Each phone keeps its own records. Two phones don't sync with each other.

## Tech stack

| Area | Choice |
|---|---|
| Language and UI | Kotlin, Jetpack Compose, Material 3, Navigation, Paging |
| Database | Room 2.8 with the bundled SQLite driver (WAL mode) |
| Camera and scanning | CameraX 1.6 with ZXing core 3.5 |
| Excel | In-house streaming XLSX writer |
| Labels | Android `PdfDocument`, with barcodes drawn as vector shapes |

## Project structure

```
app/src/main/java/com/cnanjappa/inventory/
├── domain/   size/colour/identity rules, barcode keys, internal label codes
├── data/     Room entities, DAO, InventoryRepository (all stock changes)
├── scan/     CameraX + ZXing analyser, thermal/battery helpers
├── export/   XlsxWriter, ExcelReport, LabelPdf, Backup
└── ui/       Compose screens and shared components
```

### How stock stays correct

- **One code path.** Every stock change goes through `InventoryRepository`, whether it comes from the camera or a button.
- **Saved once.** Each action has a unique operation ID. A repeated tap, a repeated camera frame or a retry after a crash returns the original result instead of changing stock again.
- **All or nothing.** The stock change and its history record are saved in one database transaction.
- **Database guards.** Sales and returns use conditional updates. Database triggers also block negative stock, returns beyond the original sale, and edits to history.
- **Consistent snapshots.** Excel reports and backups read one snapshot of the database, so sales can continue while they run.

## Status

- **Tested:** builds pass and all 54 automated tests pass on the emulator (API 37). I also checked these by hand on the emulator: adding products, sell/undo/return, the return limit, the Stock tab and History, and smoothness.
- **Not yet tested:**
  - On a real phone.
  - Camera recognition on real labels.
  - Printing.
  - Backup and restore.
  - Battery and heat.
  - Capacity tests (20,000 products).
- **Not built:** labels for several products in one PDF, custom label sizes, and a "Print completed" status.

Engineering details are in `NOTES.md`. Requirements and the test plan are in `../android_inventory_requirements.md`, `../agent.md` and `../test.md`.

## Licences

All runtime components are free:

- AndroidX, Jetpack Compose, Room, CameraX and ZXing are under the Apache License 2.0.
- The Inter font is under the SIL Open Font License 1.1 (see `INTER_FONT_LICENSE.txt`).
