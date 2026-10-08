# Simple Android Inventory App — Requirements and Test Plan

## 1. Goal

Build a smooth, simple Android inventory app for a clothing shop operated by people who are not comfortable with technology. The main task is: open **Sell**, scan an item's barcode or QR code using the phone camera, reduce stock by one, and show a clear green success message.

The app must also add products, add new stock, handle sold-product returns, generate printable labels, and show remaining stock by product and variant. Keep everyday actions short and easy to understand.

This document is a build specification. The test cases below must be implemented and executed; they are not completed test results.

## 2. Working assumptions

- First version: one shop using one Android phone or tablet as the stock record.
- All main functions work offline, including first-use scanning and label generation.
- No account, subscription, advertisements, paid API, server, or compulsory cloud service.
- A scan sells one piece. Several identical pieces require several intentional scans.
- One code identifies one product variant, not an individually serialized physical garment. Identical garments share a label.
- **Full / Half means sleeve type**, while **Size means clothing size**, such as 38, 40, 42, S, M, or L.
- Example interpretation: company **Ramraj**, product **White Shirt**, model/sub-name **RR Image**, colour **White**.
- These assumptions keep the first version simple. Multiple phones editing the same stock would require a separate synchronization design.

## Complete offline operation — mandatory

The installed app must work completely offline from its very first launch. Airplane mode, no SIM, no Wi-Fi, and lack of a Google account must not block any inventory operation.

- Launch branding and Home, product creation/editing, saved/custom size and colour choices, camera scanning, selling, undo, returns, restocking, corrections, history, stock analytics, QR/barcode generation, and local backup/restore all work without a network connection.
- Bundle barcode decoding code, fonts, icons, and all essential assets inside the APK. No first-use model download, remote configuration, online product lookup, activation, licence check, login, or server validation.
- All stock and history live in the device's local Room/SQLite database. Resolve barcode identities against that database; QR labels must encode local identifiers, not require opening a web URL.
- The release app must not request **INTERNET** or **ACCESS_NETWORK_STATE** permissions. Inspect the final merged manifest so dependencies do not silently introduce them. Remove analytics, advertising, crash-upload, and other network-dependent SDKs.
- Save labels and full backups to a local location using Android's file picker. Restore from a local file on the same phone, or a file transferred offline by USB or another local method. A phone change does not require a server.
- Sharing through an external app and printing through an external service depend on that chosen app/printer. They must remain optional. Local PDF/image label export always works offline; do not promise remote printing or cloud sharing without connectivity.
- No cloud fallback, sync queue, connection warning banner, or retry-to-server logic. Multiple independently installed phones do not synchronize stock.
- App distribution and updates are separate from operation: a signed APK can be transferred and installed offline. Online stores or optional cloud file destinations must never be necessary to run the installed app.

Required verification: install the signed APK on a clean physical device with all radios disabled, launch for the first time, add a product and custom options, generate/scan a label, sell, undo, return, restock, view analytics/history, export locally, restart the phone, and restore a local backup. Every core step must pass without downloading anything. Also verify the release manifest has no network permissions and that missing Google Play services does not block the scanner.

## 3. Clean user interface

### Launch screen — shop name

On a cold launch after tapping the app icon, show a clean branded launch page with the exact shop name **C Nanjappa Cloth Center**, then automatically open **Home**. No Continue button, login, advertisement, or user interaction is required.

- Center the shop name on a quiet white/neutral background with readable premium typography. Allow two lines on smaller screens rather than shrinking or clipping the name.
- Integrate the Android system splash with the branded launch page to avoid duplicate splash screens, blank flashes, or layout jumps. Use the system splash for an app icon if necessary and show the full shop name in the app's launch content.
- Keep the name visible only briefly during startup; target about 0.6–1 second on a fast cold launch, within the existing 3-second total startup budget. Do not add a long artificial delay or block the UI thread with a timer/sleep.
- Start local database preparation in the background immediately. Enter Home once essential state is ready. If preparation is unusually slow, show a short **Opening your shop…** status rather than a frozen screen.
- Use a subtle short transition to Home, following Android's animation settings. With animations disabled, navigate directly.
- Show branding once per cold launch. Returning from the background should preserve the current screen and task, not restart the launch page. Rotation during launch must not restart its timing or initialize the database twice.
- If launch encounters a recoverable problem, show a brief accurate message and Retry; preserve inventory and never reset the database to get past the launch screen.


Use three bottom tabs: **Home**, **Products**, and **Stock**. Home has large **Sell**, **Return**, **Add Product**, and **Stock overview** buttons. The **Stock** tab keeps its existing on-screen stock quantities and size/sleeve breakdowns. The Home **Stock overview** button instead generates the complete inventory Excel report described below; it does not navigate to the Stock tab. Put History and Backup inside a small menu.

- Use familiar labels, readable text, large buttons, and at least 48 dp touch targets.
- The entire button, including its text and icon, must be tappable.
- Use one clear main action per screen and sensible spacing.
- Avoid crowded dashboards, long animations, nested menus, and technical error messages.
- Green means a completed action; red means the action failed. Always include words and an icon so colour is not the only signal.
- Support Android text scaling without clipped buttons or overlapping fields.
- Show an explicit screen heading: **Selling item** or **Returning item**. Never infer the operation from a barcode.
- Keep the app usable with the keyboard open and on smaller screens.

### Tap-first interaction — mandatory

Every common choice must work by tapping a clearly labelled button. Do not make staff type Full, Half, White, a saved company, a saved model, or a standard size. A tap changes the selection immediately, with a visible selected state. Selecting a field does not sell an item or change stock.

| Task | Simple interaction |
|---|---|
| Sleeve | Tap **Full** or **Half** |
| Size | Tap a letter, number, or age-based size; choose **Add size** for another value |
| Colour | Tap a colour name with a swatch, such as **White** |
| Company / model / product | Tap a previously saved choice; use **Add new** only for first-time entry |
| Quantity | Large **−** and **+** buttons with the number between them |
| Sell without camera | Tap product, tap its size/sleeve if needed, then **Sell 1** |
| Return a good item | Scan or tap product, then tap **Return 1** |
| Add stock | Tap quantity with +/−, then **Add stock** |
| View quantities | Tap **Stock**, then a product to expand its sizes |

Show current stock beside Sell 1 and Return 1. A completed stock action updates the number immediately after commit and shows green feedback. **Sell 1** means −1 stock; **Return 1** means +1 stock for an eligible good-condition return. Keep selection buttons and stock-changing buttons visually distinct and label the latter with the action and quantity.

Keep one-item return as the default; hide sale-history selection and unusual options behind **More options**. Routine stock changes must not require typing. Stock corrections offer reason buttons **Count correction**, **Damaged**, and **Lost**, with optional **Other** text. Typing remains available for new names, custom sizes, and large quantities, but is never the only route for common choices.

Quantity selectors change a draft quantity only. Persist once on the labelled action button. Disable minus at zero, require at least one for restock, and never let rapid taps submit a stock action twice. Missing required selections show a short inline hint. Do not silently choose a clothing size or sleeve for the user.

### Home

Show four large action buttons: **Sell**, **Return**, **Add Product**, and **Stock overview**. An optional small **Pieces in stock** total may remain. Stock overview generates the complete inventory Excel report and opens the local Save Excel file picker once ready. The Stock tab remains the searchable on-screen product quantities page. Remove low-stock and out-of-stock cards, badges, filters, thresholds, and separate sections throughout the app. Products with zero pieces still appear with the ordinary quantity **0 pieces left**. A sale with no available stock shows **No pieces available** as inline feedback. No sales-value or revenue chart is needed.

## 4. Product fields and variants

### Add Product form

| Field | Required? | Example / behaviour |
|---|---|---|
| Product name | Yes | White Shirt |
| Sub-name / model name | Yes | RR Image; use General if there is no model |
| Size | Yes | Tap S–XXXL, a number from 36–50, an age range from 0–1 through 18–20 years, or a saved custom size such as 32 |
| Sleeve type | Yes | Exactly Full or Half; explicit selection required |
| Company / brand | No | Ramraj |
| Colour | No | White and Cream first; choose another colour or type it through + More |
| Opening quantity | Yes | Whole number, zero or greater |
| Existing barcode | No | Scan or type the manufacturer's barcode |

Show the first three required identity fields clearly. Use large tap-to-select buttons for saved names/models/companies, sizes, colours, and the required Full / Half selection. Company and colour stay visible but optional. Opening quantity starts at zero with large +/− buttons; tapping the number optionally opens a numeric keyboard for large quantities. **Add new** opens text input only when the desired name or option has not been saved before.

Keep a single name field and a single sub-name/model field. Do not ask for both “sub-name” and “model name” separately.

### Required sleeves and flexible colours

