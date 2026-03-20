# PalmGrade RNS

**Android application for oil palm bunch quality checking over Reticulum Network Stack (RNS)**

Designed for offline field use. Harvesters grade bunch quality, capture GPS-tagged proof photos, and transmit grading records via [LXMF](https://github.com/markqvist/LXMF) over a [Reticulum](https://github.com/markqvist/Reticulum) network through a connected [RNode](https://github.com/markqvist/rnodeconfigutil) LoRa radio device.

---

## System Overview

```
Android Phone
  ├── PalmGrade RNS app
  │     ├── GPS (proof of location)
  │     ├── Camera (proof of harvest)
  │     └── SQLite (local record store)
  │
  └── Bluetooth Classic (SPP/RFCOMM)
        │
        ▼
      RNode LoRa Radio
        │  433.025 MHz
        ▼
      RNS Mesh Network
        │
        ▼
      Receiver Node  ←→  LXMF destination (office / base station)

  End of day:
  Android ──WiFi──► office_receiver.py  (ZIP with CSV + photos)
```

---

## Features

| Feature | Detail |
|---|---|
| Grading categories | Ripe, Unripe, Overripe, Empty, Damaged, Rotten |
| Input method | SeekBar + manual numeric input per category |
| GPS capture | Auto, GPS-only (no cellular), accuracy shown |
| Photo capture | Camera intent, compressed JPEG ~300 KB, GPS EXIF tagged |
| Transmission | LXMF over RNS via RNode Bluetooth Classic |
| Auto-announce | Every 10 minutes (configurable) |
| TX retry | Background worker retries unsent records every 2 min (max 5 attempts) |
| Local storage | Room/SQLite, survives app restarts |
| Export | ZIP: CSV + geotagged photos + SHA-256 manifest |
| WiFi transfer | HTTP POST to office server at end of shift |

---

## Project Structure

```
PalmGradeRNS/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/palmgrade/rns/
│   │   ├── PalmGradeApp.java              Application class
│   │   ├── bluetooth/
│   │   │   └── RNodeBluetoothManager.java KISS-framed SPP connection
│   │   ├── rns/
│   │   │   ├── RnsIdentity.java           Key generation, address derivation
│   │   │   └── RnsService.java            Foreground service, announce timer
│   │   ├── grading/
│   │   │   ├── BunchRecord.java           Data model + CSV serialisation
│   │   │   ├── GpsLocationProvider.java   GPS-only location
│   │   │   └── PhotoManager.java          Camera + EXIF geotagging
│   │   ├── storage/
│   │   │   ├── AppDatabase.java           Room database
│   │   │   ├── BunchRecordDao.java        DAO (queries)
│   │   │   └── BunchRecordRepository.java Repository (thread management)
│   │   ├── export/
│   │   │   └── ExportManager.java         ZIP builder + WiFi transfer
│   │   ├── util/
│   │   │   └── TxRetryWorker.java         Background TX retry scheduler
│   │   └── ui/
│   │       ├── MainActivity.java          Host activity (ViewPager2 tabs)
│   │       ├── MainPagerAdapter.java
│   │       ├── connection/ConnectionFragment.java
│   │       ├── grading/GradingFragment.java
│   │       ├── history/HistoryFragment.java
│   │       └── export/ExportFragment.java
│   └── res/
│       ├── layout/                        7 layout XML files
│       ├── drawable/                      20 vector icons + shape backgrounds
│       ├── values/                        colors, strings, themes, dimens
│       └── xml/                           file_paths, network_security_config
└── scripts/
    └── office_receiver.py                 Flask upload server
```

---

## Build Instructions

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 34 (compile), SDK 26 minimum (Android 8.0)
- JDK 17
- Physical Android device (Bluetooth Classic not available in emulator)

### Steps

```bash
# 1. Clone / copy the project
cd PalmGradeRNS

# 2. Open in Android Studio
#    File → Open → select PalmGradeRNS/

# 3. Sync Gradle
#    Android Studio will prompt automatically

# 4. Build debug APK
./gradlew assembleDebug

# APK location: app/build/outputs/apk/debug/app-debug.apk

# 5. Install to connected device
./gradlew installDebug
```

### Dependencies (app/build.gradle)

| Library | Version | Purpose |
|---|---|---|
| `androidx.appcompat` | 1.7.0 | Base activity/fragment |
| `material` | 1.12.0 | Tabs, buttons, dialogs |
| `viewpager2` | 1.1.0 | Tab pager |
| `room-runtime` | 2.6.1 | SQLite ORM |
| `exifinterface` | 1.3.7 | GPS EXIF writing |
| `lifecycle-livedata` | 2.8.4 | LiveData observers |

---

## RNS Identity

The app follows Reticulum's identity model, matching Sideband:

```
enc_private_key  (32 bytes, X25519)  ──► enc_public_key  (32 bytes)
sign_private_key (32 bytes, Ed25519) ──► sign_public_key (32 bytes)

combined_public_key = enc_pub || sign_pub           (64 bytes)
identity_hash       = SHA-256(combined_pub)[0:16]   (32 hex chars)
lxmf_address        = SHA-256(identity_hash || SHA-256("lxmf.delivery")[0:10])[0:16]
                                                     (32 hex chars)
```

Both `identity_hash` and `lxmf_address` are **read-only derived values** — they cannot be set by the user and are always recomputed from the stored private key seeds on every app launch.

> **Production note:** The scalar multiplication in `RnsIdentity.publicKeyFromPrivate()` is currently stubbed with SHA-256. Replace with real Curve25519/Ed25519 using BouncyCastle or lazysodium-android before deployment:
>
> ```groovy
> // app/build.gradle
> implementation 'org.bouncycastle:bcprov-jdk15on:1.70'
> ```

---

## RNode Configuration

| Parameter | Value | Notes |
|---|---|---|
| Frequency | 433.025 MHz | Fixed, Malaysian 433 MHz ISM band |
| Bandwidth | 125 kHz (default) | Selectable: 125 / 62.5 / 31.25 / 15.625 kHz |
| Spreading Factor | 9 (default) | Range: 7–12 |
| Coding Rate | 5 (default) | Range: 5–8 (4/5 to 4/8) |
| Protocol | KISS over SPP Bluetooth Classic | UUID: `00001101-0000-1000-8000-00805F9B34FB` |

---

## LXMF Message Format

Each grading submission is sent as an LXMF message:

- **Title:** `GRADE/<block_id>/<uuid_prefix>`  
  e.g. `GRADE/BLK-0042/a1b2c3d4`
- **Content:** CSV line  
  `uuid,timestamp,block_id,harvester,lat,lon,ripe,unripe,overripe,empty,damaged,rotten`

---

## Office Receiver

Run `scripts/office_receiver.py` on the office PC when harvesters return:

```bash
# Install dependency
pip install flask

# Start server (binds to all interfaces on port 8080)
python3 scripts/office_receiver.py

# Custom port and output directory
python3 scripts/office_receiver.py --port 8080 --output /data/palmgrade/uploads
```

The Android app's Export tab sends to `http://<ip>:8080/upload`. The server:
1. Saves the ZIP with a timestamp prefix
2. Extracts to a subfolder
3. Parses `manifest.json` and verifies SHA-256 checksums for every photo
4. Prints a CSV preview and record summary
5. Returns `HTTP 200` JSON so the app can confirm success

---

## Data Export ZIP Structure

```
palmgrade_2026-03-19.zip
├── grading_2026-03-19.csv    ← all day's records, one row per submission
├── photos/
│   ├── PALM_20260319_081400.jpg   ← ~300 KB, GPS EXIF tagged
│   ├── PALM_20260319_090212.jpg
│   └── …
└── manifest.json              ← checksums + identity + record metadata
```

### manifest.json schema
```json
{
  "date": "2026-03-19",
  "generated_at": "2026-03-19T17:00:00",
  "record_count": 7,
  "photo_count": 7,
  "harvester_rns": "b2e48f1a903c6d27e5a0f841c39d5b82",
  "app_version": "1.0.0",
  "records": [
    {
      "uuid": "550e8400-e29b-41d4-a716-446655440000",
      "block_id": "BLK-0042",
      "timestamp": "19 Mar 2026 08:14",
      "photo_file": "photos/PALM_20260319_081400.jpg",
      "photo_sha256": "a3f19c2d…",
      "csv_sha256": "b4e28d1f…",
      "transmitted": true
    }
  ]
}
```

---

## Permissions

| Permission | Reason |
|---|---|
| `BLUETOOTH_CONNECT` / `BLUETOOTH` | Connect to RNode |
| `BLUETOOTH_SCAN` | Discover BT devices |
| `ACCESS_FINE_LOCATION` | GPS fix for records |
| `CAMERA` | Proof photo capture |
| `FOREGROUND_SERVICE` | Keep RNS connection alive |
| `ACCESS_WIFI_STATE` | WiFi export |
| `INTERNET` | WiFi transfer to office server (LAN only) |

---

## Offline Operation

The app is fully self-contained for field use:

- No cellular required — all transmission over LoRa RNS
- No Play Services required — system fonts only, no downloadable fonts
- GPS uses hardware GPS only (`LocationManager.GPS_PROVIDER`)
- WiFi transfer is LAN-only; network security config blocks external cleartext
- SQLite database persists all records across reboots
- TX retry worker ensures records sent when RNode reconnects

---

## Licence

Internal use. See your organisation's software licence terms.

---

## GitHub — Push & Build via CLI

### Initial setup

```bash
# 1. Create a new repo on GitHub (do NOT initialise with README)
#    https://github.com/new  → name: PalmGradeRNS

# 2. Initialise git and push
cd PalmGradeRNS
git init
git add .
git commit -m "Initial commit: PalmGrade RNS Android app"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/PalmGradeRNS.git
git push -u origin main
```

The push triggers `.github/workflows/build.yml` automatically.
The debug APK will appear under **Actions → Build PalmGrade RNS APK → Artifacts**.

### Add the gradle-wrapper.jar (required once)

The wrapper JAR is not included in this package. Run once before pushing:

```bash
# Option A — if you have Gradle installed locally:
gradle wrapper --gradle-version 8.6

# Option B — using the Android SDK's bundled Gradle:
# Android Studio: Tools → Gradle → Generate Wrapper
```

Then commit it:

```bash
git add gradle/wrapper/gradle-wrapper.jar
git commit -m "Add gradle wrapper jar"
git push
```

### Release a signed APK

```bash
# 1. Generate a keystore (do this once, store safely)
keytool -genkey -v \
  -keystore palmgrade-release.jks \
  -alias palmgrade \
  -keyalg RSA -keysize 2048 \
  -validity 10000

# 2. Base64-encode the keystore for GitHub Secrets
base64 palmgrade-release.jks | tr -d '\n'   # copy this output

# 3. Add secrets in GitHub → Settings → Secrets → Actions:
#      SIGNING_KEYSTORE_BASE64  ← output from step 2
#      SIGNING_KEY_ALIAS        ← palmgrade
#      SIGNING_KEY_PASSWORD     ← your key password
#      SIGNING_STORE_PASSWORD   ← your store password

# 4. Tag a release to trigger the release build
git tag v1.0.0
git push origin v1.0.0
```

GitHub Actions will build, sign, and attach the APK to a GitHub Release automatically.

### Manual trigger

Go to **Actions → Build PalmGrade RNS APK → Run workflow** and choose `debug` or `release`.
