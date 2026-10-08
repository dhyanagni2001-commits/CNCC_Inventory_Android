# C Nanjappa Cloth Center — Inventory

Offline Android app for a clothing shop. Scan a garment's barcode to sell it, add products and stock, record returns, and export Excel reports, labels and backups. No internet, account or subscription needed.

## Features

- **Sell by scanning:** each scan sells exactly 1 piece, with Undo.
- **Uses existing barcodes:** EAN, UPC, Code 128/39, QR and Data Matrix. No printing needed.
- **Returns:** only against a recorded sale, including damaged returns.
- **Products:** company, name, model, colour, size and Full/Half sleeve, with optional notes.
- **Stock:** add stock, or correct it with a reason. Search all stock, with a size breakdown.
- **Exports:** Excel stock report, barcode label PDF, and checked backup and restore.

## How it works

```mermaid
flowchart LR
    Cam["Camera scan<br/>(CameraX + ZXing)"] --> UI
    UI["Screens<br/>(Jetpack Compose)"] --> VM[ViewModels]
    VM --> Repo["InventoryRepository<br/>single path for every stock change"]
    Repo --> DB[("Room / SQLite<br/>stock + history<br/>triggers block bad data")]
    DB --> Excel[Excel report]
    DB --> Labels[Label PDF]
    DB --> Backup[Backup file]
```

```mermaid
sequenceDiagram
    participant Staff
    participant App
    participant DB as Database
    Staff->>App: Scan label
    App->>DB: Save operation ID (pending)
    App->>DB: One transaction: stock -1, sale + history, mark done
    DB-->>App: Pieces left
    App-->>Staff: Green "1 item reduced" + Undo
    Note over App,DB: A repeated scan or retry with the same ID<br/>returns the saved result, never sells twice
```

## Build and run

You need Android Studio (or its bundled JDK). Phones need Android 8.0 or newer.

```bash
git clone https://github.com/dhyanagni2001-commits/CNCC_Inventory_Android.git
cd CNCC_Inventory_Android/MyApplication
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew :app:assemblePreview    # optimised test build
adb install -r app/build/outputs/apk/preview/app-preview.apk
```

The release APK (`./gradlew :app:assembleRelease`) must be signed with the shop's own key. This repository contains no keys.

## Tests

```bash
./gradlew :app:testDebugUnitTest          # 26 unit tests, no device needed
./gradlew :app:connectedDebugAndroidTest  # 28 device tests, needs an emulator or phone
```

All 54 tests pass on the emulator (API 37). The tests run in a separate `.debug` app, so they never touch real shop data.

### Stress test (10,000 products)

```bash
cd MyApplication
scripts/stress.sh 300             # 300 = seconds of UI scrolling; report saved in test-results/
SKIP_DB=1 scripts/stress.sh 300   # rerun the UI, idle and random-tap phases without reseeding
```

It adds 10,000 products to the `.debug` app, then runs 5,000 stock changes, sale races, searches, an Excel export and a backup. It checks every quantity against its own count, then scrolls the app, checks it in the background and does 3,000 random taps.

Results on the emulator (API 37, 8 Oct 2026). Times are typical / 95th percentile:

| Action | 10,000 products | 100,000 products |
|---|---|---|
| Scan + sell | 1.8 / 6 ms | 1.9 / 5 ms |
| Return / undo / restock | 1–1.5 / 3–4 ms | 1.4–1.5 / 2–5 ms |
| Stock tab search | 10 / 20 ms | 217 / 299 ms |
| Products search | 6 / 10 ms | 69 / 104 ms |
| Full Excel export / backup | 3.4 s / 3.2 s | 23 s / 22 s |
| Database / Excel / backup size | 9.9 / 1.0 / 3.1 MB | 80 / 7.9 / 22 MB |

- **Correctness at both sizes:** 0 stock mismatches, stock history matches every quantity, and each of 50 "last piece" races sold exactly once. A repeated tap did not sell twice.
- **Search:** at 100,000 products the slowest search is 299 ms for 1 in 20 searches, under the 500 ms limit, so switching to full-text search is not needed yet. The Stock tab search is the slowest part because it totals sizes across all products for every search.
- **Stability:** 3,000 random taps and swipes at each size, with no crash and no "app not responding". Memory stayed at about 20 MB Java and 25 MB native.
- **Battery (estimate):** nonstop scrolling used 13–23% of one CPU core at 10,000 products and 41% at 100,000. On a phone with a 5,000 mAh battery, 10,000 products works out to about 0.5–2% per hour from the app; the screen itself uses more, about 3–8% per hour. In the background the app uses no CPU, holds no wakelocks and has the camera closed. The emulator has no real battery, so these figures need checking on a phone.
- **Smoothness:** inconclusive on the emulator. Many frames are late on both builds (52% optimised `preview`, 64% debug, in the same test). The optimised build halves the frames slowed by app code; most of the rest come from the emulator's graphics layer. Needs measuring on a real phone.

To test the optimised build with stress data, restore `/sdcard/Android/data/com.cnanjappa.inventory.debug/files/stress.cncbak` in the app (Menu → Backup → Restore backup), then run `SKIP_DB=1 PKG=com.cnanjappa.inventory scripts/stress.sh 180`.

**Not yet verified:** a real phone (battery, heat, smoothness), the camera on real labels, and printing.

## Project layout

```
MyApplication/app/src/main/java/com/cnanjappa/inventory/
├── domain/   size, colour and barcode rules
├── data/     database and InventoryRepository
├── scan/     camera barcode scanner
├── export/   Excel, labels, backup
└── ui/       screens
```

For more detail, see [MyApplication/NOTES.md](MyApplication/NOTES.md) and [android_inventory_requirements.md](android_inventory_requirements.md).

## Licences

AndroidX, Compose, Room, CameraX and ZXing are under Apache 2.0. The Inter font is under SIL OFL 1.1 (see [licence](MyApplication/INTER_FONT_LICENSE.txt)).