- **Sleeve *** is compulsory for every product variant in this app: exactly **Full** or **Half**. Do not offer Not applicable or silently preselect a value on a new blank form. When copying a variant, show the copied selection clearly and allow changing it.
- If sleeves are missing on Save, highlight just that field with **Choose Full or Half**. Keep all entered values.
- Colour choices must begin with **White** and **Cream**, in that order, on the first row. Use visible text labels; a white or cream swatch needs a border.
- Show other saved colours below them. **+ More** opens a small bottom sheet with saved choices and a **Type colour** field plus **Use colour** button. This supports arbitrary colours such as Blue, Maroon, or Olive.
- Trim typed colour names, reject blank custom colours, and match existing names case-insensitively. Typing white reuses White rather than creating a duplicate. Limit custom colour names to 40 characters with a short field hint.
- Save a custom colour only when its product form successfully saves. Afterward it is a one-tap choice; cancelling the sheet or form must not add unwanted saved colours. White and Cream remain first regardless of recent use.
- Colour remains optional, as originally specified; if it is unknown, keep it blank instead of assuming White. Full/Half, name, model, and size remain required.

### Flexible size list — required

Sizes must never be restricted to one fixed system. Show **Letter sizes**, **Number sizes**, and **Age sizes** as simple selectable sections:

- Letter sizes: **S**, **M**, **L (Large)**, **XL**, **XXL (Double XL)**, **XXXL (Triple XL)**.
- Number sizes: **36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50**. Include every integer, not only even numbers.
- Age sizes (years): **0–1, 1–2, 2–3, 3–4, 4–5, 5–6, 6–7, 7–8, 8–9, 9–10, 10–11, 11–12, 12–13, 13–14, 14–15, 15–16, 16–17, 17–18, 18–20**. These are garment size labels, not a customer age field. Display **years** clearly. Use an expandable Age sizes section with large tappable options rather than crowding every option onto the form.
- Keep age sizes distinct from letter and numeric sizes: **2–3 years** is not numeric **2**, numeric **3**, or **S**. Normalize hyphen/en-dash and spacing for equivalent age labels so **2-3** and **2–3 years** select the same age size; never infer a letter/number size from an age. Preserve the explicit size type during save, search, export, and backup/restore.
- Age sizes work in product creation/editing, manual sale/return/restock, Stock search and size filters, grouped size breakdowns, barcode linking and labels, Excel export, and backup/restore. In Stock filters, show saved sizes carried by inventory, including saved age ranges. Selecting a size/filter never changes stock.
- **Add size** supports any short custom size, including **32**, **34**, sizes above 50, or **Free Size**. This is a text option, not a restriction to the default list.
- Save custom sizes for future one-tap selection. Show saved custom values beside the standard choices and allow a product to use whichever sizes it carries.
- Treat **Large** and **L** as the same size, **Double XL / 2XL / XXL** as the same size, and **Triple XL / 3XL / XXXL** as the same size. Preserve a consistent readable label rather than creating duplicate variants for aliases.
- Numeric 40 and alphabetic L remain different sizes; never automatically convert them.
- Make selection clear; adding a size alone does not create stock. A variant is created only when the product form is saved with its quantity.

### Variant behaviour

Each combination of company, product name, model, colour, size, and sleeve type has its own stock quantity and internal code. Full and Half must never share stock. Different sizes must never share stock.

Example opening inventory:

| Company | Product | Model | Colour | Size | Sleeve | Pieces |
|---|---|---|---|---|---|---:|
| Ramraj | White Shirt | RR Image | White | 40 | Full | 4 |
| Ramraj | White Shirt | RR Image | White | 40 | Half | 4 |

The default main form action is **Save product**. Reuse an existing manufacturer barcode whenever possible; no automatic printing or sticker purchase is required. After saving, show **Product added — 4 pieces**. Offer **Add another size / sleeve**, which copies details and asks only for changed fields and quantity. Keep **Generate labels** optional inside the product for items that need new labels. Stock creation and optional label generation are separate recoverable steps: PDF failure never repeats a saved product.

### Validation and editing

- Trim outer spaces and compare duplicate identities case-insensitively; preserve readable display text. Size values such as “m” and “M” should match.
- Block blank required fields, negative/fractional quantities, unreasonable text length, integer overflow, and unsupported control characters. Define text limits in the form, such as 100 characters for names and 30 for size.
- A duplicate variant should open the existing item and offer **Add stock**, rather than silently creating another record.
- A direct barcode alias belongs to one variant; many aliases may point to that same variant. If a manufacturer code is discovered on multiple variants, explicitly mark it ambiguous and require manual variant selection. Never silently overwrite the mapping. Generated labels remain optional.
- Preserve leading zeroes in manufacturer barcodes; store them as text.
- Editing spelling keeps the stable internal code. If an edit would merge two variants, block it and explain which existing item matches.
- Changing size, colour, or sleeve requires a confirmation showing old and new details. Historic transaction descriptions retain the details at the time of the transaction.
- Archive unused products instead of deleting their history. Archived items cannot be sold or receive new stock; they remain available for linked returns and history. Any restocked return remains visible in Stock and can be reactivated.

## 5. Sell by scanning

1. Tap **Sell**. Camera opens with a large scan frame, torch button, and **Find product instead** fallback.
2. Place one label inside the frame. Accept a single unambiguous barcode/QR code.
3. Lock scanning immediately after accepting the code.
4. Find the exact variant, check available stock, and commit a one-piece sale locally.
5. Only after the database confirms success, show a green result card:

   **✓ 1 item reduced**  
   **Ramraj · White Shirt · RR Image**  
   **White · Size 40 · Full**  
   **3 pieces left**

6. Provide **Undo**, **Scan next**, and **Done**. Optional short vibration or sound confirms success.
7. Camera decoding remains paused until the user taps **Scan next**. Never resume automatically after a timer.

The sale is automatic after a valid scan because this is the requested main workflow. Do not add a confirmation popup for every normal sale. Display a short instruction on the scanner: **Scanning here sells 1 piece.**

### Preventing accidental repeat scans

- Repeated camera frames, callbacks, rapid taps, and screen rotation must not create another sale.
- Each intentional scan attempt has one persistent operation ID. Reusing that ID returns the existing outcome, without changing stock again.
- On **Scan next**, require the previous code to leave the frame before accepting it again. Display **Move label away, then scan the next item** when necessary.
- This allows deliberate sales of identical items with the same label, while preventing repeated deductions from a label held still.
- If several labels are detected, show **Point at one label** and make no stock change.
- Manual fallback: search, choose the exact variant, then tap **Sell 1**. It uses the same stock rules as scanning.

### Very fast scan-to-green feedback — release priority

For a clear supported label held within a focused, well-lit scanner, selling should feel nearly immediate. Do not promise literal zero latency: camera exposure/focus, frame delivery, decoding, database commit, and display rendering take time. Avoid artificial delays and measure the complete interaction, not just barcode decoding.

- While scanner is active, analyze incoming frames continuously at a bounded useful cadence. Do not wait for a capture-button press, an animation, a timer countdown, or an arbitrary multi-second stabilization window.
- Start indexed barcode lookup as soon as a valid unambiguous label is decoded. In the normal linked-code path, one code resolves directly to a variant without loading product lists, querying remote services, opening a product-details page, or asking for confirmation.
- Bundle the decoder so first-use operation is offline. Keep database/indexes ready after normal app startup; reuse the decoder and bounded processing resources. Scanner preparation must not be repeated for every frame.
- Crop analysis to the scan area where practical; handle frame rotation and autofocus properly. Decode the configured supported formats and use the lowest tested resolution that reliably reads the intended garment labels.
- Lock one operation immediately when accepting a code. Decode, resolve, conditionally decrement, and record the sale using bounded work; update the visible result immediately after commit. Do not calculate analytics, generate labels/Excel, refresh unrelated lists, or load thumbnails before showing green.
- Display **1 item reduced** and the persisted remaining count in the next available UI frame after success. Product image loading and optional motion are secondary. Haptic/sound success follows commit and must not precede an actual saved sale.
- A 120–200 ms transition must not delay the appearance of success text, enablement of Done/Undo, or the start of the next requested scan. No artificial minimum spinner or success dwell time.
- Keep the existing duplicate-scan gate: a single held label never sells twice. Show Scan next immediately after commit; rearm only through the deliberate action. Scanner restart and prior-label clearance must be tuned/tested so safety does not introduce unnecessary pauses for normal next-item movement.
- With Battery Saver or thermal stress, workload limits may reduce scan speed. Never override thermal protections to meet a benchmark; provide manual product selection if the device cannot sustain scanning safely.

Benchmark targets on named representative physical phones, in release builds, at 20,000 products / up to 100,000 variants:

