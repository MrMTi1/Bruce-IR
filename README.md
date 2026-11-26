BruceIR By MTiBruceIR By MTi is a professional infrared (IR) signal manager and emitter designed for Android devices. This application is specifically crafted to bridge the gap between dedicated hardware like the Flipper Zero (using Bruce Firmware) and your smartphone, allowing you to use your phone as a high-powered, customizable universal remote.🚀 Key Features•Flipper Zero / Bruce IR Compatibility: Directly import and parse .ir files. The app understands the Bruce/Flipper file structure, converting names, frequencies, and raw data into functional touch buttons.•Advanced Folder Management: Organize your remotes into nested catalogs. Whether you have 10 devices or 100, you can keep them sorted in dedicated directories (e.g., "Living Room," "Office," "TV Setup").•Intuitive Drag & Drop: Customize your layout exactly how you want it. In edit mode, simply hold and move buttons to reorder them for better ergonomics.•Secure Edit Mode: Features a "Safety Lock" toggle. When locked, the app acts as a standard remote, preventing accidental button moves or deletions during daily use. Unlock it to edit names, modify RAW data, or reorder keys.•Raw Data Editor: View and modify the underlying IR pulse patterns and carrier frequencies directly within the app.•Persistent Storage: Your library is automatically saved to your device. Close the app or restart your phone—your remotes and folder structures remain intact.•Modern & Responsive UI: A clean, Material Design interface featuring a professional Splash Screen and a clutter-free layout for fast operation.🛠 Technical Details•File Support: Parses standard .ir text files.•Signal Type: Supports RAW signals (Pulse/Space sequences).•Data Conversion: Automatically handles negative signal offsets and timing normalization to ensure maximum hardware compatibility.•Hardware Requirements: Requires an Android device with a built-in Infrared Emitter (IR Blaster).👨‍💻 CreditsDeveloped by MTi. This project was created to provide a convenient mobile alternative for users of the Flipper Zero ecosystem and Bruce Firmware, offering a more portable way to manage and deploy IR command libraries.How to use?1.Import: Click the + button and select "Import Bruce IR file".2.Organize: Create folders to group your devices.3.Customize: Open the "Lock" icon to enable Drag & Drop or Edit commands.4.Transmit: Close the Lock and tap any button to send the IR signal!Disclaimer: This application is intended for legal use with devices you own or have permission to control.
## Folder layout and Bruce Bluetooth

Open **Management → Folder layout and icon size** to choose a row or grid layout, set grid columns (2–4), and resize icons (24–56 dp). These preferences are saved on the phone.

For Bruce firmware IR capture over Bluetooth, enable **Config → System Config → Advanced → Toggle BLE API** on the device, then use **Management → LILYGO / BRUCE → LIVE CAPTURE (Bluetooth)** in the app.

IR capture is also available over Wi-Fi as **LIVE CAPTURE (Wi-Fi)**. Enable Bruce WebUI, connect the phone and LilyGO to the same network, and configure the WebUI address and credentials in the app. Bluetooth capture remains the option that does not require LilyGO Wi-Fi.

For Sub-GHz work, open **REMOTE CONSOLE (Mirror) → MODULES → Sub-GHz / RF**. Bruce performs the receive using its configured RF hardware; BruceIR shows decoded protocol and signal fields, and lets you inspect, edit, copy, or save the capture as a `.sub` file on the phone. Use **FILE TRANSFER (Wi-Fi)** to upload an edited `.sub` file back to Bruce when Wi-Fi is available. Rolling-code serial/counter fields are shown when Bruce's decoder recognizes them; editing a capture does not enroll a new remote or generate a valid rolling-code pairing sequence.

The toolbar screen icon opens the **REMOTE CONSOLE (Mirror)**, which connects over BLE and can mirror Bruce's screen, send menu navigation commands, and request screen images without using device Wi-Fi. Its **FILES** button provides basic file browsing over the same connection: list folders, preview supported text files, create folders, and delete files or empty folders. It uses Bruce's currently selected storage (SD when mounted, otherwise LittleFS). This is not a complete BLE replacement for WebUI: file editing, uploads, binary downloads, and the rest of WebUI still require Wi-Fi. The separate **BRUCE WEB UI** option remains available from **Management → LILYGO / BRUCE** when Wi-Fi is enabled.

In Bruce firmware, BLE API can be toggled at **Config → System Config → Toggle BLE API**. On the phone, tap the toolbar view-mode button to switch between grid and rows, or long-press it to open the layout and icon size controls. The same settings are also under **Management → Folder layout and icon size**.

## New radio & network modules (v2.6)

- **Aggressive Wi-Fi Scanner (live)** — `AggressiveWifiScanActivity`: continuous chained scans (interval 1/2/5/10 s), per-BSSID RSSI history with sparkline and trend, channel-occupancy summary, security risk flags (open, WEP, WPS, TKIP, hidden SSID, duplicated SSID), filters, CSV/JSON export.
- **BLE API Explorer (GATT)** — `BleApiExplorerActivity`: low-latency BLE scan with a full advertising-payload parser (flags, service UUIDs, service/manufacturer data, TX power, appearance), RSSI history, plus a GATT client: service/characteristic/descriptor tree, read, write (hex/text), notify/indicate, notification log and JSON export.
- **Advanced LAN Scanner** — `AdvancedNetworkScannerActivity`: auto subnet or custom CIDR, host discovery with ARP enrichment, port presets and custom ranges, service banner fingerprinting, MAC OUI vendor lookup with device-type heuristics, deep scan, CSV/JSON export.

All three modules are registered in the manifest and available from the Field Workbench and from **Management → BruceIR tools / Wi-Fi Audit**. They are gated by the active app profile.

## App profile codes (module gate)

The Field Workbench asks for an activation code on first run. Codes:

| Code | Profile |
| --- | --- |
| `1111` | Electrician and measurements only |
| `3333` | IR remotes only |
| `2222` | Everything except electrician tools |
| `7777` | Full access |

A wrong code never grants access: the dialog stays locked, shows the remaining attempts, and after 5 failures the gate is blocked for 60 seconds. `7777` (full access) must be entered explicitly. The profile can be changed or reset in **Settings → PROFIL APLIKACJI** (`WorkbenchSettingsActivity`); resetting returns the app to the code gate. Every gated module activity also re-checks the profile on entry, so the gate cannot be bypassed by launching an activity from another menu.

## IR library menu and Flipper IRDB import (v2.6.1)

The pilot/folder lists inside MainActivity (IR remotes → PILOTY, Flipper categories, device folders) use the same card style as the app main menu: uniform dark cards, accent icon, title, item count and chevron.

The built-in Flipper IRDB is shipped gzip-compressed as `app/src/main/assets/flipper_irdb.bin` (an asset ending in `.gz` would be decompressed by AGP and bloat the APK by ~65 MB, and then `AssetManager.open("....gz")` fails at runtime). It is loaded lazily into the managed `🐬 Flipper IRDB` folder:

- first open in a session parses the database (progress dialog, cancellable); later opens are instant (in-process cache),
- the loaded tree is intentionally **not** written into `bruce_db.json` (avoids tens of MB on every save) — only the managed folder is skipped on save/load, user folders named e.g. "Flipper test" are kept,
- **Management → Import Flipper IR (.ir / DB)** offers: built-in DB, own Flipper DB file (`.json`/`.json.gz`) and an `.ir` file; errors show *retry* and *pick file* actions,
- importing a full-library JSON appends it as a new folder instead of overwriting the user library,
- the raw upstream `Flipper-IRDB-main/*.ir` tree is kept outside the APK in `ir_db_raw/` (used to generate the compressed DB asset).