| Interval | Target under clear-label normal conditions |
|---|---|
| Readable label arrives inside an already focused active scan frame → green committed result | p50 ≤ 300 ms; p95 ≤ 600 ms |
| Decoder returns valid known code → database commit and visible green result | p95 ≤ 100 ms |
| Database committed → visible success | Next available display frame, without an added timer |

Measure over at least 200 varied real garment labels, including UPC/EAN and internal Code 128/QR where used, with at least 95% first-pass recognition for clean labels. Report misses and timeouts separately, not just successful fast scans. Also test near/far distances, glare, motion, small bars, and mixed lighting; report those separately from ideal-condition targets. Do not introduce speculative barcode matching to improve speed. Ambiguous manufacturer codes still require explicit variant selection.

Measure camera-ready time separately from label-to-success latency. If a named device cannot meet targets, profile and fix the actual bottleneck, or state its measured limits before release. These requirements are acceptance targets, not completed benchmark results.

### Undo a mistaken sale

Undo targets the specific successful sale, restores its remaining unreturned quantity, and records a reversal in one transaction. It can happen only once. A sale already fully returned or reversed cannot be undone again. If partly returned, Undo restores only the remainder and explains the quantity.

The immediate result screen offers Undo. History also allows a deliberate sale reversal with confirmation, so an accidental scan can be corrected after leaving the screen. Never erase the original sale record.

## 6. Return a sold item

1. Tap **Return**. Clearly display **Returning item**.
2. Scan the label or find the exact variant manually. Scanning here identifies the item; it does not immediately change stock.
3. Show the exact product, size, sleeve, current stock, and a large **Return 1** button. Default to good condition. Do not require the user to browse sale history.
4. Internally allocate the return to the newest eligible recorded sale for that exact variant. This is inventory accounting, not identification of the physical sale: shared variant labels cannot prove which transaction an item came from. Freeze the selected sale for the operation and revalidate on commit.
5. **More options** allows choosing a specific sale, selecting **Damaged — do not put back in stock**, or using +/− for a larger return quantity. A larger return is limited to the selected sale's eligibility.
6. Tap **Return 1** once for a normal return; use **Record damaged return** or **Return N** when options change the action. The button itself commits the action, with no second routine confirmation dialog.
7. After commit, show **✓ 1 item returned — 4 pieces left**, or **✓ Return recorded — stock unchanged (damaged)**.

Rules:

- A return must reference an existing sale and the same variant.
- Total returns plus sale reversals cannot exceed the original sold quantity, including damaged returns.
- Double taps and repeated submissions record only one return.
- A damaged return consumes return eligibility but does not increase sellable stock.
- If no matching sale exists, show **No recorded sale found**. Offer **Correct stock** as a separate action with a required reason; do not invent a sale or silently process an unverified return.
- Returns remain available for archived variants through their sales history.
- An exchange is two simple actions: return the old variant, then sell the new variant. Do not build a separate exchange workflow in this version.
- Return refers to inventory movement. Payment/refund processing is outside this app's scope.

## 7. Add stock and correct stock

Inside a product, provide **Add stock** and **Correct stock**.

- Add stock: choose a positive whole-number quantity using +/− buttons, review the variant, then tap **Add stock**. Record a restock movement.
- Correct stock: choose the actual counted quantity using +/− buttons, including zero, and tap a required reason button (Count correction, Damaged, Lost, or Other). Other requires a short explanation. Show current and new quantity before confirmation. Record the difference as an adjustment.
- Use stock correction for physical counting errors, losses, or damaged stock removed from the shelf. Never edit the quantity invisibly through the product form.
- Disable the action while saving and make retries safe with operation IDs.

### Multiple companies and mixed barcode formats — required

This app is for the entire shop, not only Ramraj. Support **Ramraj**, **Udyam**, **Puma**, and arbitrary other company names. These are example brands, not claims about which code formats those manufacturers actually print. Staff tap saved companies or Add new to enter another. Never hardcode Ramraj in data models, lookup logic, generated labels, search, reports, or validation.

- Company is part of variant identity. Ramraj / Shirt / White / 40 / Full and Udyam / Shirt / White / 40 / Full remain separate products and stock counts even when other details match. Company remains optional for unbranded/unknown stock; never assume a brand from an unread label.
- Support offline decoding of **EAN-13, EAN-8, UPC-A, UPC-E, Code 128, Code 39, QR Code, and Data Matrix** in the default mixed-shop scanner. These formats are supported by the chosen ZXing core. Test actual sample labels from every stocked company before rollout; other supported symbologies can be enabled if shop labels require them. Do not claim universal decoding of proprietary, encrypted, unreadable, or unsupported symbols.
- Staff do not have to choose a company or barcode format before an ordinary scan. Detect the supported format, read the payload, and perform an indexed local lookup to find its saved company and exact variant.
- Use format plus exact decoded identity as the lookup key, with narrowly defined tested UPC/EAN equivalence normalization. Preserve raw content for inspection/export. Do not lowercase, strip leading zeroes, truncate, or guess identities by substring/prefix. Two unrelated codes must not be collapsed by normalization.
- On first encounter, staff scan a label, select/enter the company and variant, and save the mapping once. The barcode/QR decoder does not by itself know the company, model, size, or colour. No internet catalog is needed or consulted.
- Several valid codes or different formats can link to the same variant: an EAN garment label and a product-specific QR label may both resolve to one stock count. Linking adds aliases only, never stock. Mixed-brand scans do not require scanner restarts or switching brand modes.
- A QR may contain a product identifier, text, or a URL. Treat a stable product-specific payload as a local alias after deliberate linking. Never open the URL or require fetching the webpage. A company homepage, promotional QR, payment QR, or other code shared across products cannot automatically identify a variant. Prefer the garment's product barcode or require manual selection.
- If the same lookup key appears on products from different companies, record it as ambiguous; show company/model/size/sleeve choices and require selection before sale/return/restock. Selecting a company at creation time does not make an otherwise ambiguous scan unique. Never overwrite another company's mapping or deduct from whichever match was saved first.
- Multiple visible labels on packaging must not create multiple sales. If more than one unresolved/distinct code is detected, ask **Point at the product label**. If verified aliases of the same variant are visible together, accept one deliberate operation only. Apply existing duplicate-frame and Scan next safeguards.
- Unsupported or unrecognized labels show **Label not recognised — Find product**; all manual stock actions remain available free and offline.
- Keep the fast-scan and power requirements while supporting mixed formats. Benchmark the actual mixed label set rather than achieving speed by disabling a format needed by another company.
- Stock screens and complete Excel exports include company and group totals without mixing brands; internal generated codes remain catalog/variant identifiers rather than manufacturer-specific conventions.

Required tests: consecutive scans across companies/formats; identical descriptions across brands; direct EAN and QR aliases to one variant; exact-key collision across brands; leading zeroes and UPC/EAN normalization; shared website QR; several labels in one frame; unknown company; unsupported format/manual fallback; correct company totals in Excel; all cases fully offline.

Reference: [ZXing supported formats](https://github.com/zxing/zxing). Use the bundled core decoder with CameraX, not the deprecated separate Barcode Scanner app or an external scanner installation.

### Existing barcode first — zero printing cost

Default to reusing the code already printed on the garment's packaging or hang tag. This requires no new sticker, barcode printer, or dedicated scanner: the Android phone camera is sufficient. Manufacturer code conventions vary; the app must not assume that identical garments share a code or that each code uniquely identifies a physical garment.

**Product variant** means company + product name + model + colour + size + required sleeve type. Example: Ramraj / Shirt / RR Image / White / 40 / Full. Stock belongs to that variant. Barcode aliases are lookup keys, not stock records. Preserve raw barcode text and leading zeroes; distinguish symbology where needed and use a consistent tested representation for equivalent UPC/EAN forms.

#### First delivery: link once, count quantity

1. Tap **Add product**, then **Scan existing barcode** and scan one example garment.
2. If unknown, fill/select its name, model, company, colour, size, and Full/Half. The barcode does not magically contain these descriptions; staff enter them once. Existing saved options remain tappable.
3. Count how many pieces of that exact variant arrived and choose the quantity with +/− or optional numeric input. Four identical garments means quantity 4.
4. Tap **Save product**. Save the variant, barcode alias, and opening-stock movement atomically with one operation ID.
5. If those pieces share the same barcode, scanning every piece during stocking is unnecessary. Check a small sample of labels before relying on that assumption.

#### Same variant, different existing codes

Multiple different manufacturer codes may map to one variant, for example two individually numbered packages of the same Ramraj / RR Image / White / 40 / Full shirt. **Link another barcode** on the product scans an unknown code, shows the selected variant, and asks for one **Link barcode** tap. This attaches the alias only; it does not add a piece or change stock.

If every physical garment has its own unique manufacturer code and staff want every code to work at sale time, each unique code must be linked at least once. There is no safe way to discover unseen codes automatically. A batch **Link more labels** mode can reduce taps: choose the variant once and deliberately scan each new code, with review/confirm at the end. Repeated scans of the same code do not add aliases or stock. Quantity received is a separately confirmed number, not inferred from linking.

For a zero-printing-cost alternative to registering many unique codes, find the variant in Products and tap **Sell 1**. Never guess the variant from a partially matching code.

#### Later deliveries / restocking

1. Tap **Add stock** on the product, or use **Scan to add stock** from Products.
2. Scan one already-linked code. Show the exact variant and existing quantity; scanning here identifies the variant without changing inventory.
3. Enter quantity received, for example 4, and tap **Add stock** once. Quantity 3 becomes 7. No new barcode record or printing is needed for an already-linked code.
4. For an unknown code, offer **Link to existing product** or **Add new product**. If linked to an existing product, confirm Link barcode separately, then show the ordinary quantity/restock action. Do not silently treat linking as a received item.
5. Always check that sizes/sleeves/colours in a delivery match the chosen variant. Stock mixed variants separately.

#### Sale and return

Sell scan → barcode alias lookup → exact variant → atomic stock −1 → green confirmation. Identical shared barcodes can be sold repeatedly through intentional Scan next/rearming. Different linked aliases of the same variant update that same quantity.

Return scan → barcode alias lookup → same variant → eligible sale allocation → Return 1 → stock +1 for a good item. Alias registration does not introduce individual-piece traceability. A repeated sale of an individually numbered label cannot prove physical identity in this variant-level version; retain the existing anti-repeat camera gate and stock limits.

#### Barcode shared across different variants

If the same manufacturer barcode is printed on size 40 and 42, or Full and Half, it cannot safely identify one variant. Never overwrite a previous mapping. Mark that code as ambiguous and disable automatic sale for it. Show **Choose size and sleeve** with candidate variants and their quantities; user selects the exact variant and taps Sell 1. Returning/restocking through that code likewise requires explicit variant selection.

Store a separately flagged ambiguous-code candidate relation rather than violating unique alias-to-variant constraints. A valid direct alias still maps to exactly one variant; ambiguous resolution never performs a mutation before selection. If no reliable code exists, manual product selection remains the no-cost fallback. Optional printed labels stay available, but are not compulsory.

Required tests: shared code on identical pieces; several different codes linked to one variant; batch quantity added once; linking with no quantity change; unknown-code restock; same code discovered across different sizes/sleeves; duplicate alias scans; leading zeroes; cancelled linking; repeated operation recovery; both direct and ambiguous sale/return resolution.

## 8. Generate QR codes and barcodes

- Automatically create a unique stable internal variant code when a product variant is saved.
- Default label format: **Code 128 barcode**, as requested for printed clothing labels. Offer QR code as an optional **Label type** choice; both work offline.
- Both formats resolve to the same stable variant. Never encode current quantity, price, or editable product names as the identity.
- Use a short app-specific code containing a catalog identifier and variant identifier. Codes from another shop must not accidentally match local products.
- Keep manufacturer barcodes as separately validated aliases; multiple codes may resolve to the same variant. Handle codes shared across variants through the explicit ambiguous-code selection flow.
- Label text: company/product, model, colour, size, sleeve type, and readable internal code.
- Provide **Save label**, **Share**, and **Print labels** through Android's normal file/share/print UI. A PDF label sheet supports multiple copies.
- Multiple identical garments can use copies of one variant label. Generating or reprinting labels does not add stock.
- Use black codes on white with suitable quiet zones and adequate physical size. Test exported and printed codes, not only on-screen codes.
- Product details can change without invalidating existing labels. Reprinting must retain the same identity.
- Unknown codes show **Product not found — add product or search**. Do not reduce stock or create an item automatically.

### Quantity-matched label PDFs and printing — required

Example: add **4 Ramraj / RR Image / White / Size 40 / Full** shirts. Create one stable variant barcode and repeat it on **4 physical labels** in the PDF. Attach one label to each shirt. Scanning any copy in Sell reduces that exact variant by one; scanner rearming prevents accidental repeat sales.

| Added garments | Generated labels |
|---|---|
| 4 identical Size 40 Full shirts | 4 copies of one variant barcode |
| 4 Full and 4 Half shirts, same size | 8 labels: 4 copies of the Full code and 4 copies of the Half code |
| Add 3 more to an existing quantity of 4 | 3 new labels by default, not 7 |
| Opening quantity 0 | Save variant, no automatic label PDF; Labels available later |
| Return one previously labelled garment | No automatic new label; optional replacement-label reprint |

#### Simple workflow

1. Default Add Product action is **Save product**, with existing-barcode linking and no label generation. If the user explicitly enables optional **Save & generate labels**, commit once and generate labels for the opening quantity. Existing barcodes remain the zero-printing-cost default.
2. Show **Labels ready — 4 labels** with product description, a small label preview, and **Save PDF**, **Print**, and **Done**. Generating a PDF does not mean it has been saved to the user's chosen folder or physically printed.
3. **Save PDF** opens the local file picker. Export a complete PDF that the user can transfer to a computer or printing shop offline.
4. **Print** submits the generated PDF through Android's system print dialog. The user chooses an available compatible printer and confirms. Never silently send a job to whichever printer happens to be nearby.
5. On Add stock, reuse existing labels and do not generate or print new labels automatically. If the user opens optional Generate labels, default its count to the quantity just added. Inside any product, **Print labels** supports explicit copy count with +/−, default 1 for a replacement label; **Labels for current stock** explicitly chooses the current quantity.
6. Optional **Print several products** selection gathers variants and their explicit copy counts into one PDF to reduce paper waste. This does not change stock.

#### Printer compatibility and offline guarantees

- Local PDF generation and saving are mandatory and completely offline. Direct physical printing is optional and depends on Android-compatible printer hardware and an installed print service.
- Compatible printing may use a local connection without internet, depending on the printer/service. Do not promise universal Bluetooth, USB, thermal-printer, Wi-Fi Direct, or network-printer compatibility. A nearby printer is not automatically supported.
- Delegate discovery and connection to the system print UI so the inventory app retains its no-network-permission design. Dedicated direct Bluetooth/USB printer support requires a known printer model, protocol, permission design, and tested compatibility; do not assume it in the first version.
- If no compatible printer is available, show **Save PDF to print elsewhere**. This is a normal fallback, not a large error dialog.
- Distinguish **PDF ready**, **PDF saved**, **Print submitted**, and **Print completed**. Only use completed status if the print service reports it; do not infer paper output from tapping Print.

#### Barcode layout and efficient rendering

- Default to black Code 128 bars on white, with readable product/model, colour, size, sleeve, and internal code. Use the short stable internal variant code with its catalog identity; keep it within a length that fits the tested label width. No editable quantity in the identity.
- QR is an alternate layout, not an additional code printed by default. Do not use GS1/EAN retail numbering unless a separately valid assigned identifier is provided; internal Code 128 is sufficient for this inventory app.
- Provide an A4 sticker-sheet preset for ordinary printers and a configurable label dimension/paper preset. Store one shop's selected template so users do not repeat setup.
- Layout must honor physical label width/height, margins, gaps, print-safe area, barcode quiet zones, and minimum tested bar/module widths. Render bars as crisp PDF vector rectangles where practical. Never stretch a barcode nonuniformly or fit long content by making bars unreadably thin.
- Recommend actual-size/100% printing for calibrated sticker sheets. Verify alignment and scanability on the chosen physical template/printer. Thermal label output is available only with a tested compatible print service and media preset.
- Generate exactly N labels over as many pages as required, with no extra product labels in unused cells. A 4-label sheet uses 4 cells; allow a saved starting-cell option for partially used sticker sheets under More options.
- Encode each unique variant once per PDF batch and reuse its vector/bar pattern for repeated labels. Render/write bounded pages off the main thread; release intermediate objects and do not allocate 20,000 label bitmaps simultaneously.
- Support cancellation and chunked generation for large batches. Cancelled or failed exports leave no misleading complete file and never alter stock. Retry PDF generation from the original saved operation/batch, not from a fresh Save product action.
- Reprinting repeats the existing code and requested copy count; no new inventory, variant, or physical-item serial record is created. Copies are not individually tracked, so duplicate physical labels cannot prove item identity.

Reference: [Android custom document printing](https://developer.android.com/training/printing/custom-docs).

## 9. Stock analytics page

The tab can be called **Stock**, with a page heading **Stock overview**. Show simple counts rather than complex charts.

Example product card:

**Ramraj — White Shirt**  
**RR Image · White**  
**Full: 4 pieces left**  
**Half: 4 pieces left**  
**Total: 8 pieces**

Tap the card to see the size breakdown:

| Size | Full | Half | Total |
|---|---:|---:|---:|
| 40 | 4 | 4 | 8 |
| Total | 4 | 4 | 8 |

- Summary cards group by company, product, model, and colour. Sum sleeve totals across sizes, and show sizes when expanded.
- Search supports product, company, model, colour, and size.
- Show the product list and search directly, with no low-stock or out-of-stock filter chips, badges, or separate views. Use ordinary quantity counts, including zero.
- Show zero-stock variants so staff know what to refill.
- Include archived variants with remaining stock, visibly marked Archived; omit archived zero-stock variants from the normal list.
- Refresh stock after every committed sale, return, restock, reversal, or adjustment, without requiring a manual reload.
- A small **Today's activity** summary may show pieces sold, returned, and sale reversals separately. Count by the shop's configured local timezone; do not label sales as revenue.

### Stock overview / Inventory Analytics → Excel report

Keep all existing **Stock** tab functionality unchanged. The Home **Stock overview** action (also called Inventory Analytics when referring to the export feature) generates a genuine **.xlsx Excel workbook**, not CSV renamed as Excel and not merely a picture/PDF of the stock screen. Use one button for this action, not two duplicate analytics buttons.

#### User flow

1. Tap **Stock overview** on Home.
2. Show a short **Preparing Excel report…** message and progress/cancel for large reports. Generate offline without blocking navigation or ordinary stock actions.
3. When ready, open Android's local file picker to **Save Excel**. Suggested filename: **C_Nanjappa_Inventory_YYYY-MM-DD_HH-mm.xlsx**.
4. After a successful write, show **Excel saved**, with optional **Open** and **Share** actions. Opening requires an installed compatible spreadsheet viewer; if none is available, retain the saved file and show **Excel saved. Open it on a computer**. Do not make a paid Excel licence, Microsoft login, viewer installation, or network access necessary to generate/save the report.
5. Cancelling, failing, opening, or sharing the export never changes inventory. If generation or saving fails, use a short accurate message and allow safe retry.

#### Complete report contents

Export the entire shop inventory, independent of any current search or screen filter, including zero-stock and archived variants with their status. One consistent database snapshot determines counts and movement history; show **As of** timestamp and configured shop timezone on the Summary sheet. Later sales appear in the next export, not as a partially updated mixture in the current workbook.

| Worksheet | Contents |
|---|---|
| Summary | Shop name, report time/timezone, product and variant counts, total sellable pieces, gross pieces sold, good and damaged returns, reversed sale pieces, restock pieces, and adjustment totals; clear date coverage for movement totals |
| Inventory | One row per variant: company, product name, model/sub-name, colour, size, sleeve, current pieces, active/archived status, internal variant code, and linked barcode count |
| Product totals | Group by company/product/model/colour; Full quantity, Half quantity, total quantity, and distinct sizes. Include only quantities that actually exist and calculate totals consistently |
| Size breakdown | Company/product/model/colour/size, Full pieces, Half pieces, and total pieces |
| Movements | Recorded opening stock, sales, good/damaged returns, reversals, restocks, and corrections: timestamp, movement ID, related operation/sale IDs, product details, stock delta, and quantity/action type. Damaged returns show zero stock delta and their returned quantity explicitly |
| Barcodes | One row per direct barcode alias with exact text, format where known, and linked variant; ambiguous codes/candidates clearly marked. Multiple codes must not duplicate inventory quantities |

Movement totals cover all recorded history by default and state the earliest included date. Distinguish gross sales, reversals, good returns, damaged returns, and actual stock deltas; do not describe them as revenue or refund amounts. The app does not collect enough price/payment data for financial analysis. Count archived records transparently rather than silently excluding them.

#### Workbook quality and performance

- Use clear headings, frozen header rows, filters, readable column widths, and consistent date/number formatting. Quantities are numeric cells. Barcode/internal IDs are literal text cells, preserving leading zeroes and long codes. User-entered names/colours must remain literal text, never interpreted as spreadsheet formulas.
- Use worksheet formulas where useful for totals, with correct cached results for previewers, or export clearly calculated snapshot totals if the offline writer cannot reliably cache formulas. Verify totals against the snapshot, not against later live stock.
- Generate with a free library that supports local XLSX writing and streaming/bounded-memory generation on Android. Pin and test its dependency/licence/API compatibility before choosing it. No cloud conversion service, paid Excel API, or backend.
- Stream rows in bounded batches; do not load a million movement objects or the full workbook into memory. Use a consistent bounded read snapshot that does not impose long write locks on sales. Test that backup and export policies remain compatible with the battery/heat requirements.
- Excel supports up to 1,048,576 rows per worksheet, including headers. Split larger history into **Movements 1**, **Movements 2**, etc.; export all rows with a summary of sheet counts rather than truncating silently. Respect per-cell limits and make any required truncation of noncritical long notes explicit.
- Write to a temporary complete file, validate the workbook, then export via the file picker. Never report Excel saved for a partial or failed file. Clean up cancelled temporary exports and avoid retaining unlimited old copies inside the app.
- An Excel report is for analysis, not the app's full restorable backup. Keep Backup/Restore separate. Do not add Excel import or multi-phone synchronization in this version.

Required checks: complete zero/archived inventory; Full/Half/size totals; multiple aliases without double counting; leading-zero barcode cells; user text beginning with =/+/-/@; damaged returns/reversals; snapshot correctness while selling; offline saving with no Excel app; cancellation; large streamed exports; multi-sheet history; independent workbook opening in compatible spreadsheet software.

## 10. Data reliability rules

Keep these rules in the data layer, not just the UI:

1. Available stock can never become negative.
2. A stock change and its movement record must commit together or neither commits.
3. For a sale, use a conditional database update that succeeds only when sufficient stock exists. Check the affected-row result inside the transaction.
4. Use a unique operation ID for each intentional mutation. Scanner frames, repeated taps, and retries cannot create duplicate movements.
5. Validate return eligibility and commit the return plus stock update in one transaction.
6. Reversals reference the original transaction and cannot exceed its remaining reversible quantity.
7. Stock equals the sum of recorded stock deltas, starting with the opening-stock movement. Damaged returns have a zero stock delta but still consume return eligibility.
8. Product identity, internal codes, and barcode aliases have database uniqueness constraints.
9. Before a mutation, retain the operation ID and intent in recoverable local state. After interruption, query its committed result before offering a retry. Never automatically repeat an operation whose outcome is unknown.
10. Never display success before commit. If the app closes after commit, reopening shows the committed result without selling again.
11. Preserve inventory and history during app updates using tested database migrations. Never use destructive migration as a production fallback.

Minimal internal records: **Variant**, **BarcodeAlias**, **Sale**, **Return**, and **StockMovement**, plus recoverable operation state. Keep these concepts out of the ordinary user interface.

Each movement stores an ID, operation ID, variant ID, type, quantity delta, timestamp, and related sale/return/reversal ID where applicable. Sales and returns keep the variant description snapshot for readable history.

## 11. Smoothness and recovery

### Short, appropriate messages

Use a short inline hint near the affected field or a small message bar with one useful action. Avoid large error dialogs, stacked notifications, full-screen failure pages, technical codes, database details, and stack traces. Keep entered values and the user's place. Assistive technology must announce feedback; actionable messages stay available long enough to use.

| Situation | User-facing message | Next step |
|---|---|---|
| Missing sleeves | Choose Full or Half | Highlight sleeves |
| Missing size | Choose a size | Highlight sizes |
| Missing name/model | Enter product name / Enter model name | Focus the respective field |
| Blank typed colour | Enter a colour | Keep colour sheet open |
| Duplicate variant | This item already exists | Add stock |
| Barcode assigned elsewhere | Label belongs to another item | View item |
| Unknown scan | Product not found | Find product |
| Zero stock sale | No pieces available | Done |
| No eligible sale to return | No sale found for this item | Correct stock |
| Return limit reached | This sale is already returned | Choose another sale |
| Camera unavailable | Camera unavailable | Find product / Retry |
| Camera permission needed | Allow camera to scan labels | Allow camera / Find product |
| Insufficient storage confirmed | Storage full. Free some space | Keep form for retry |
| Write confirmed not committed | Could not save. Try again | Retry with the same operation ID |
| Commit outcome unresolved | Checking your last action… | Check persisted result; no blind retry |

Only name a cause such as storage full when detected. Errors must never change stock, show false success, or repeatedly pop up for every camera frame. Keep logs local for development; do not expose their contents in the normal interface.

### Premium appearance and motion

Use a refined native Android design: consistent typography, generous spacing, restrained blue actions, quiet white/neutral surfaces, subtle borders, and green committed-success feedback. Preserve readable large controls; premium must not mean tiny text or decorative complexity.

Use short 120–200 ms transitions and subtle touch feedback. Avoid bouncing, elaborate full-screen effects, endlessly animated decorations, or animating every list row. Respect the system animation scale, including disabled animations. Navigation and buttons remain responsive during transitions. Preserve scroll position and update affected counts without jumping or rebuilding the entire page. A static mockup demonstrates appearance only; smoothness must be measured in the implemented app.

### Battery, heat, and resource efficiency — required

Minimize unnecessary battery drain and sustained heat alongside smoothness. Do not achieve smoothness by keeping the CPU, camera, flashlight, or display active continuously. Software cannot guarantee zero battery drain, no warmth, or no long-term battery aging: device condition, charging, ambient temperature, brightness, and camera usage also matter. Validate actual energy use on real phones; do not claim that stack selection alone protects battery health.

#### Camera and flashlight

- Open the camera only while a visible scanner actively needs it. Release preview, analysis, and torch on app backgrounding, screen lock, leaving the scanner, or successful scan. Success may retain a static preview image; it must not keep the live camera running. Restart only after Scan next or explicit Resume scan.
- Pause and release an unused scanner after about 30 seconds without user interaction or successful scanning; show **Tap to scan again**. Do not make the user restart a completed transaction.
- Start with a modest scan resolution around 720p and benchmark a decode rate around 10–15 frames per second while actively scanning, increasing only when measured label accuracy requires it. Camera preview cadence and decoding rate are separate; preview should remain smooth while unused analysis frames are dropped.
- Restrict decoding to supported inventory formats and the scan region where practical. Reuse bounded buffers and decoder resources; avoid converting full frames into newly allocated bitmaps for every callback. Process one frame at a time and close all frames.
- Torch is off by default, controlled by the user, and switched off when pausing or leaving scanning. Never leave it on behind the success screen.
- Respect the normal display timeout. No permanent keep-screen-on flag or app-acquired CPU wake lock for normal inventory use. If a brief scanner-only screen-awake option is implemented, clear it on every exit and inactivity pause.

#### Idle, background, and daily work

- On Home, Products, and Stock, do work in response to user input or committed database changes. No periodic refresh loops, continuous redraws, battery-status polling, busy waiting, permanent foreground service, background camera, or unnecessary sensor subscriptions.
- Main inventory functionality needs no GPS, Bluetooth scanning, network polling, cloud synchronization, or telemetry uploads. Request only permissions actually needed.
- Use bounded workers and queues. Cancel obsolete searches, decoding, and navigation tasks. Never cancel or abandon an in-progress committed-stock operation without resolving its durable outcome.
- Persist stock mutations immediately and atomically; never delay saving sales merely to batch writes for power savings. Batch only noncritical work such as bulk export and thumbnail generation.
- Backups and large exports are user-triggered. If an optional deferred task is added, use Android's system-managed scheduling with suitable constraints rather than frequent alarms. No periodic heavy background job is required for this app.
- Display only visible list rows, cache a bounded number of small thumbnails, and avoid logging every frame or transaction payload in release builds. Avoid unnecessary allocations and repeated database initialization.
- Respect Battery Saver and low-memory callbacks. Reduce optional motion, trim caches, lower decoding demand, and postpone nonessential bulk work without compromising transaction correctness. Do not force higher brightness or a higher screen refresh rate.

#### Thermal handling

- Observe Android's supported thermal-status callbacks through PowerManager, guarded by API availability. Do not depend on unavailable sensors or frequent polling. On older devices, retain the conservative limits above.
- At moderate thermal stress, reduce camera decoding workload and optional animation; suspend optional background/bulk processing.
- At severe or greater thermal stress, finish/resolve any active stock commit safely, release camera/torch, and offer **Phone is warm. Use product search**. Manual sale/return remains available where the OS permits. Stop optional exports and resume them safely later.
- Resume normal scanning only after thermal status improves, with hysteresis to prevent repeated switching. Resume camera through user action rather than silently reopening it.
- Use device thermal status rather than inventing one universal temperature cutoff. Never bypass Android's thermal or power restrictions or instruct users to disable battery protection.

#### Required power and heat validation

Use release builds on at least a representative low-cost phone and a midrange phone. Record model, Android version, battery condition, ambient temperature, brightness, refresh rate, charging state, dataset, and workload. Repeat controlled runs; do not infer app efficiency from a single battery-percentage reading.

| Workload | What must be measured / checked |
|---|---|
| 30-minute stock browsing and manual changes | CPU activity, energy/battery trend, frame timing, memory; no constant idle workload |
| 30-minute scan sessions with realistic pauses | Decode/preview time, torch time, power consumption and thermal status; camera stops between scans |
| 30-minute continuous-scanning stress test | Adaptive thermal response, sustained accuracy, frame timing, recovery and manual fallback |
| 60-minute background/screen-off idle | No app camera/torch, recurring refresh job, app-held wake lock, or sustained CPU loop; energy consistent with idle baseline within repeatable measurement variation |
| 8-hour capacity session | Energy and thermal trend alongside existing stock correctness, latency, and memory checks; no resource leak over time |
| Battery Saver, low memory, and thermal-status changes | Optional work reduced, safe camera pause/recovery, no lost or duplicate transactions |
| Cancel export, rotate, background, or lock phone | Resources released; partial exports cleaned up; transaction outcomes recoverable |

Use Android's profiling/trace and battery-stat tools available on the test devices. Compare optimized scanning with a controlled baseline using the same hardware, brightness, preview configuration, and label workload. Before release, publish measured consumption and establish device-specific acceptable budgets from those measurements; investigate any repeatable power regression. No universal percentage-per-hour or temperature guarantee is justified without those results.

Primary guidance: [Android Thermal API](https://developer.android.com/games/optimize/adpf/thermal), [Excessive partial wake locks](https://developer.android.com/topic/performance/vitals/excessive-wakelock).

### Capacity and daily use

Support **20,000 product records**, including a worst-case benchmark with **100,000 variants** across sizes/sleeves and **1,000,000 historic movements**. Also benchmark the simpler 20,000-variant case. A large catalog must not require loading every product, barcode, image, or transaction into memory.

- Use native Kotlin, Jetpack Compose, Room/SQLite, CameraX, and bundled ZXing; this remains an offline implementation with no paid services.
- Page database-backed product, variant, and history lists. Load expanded size breakdowns only for the selected product.
- Keep barcode and variant lookups indexed; index return eligibility and history queries by variant and date. Search through indexed queries (including a suitable local full-text index), not by scanning a full in-memory catalog.
- Stock changes update one variant and the needed grouped summaries in bounded transactions. Never recalculate the whole movement history for every tap or scan. Retain the full ledger for reconciliation, performed outside the interactive path.
- Use bounded image/thumbnail caches and thumbnail decoding. Generate labels on demand; do not pre-render codes for the entire catalog.
- Keep UI state stable and prevent unrelated rows from recomposing on a single stock change. Throttle search input while ensuring the last query wins.
- Backups, bulk label generation, and reconciliation run away from the main thread in bounded batches. Maintain a consistent backup snapshot without long write locks; verify concurrent sales remain responsive or present a brief explicit safe pause.
- Show correct persisted counts before success. Fast visual feedback must never fabricate a completed sale.


- Run database work, image decoding, label generation, and file operations away from the main UI thread.
- Use CameraX lifecycle handling; release camera resources when leaving scanning.
- Process at most one analysis frame at a time, discard stale queued frames, and always close every camera frame even after errors.
- Pause scanning during mutations and while showing results. Cancel obsolete camera callbacks after navigation.
- Use stable list item keys, indexed barcode lookup, and paged/lazy stock lists.
- Save form input and operation state across rotation and process recreation. Show Unsaved changes confirmation when leaving a changed form.
- Use short progress messages. If a save takes longer than expected, keep its result unresolved until checked; a timeout must not imply that no stock change happened.
- Low storage or write failure: explain **Couldn't save. Check storage and try again.** Do not show green success. Confirm the operation's database result before a retry.
- Permission denial: explain why the camera is needed and offer product search. If permanently denied, offer **Open settings**.
- Camera unavailable/in use: offer retry and manual search. Torch is shown only when supported.
- No internet must not block any main workflow.

Performance acceptance targets, to be measured on a named representative low-cost physical Android phone:

| Action | Target |
|---|---|
| Cold launch through C Nanjappa Cloth Center to usable home | Within 3 seconds total, including brief branding |
| Scanner ready after tapping Sell | Within 2 seconds |
| Clear supported label in focused active scanner → green result | p50 ≤ 300 ms; p95 ≤ 600 ms over at least 200 varied labels; misses/timeouts reported |
| Stock search at both 20,000 and 100,000 variants | p95 results within 500 ms after a 150 ms search debounce |
| Normal known-barcode sale: decoded code → commit and visible green result | p95 ≤ 100 ms, measured separately from camera decoding |
| Return/restock database operation at capacity | p95 commit within 200 ms |
| List scrolling and navigation at capacity | At least 95% frames within the device refresh deadline (16.7 ms at 60 Hz); no frozen UI frames over 700 ms |
| Continuous use | No crash, ANR, duplicate stock change, or unbounded memory growth in an 8-hour working-day test |

These are release targets, not a promise that all cameras or damaged labels decode instantly. On poor labels, keep the preview responsive and provide search.

## 12. Backup and restore

Provide a simple **Back up data** button and **Restore backup** in the menu. Show the last successful backup time and a gentle daily reminder when changes have occurred since the last backup.

- Export a consistent versioned backup containing variants, barcode aliases, sales, returns, operation outcomes, and movement history. CSV stock export is optional and is not a complete backup.
- Save using Android's file picker so the user can choose a location outside app-private storage. Complete the file atomically and verify it before marking the backup successful.
- Explain plainly: **Keep a backup outside this phone. Uninstalling the app or losing the phone can lose local data.**
- Restore validates format, checksum, required fields, uniqueness, stock reconciliation, and schema compatibility before changing current data.
- Show backup date and item count, warn that restore replaces current data, and create a safety backup before replacement. If safety backup fails, do not replace current data.
- Restore is all-or-nothing. Invalid or interrupted restore leaves the existing inventory usable.
- Reprinted labels must work after restore because variant and catalog identifiers remain unchanged.
- Do not merge backups or imply that separate phones are synchronized.

## 13. Free implementation approach

Recommended native Android implementation:

| Need | Choice |
|---|---|
| Android app and UI | Kotlin with Jetpack Compose / Material components |
| Camera preview and image analysis | CameraX |
| Offline barcode decoding and QR/Code 128 generation | ZXing core bundled inside the app |
| Local inventory and transactional history | Room over SQLite |
| Labels and backup files | Android PDF, file picker, share, and print APIs |
| Verification | Local unit tests, Room integration tests, Android UI tests, physical-device checks |

Use compatible supported releases and pin dependency versions at implementation time. Keep required license notices. ZXing's own repository lists QR Code and Code 128 support and uses the Apache 2.0 license. Room is Android's local SQLite abstraction; CameraX supports lifecycle-aware preview and analysis.

All required software components must be free to use, with no paid scanner SDK, cloud database, AI API, subscription, usage billing, premium feature lock, or mandatory account. All inventory features must work locally without depending on a limited free trial or cloud free-tier quota. Provide a signed APK for direct installation to keep software distribution independent of a store. Printing paper, stickers, a printer, and a phone are physical costs; they are not free software features.

Primary implementation references:

- [Room documentation](https://developer.android.com/training/data-storage/room)
- [CameraX architecture](https://developer.android.com/media/camera/camerax/architecture)
- [ZXing formats and source](https://github.com/zxing/zxing)
- [ZXing license](https://github.com/zxing/zxing/blob/master/LICENSE)

## 14. Required test cases

Implement automated data-layer tests for stock invariants and Android UI/instrumented tests for user flows. Camera quality, printed labels, permissions, and performance also need real-device testing.

| ID | Scenario | Expected result |
|---|---|---|
| P01 | Save with missing name, model, size, or sleeves | Short inline field hint; no product created; values retained |
| P02 | Add Full 40 and Half 40 variants | Separate codes and quantities |
| P03 | Add size 40 and 42 for the same model | Separate stocks; correct grouped total |
| P04 | Enter negative, fractional, blank, or overflowing quantity | Rejected without stock changes |
| P05 | Save duplicate identity with changed case/spaces | Existing variant offered; no duplicate |
| P06 | Bind a code already used by another variant/company | No overwrite; explicit ambiguous-code registration and manual selection required |
| BR01 | Scan linked Ramraj, Udyam and Puma labels consecutively | Correct company/variant resolved; no brand/format mode switch |
| BR02 | Same model/size/sleeve/colour across companies | Separate variants and stock totals |
| BR03 | EAN and QR aliases linked to one variant | Same stock count updated once |
| BR04 | Same exact lookup key appears across companies | Explicit candidate selection; no automatic wrong-brand deduction |
| BR05 | Scan company homepage/promotional/payment QR | No automatic product sale; manual product lookup available |
| BR06 | Mixed-format offline benchmark at full catalog capacity | Actual supported labels meet recognition/latency targets without per-brand setup |
| P07 | Existing barcode begins with zero | Exact lookup succeeds; zero preserved |
| P08 | Edit product spelling | Old labels work; old sale descriptions preserved |
| P09 | Edit into another existing variant identity | Blocked; no accidental merge |
| S01 | Scan Full label with stock 4 | Full becomes 3; Half unchanged; green result |
| S02 | Same code appears in 100 consecutive camera frames | Exactly one sale and one deduction |
| S03 | Tap Scan next while old label remains visible | No second sale until label leaves and is scanned again |
| S04 | Remove and rescan identical label intentionally | One additional sale |
| S05 | Scan item with zero stock | No pieces available message; no sale or negative quantity |
| S06 | Two concurrent attempts sell last remaining piece | One succeeds, one fails; final stock 0 |
| S07 | Replay the same operation ID | Existing result returned; no additional movement |
| S08 | Unknown, malformed, or another shop's code | No stock change; product-not-found guidance |
| S09 | Two different codes detected inside the scan area | Ask for one label; no sale |
| S10 | Manual Sell 1 | Same validation and transaction rules as scanning |
| S11 | Sale database write fails | No partial sale/movement; no green success |
| S12 | Kill app before commit and reopen | No partial mutation; recovered state checked before retry |
| S13 | Kill app after commit but before success display | Sale remains once; result recovered; no rescan deduction |
| S14 | Rotate or background scanner during callback | No duplicate deduction; camera lifecycle recovers |
| S15 | Undo sale, then tap Undo again | Stock restored once; second attempt blocked |
| SPD01 | 200 varied clean labels on focused scanner at full dataset capacity | End-to-end p50/p95 and first-pass recognition meet targets; no skipped misses |
| SPD02 | Barcode decoded while thumbnails/export/analytics exist | Green result does not wait for optional work |
| SPD03 | Slow/failed commit despite fast barcode detection | No premature green, haptic success, or false reduced count |
| SPD04 | Next-item scans and label held still | Prompt deliberate next scan; exactly one deduction per intentional operation |
| SPD05 | Glare, blur, damaged label, thermal stress | Accurate safe fallback; no guessed identity or bypassed thermal controls |
| R01 | Return one good item linked to a sale | Stock +1; returnable amount -1 |
| R02 | Return damaged sold item | Stock unchanged; return eligibility consumed |
| R03 | Return more than original remaining sold amount | Rejected; stock/history unchanged |
| R04 | Double-tap Return 1 or replay its operation | Exactly one return |
| R05 | Return without matching sale | No return; separate correction option shown |
| R06 | Select sale for a different variant | Rejected |
| R07 | Partly return sale, then Undo remainder | Only outstanding quantity restored |
| R08 | Return a fully reversed sale | Rejected |
| R09 | Two simultaneous returns against one remaining piece | Only one succeeds |
| R10 | Return archived variant | Linked return recorded; restocked pieces visible |
| A01 | Add 5 pieces to quantity 3 | Quantity 8; one restock movement |
| A02 | Correct quantity from 8 to 6 with reason | Quantity 6; adjustment -2 |
| A03 | Correct stock without reason | Rejected |
| A04 | Replay restock/correction operation | No duplicate stock change |
| L01 | Save variant and generate QR plus Code 128 | Both resolve to exact same variant |
| L02 | Generate/reprint 20 labels | Stock unchanged; identity unchanged |
| L03 | Scan exported and printed labels | Correct resolution for every supported format |
| L04 | Save/share/print cancelled or fails | No stock change; retry possible |
| LP01 | Add 4 identical shirts | One variant code; PDF contains exactly 4 identical labels |
| LP02 | Add 4 Full and 4 Half shirts | 8 labels with two correct distinct variant codes |
| LP03 | Restock 3 into existing quantity 4 | Stock becomes 7; new-stock label count defaults to 3 |
| LP04 | PDF generation fails after product commit and user retries | Existing product retained; PDF retried; stock not doubled |
| LP05 | Reprint replacement or current-stock labels | Correct explicit copy count; no inventory change |
| LP06 | No compatible printer or user cancels system print | Local PDF fallback works offline; no false printed status |
| LP07 | Print a multi-page barcode batch on calibrated sticker paper | Exact count, correct alignment, readable bars, every sampled label resolves correctly |
| LP08 | Generate large batch and cancel midway | Responsive UI, bounded memory, no misleading partial PDF or inventory mutation |
| LP09 | Add opening stock zero | Variant saves; no empty automatic PDF; later label action works |
| V01 | Full 4, Half 4 | Card shows Full 4, Half 4, Total 8 |
| V02 | Sell one Full | Card updates to Full 3, Half 4, Total 7 |
| V03 | Several sizes exist | Sleeve summaries sum sizes; expanded rows match totals |
| V04 | Quantity reaches 2 and then 0 | Ordinary count updates; no low-stock/out-of-stock UI; sale at zero blocked |
| V06 | Tap Stock overview on Home | Generates complete .xlsx report and offers Save Excel; Stock tab remains unchanged |
| V05 | Sale near local midnight | Today's activity uses shop timezone consistently |
| LCH01 | Tap app icon from a cold start | C Nanjappa Cloth Center appears, then Home opens automatically within startup budget |
| LCH02 | Return to app from background while selling or editing | Current screen and state retained; branding does not replay |
| LCH03 | Rotate during launch or disable system animations | No duplicate startup work, restarted delay, flicker, or navigation loop |
| LCH04 | Launch with large text, small screen, offline, or slow local initialization | Full shop name readable; responsive status if needed; no login/network dependency |
| E01 | Camera permission denied/permanently denied | Search works; appropriate camera/settings guidance |
| E02 | Poor light, blurry/damaged code, or unavailable camera | Responsive preview or readable recovery; manual search available |
| E03 | Clean APK installation and first launch with all radios disabled | Every core flow works with bundled assets; no model download, login, activation, or network error |
| OFF01 | Inspect final release merged manifest | No INTERNET or ACCESS_NETWORK_STATE permission, including from dependencies |
| OFF02 | Offline export, restart, and local backup restore | Labels, full stock/history, and identifiers preserved without cloud access |
| OFF03 | Device without Google account or usable Play services | Scanner and every core inventory function work |
| OFF04 | Cancel optional external share/print or lack connectivity | Local label export and inventory remain available; no stock change |
| XLS01 | Stock overview with mixed sizes, sleeves, zero-stock and archived variants | All variants exported; totals reconcile; Stock tab unchanged |
| XLS02 | Several barcode aliases and leading-zero codes | No stock double counting; literal code text preserved |
| XLS03 | Sell/return while exporting | Workbook uses one consistent As of snapshot; app remains responsive |
| XLS04 | Airplane mode and no spreadsheet viewer installed | XLSX generation/save succeeds; clear local-file fallback |
| XLS05 | Cancel or fail large Excel export | No false saved message, partial file, stock change, or unbounded memory |
| XLS06 | Movement history exceeds worksheet row limit | Additional movement sheets created; no silent omission |
| XLS07 | User-entered text resembles a formula | Exported as literal text; no formula execution |
| XLS08 | Open workbook in compatible spreadsheet software | Worksheets readable; numeric totals, dates, text IDs and formatting correct |
| E04 | Keyboard open, small screen, large system text | Fields/buttons usable; no clipping |
| E05 | Tap text/icon/edge of main button | Entire intended button activates once |
| E06 | Unsaved form followed by rotation/back navigation | Rotation retains values; leaving asks before discarding |
| U01 | Select sleeve, colour, and standard size | Each selection changes with one tap; no keyboard needed |
| C01 | Open colour choices after many saved/custom colours | White then Cream always first; others below |
| C02 | Type Olive through + More and save product | Olive selected and saved for future tapping |
| C03 | Type white with different case/spaces | Existing White reused; no duplicate colour |
| C04 | Cancel custom colour sheet or unsaved form | No unwanted saved colour created |
| C05 | Save with neither sleeve selected | Choose Full or Half inline; no default or Not applicable |
| C06 | Trigger validation, camera, lookup, and storage errors | Brief accurate messages, preserved input, working recovery action; no technical error dumps |
| Z01 | Open default size list | S, M, L, XL, XXL, XXXL and every number 36–50 available |
| Z05 | Open Age sizes | All listed yearly ranges from 0–1 to 17–18 plus 18–20 years available by tapping |
| Z06 | Save 2-3 and 2–3 years for the same identity | Same canonical age size; duplicate variant prevented; numeric and letter sizes remain separate |
| Z07 | Sell, return, restock, search/filter, export, and restore age-size variants | Correct independent quantities and barcode mappings; readable age labels retained end to end |
| Z02 | Add size 32 or 52 and save a variant | Accepted; saved size is tappable next time |
| Z03 | Use L then Large for same product identity | Same canonical size; duplicate variant prevented |
| Z04 | Use XXL/2XL/Double XL or XXXL/3XL/Triple XL | Matching aliases resolve to the same respective size |
| Z05 | Add L and numeric 40 | Separate variants; no guessed conversion |
| U02 | Choose previously saved company/name/model | One-tap selection; typing only through Add new |
| U03 | Tap quantity +/− | Draft number changes immediately; minus cannot pass zero; stock unchanged until action |
| U04 | Return a good item with several eligible sales | Return 1 works without history selection; newest eligible sale linked once |
| U05 | Tap Sell 1 and then Undo | Stock −1 then +1 exactly once; visible feedback each time |
| U06 | Tap a choice while no mutation is intended | Selection changes only; no inventory movement |
| U07 | First-time user completes sell, return, and add-stock flows | No typing needed for existing products; labels understood without coaching |
| B01 | Backup and restore on a clean installation | Stocks, history, identities, and labels preserved |
| B02 | Restore invalid/truncated/incompatible backup | Rejected; current data unchanged |
| B03 | Interrupt backup or restore | No false success or partly restored inventory |
| B04 | Safety backup fails before replacement | Restore does not replace current data |
| B05 | Upgrade app with existing inventory | Migration preserves counts/history; existing labels work |
| F01 | 20,000 products, up to 100,000 variants, and 1,000,000 movements | Paged lists, indexed lookup, and search meet measured targets; catalog not loaded fully into memory |
| F02 | 8-hour working-day session with 20,000 alternating sale/return/restock operations and periodic navigation/search | No crash, ANR, duplicate operation, or unbounded memory growth; counts reconcile |
| F04 | Scroll and navigate in release build at capacity on a named low-cost physical phone | Measured frame timings meet target; no frozen frames; retained scroll position |
| F05 | Run backup and label export while normal stock actions occur | No UI blocking or partial files; correct snapshot and committed counts |
| F06 | System animations disabled or scaled | Actions still work correctly and promptly; motion follows system settings |
| POW01 | Successful scan, leaving scanner, background, or screen lock | Camera/analysis/torch released; no unintended ongoing capture |
| POW02 | Scanner unused for 30 seconds | Camera pauses; Tap to scan again resumes safely |
| POW03 | App idle or background for an hour | No recurring polling, sustained CPU work, app-held wake lock, camera, or torch |
| POW04 | Battery Saver or low-memory signal | Optional work reduced and caches trimmed; correct stock writes maintained |
| POW05 | Simulated thermal stress plus real sustained-scanning test | Workload reduces; severe status stops camera with manual fallback; safe recovery |
| POW06 | Power profiling on low-cost and midrange phones | Controlled results recorded and release budgets reviewed; no repeatable unexplained regression |
| POW07 | Camera pause or task cancellation during mutation | Commit outcome resolved; no duplicate deduction or lost sale |
| F03 | Random sequences of sales/returns/undo/restocks/corrections | Nonnegative stock; movements reconcile; return limits always hold |

## 15. Definition of done

- A nontechnical shop user can sell, return, add stock, and view quantities using large labelled buttons without typing for existing products. Common form choices work by one tap. A short first-use introduction is sufficient.
- Green sale success appears only after a real committed deduction.
- Holding a barcode in front of the camera never repeatedly reduces stock.
- Full, Half, sizes, models, and colours remain accurately separated.
- Return limits, operation deduplication, and stock reconciliation are enforced in the database layer and covered by executed tests.
- Core workflows and code generation work completely offline.
- Battery, thermal, idle-resource, and camera-release tests pass on physical phones. Report measured power use and device-specific budgets; do not promise zero drain or zero battery aging.
- Backup/restore and migration checks pass before real shop inventory is entered.
- Release notes include executed test results, named physical test devices, release-build frame/latency measurements, and 8-hour capacity test results. Smoothness is a measured release requirement, not a guarantee inferred from choosing a stack. Resolve critical failures before delivery.
- Deliver a signed installable APK, source code, dependency/license notices, automated tests, and a short shop-user guide.

Keep the first version focused on inventory. Billing, tax, customer accounts, payment integrations, employee roles, multi-shop synchronization, and complex charts are not required.
