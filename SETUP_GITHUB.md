# Setting Up GitHub & First Build

Step-by-step guide to push this project to GitHub and trigger your first
CI build using GitHub Actions.

---

## 1. Prerequisites (local machine)

```bash
# Check these are installed
git --version       # 2.x
java -version       # 17+
# Android SDK is only needed for local builds;
# GitHub Actions installs it automatically.
```

---

## 2. Add the Gradle wrapper JAR

The `gradle-wrapper.jar` binary cannot be generated without Gradle installed.
Run **one** of these options:

**Option A — Gradle installed locally:**
```bash
cd PalmGradeRNS
gradle wrapper --gradle-version 8.6
```

**Option B — Download directly:**
```bash
curl -L \
  https://github.com/gradle/gradle/raw/v8.6.0/gradle/wrapper/gradle-wrapper.jar \
  -o gradle/wrapper/gradle-wrapper.jar
```

**Option C — Android Studio:**
Open the project → it downloads the wrapper automatically on first sync.

---

## 3. Initialise git and push to GitHub

```bash
cd PalmGradeRNS

# Initialise repository
git init
git add .
git commit -m "Initial commit — PalmGrade RNS v1.0.0"

# Create a new repo on GitHub (replace with your username/repo)
git remote add origin https://github.com/YOUR_USERNAME/PalmGradeRNS.git
git branch -M main
git push -u origin main
```

---

## 4. Watch the first build

After pushing to `main`, GitHub Actions triggers automatically.

1. Go to your repo on GitHub
2. Click the **Actions** tab
3. You'll see **Build PalmGrade RNS APK** workflow running
4. Click into it → **Debug APK** job
5. When it completes (~5–8 min), click **Artifacts** to download the APK

---

## 5. Set up release signing (optional but recommended)

**Generate a keystore:**
```bash
keytool -genkey -v \
  -keystore palmgrade-release.jks \
  -alias palmgrade \
  -keyalg RSA -keysize 2048 \
  -validity 10000
```

**Base64-encode it:**
```bash
# macOS
base64 -i palmgrade-release.jks | pbcopy

# Linux
base64 palmgrade-release.jks
```

**Add secrets to GitHub repo:**

Go to **Settings → Secrets and variables → Actions → New repository secret**
and add all four:

| Secret name | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | The base64 string from above |
| `SIGNING_KEY_ALIAS` | `palmgrade` |
| `SIGNING_KEY_PASSWORD` | Your key password |
| `SIGNING_STORE_PASSWORD` | Your store password |

---

## 6. Trigger a release build

**Via a version tag:**
```bash
git tag v1.0.0
git push origin v1.0.0
```
This triggers the **Release APK** job, creates a signed APK, and publishes
a GitHub Release with the APK attached automatically.

**Via manual trigger:**
GitHub → Actions → **Build PalmGrade RNS APK** → **Run workflow** →
select `release` → **Run workflow**.

---

## 7. Install the APK on a device

**Via ADB (USB debugging on):**
```bash
adb install app-debug.apk
# or for release:
adb install palmgrade-rns-v1.0.0.apk
```

**Via direct transfer:**
Copy the APK to the device, enable "Install from unknown sources" in
Settings → Security, then open the APK file.

---

## 8. First run checklist

On the device after installing:

- [ ] Grant **Location** permission (required for GPS)
- [ ] Grant **Bluetooth** permission (required for RNode)
- [ ] Grant **Camera** permission (required for proof photos)
- [ ] Pair your RNode device in Android Bluetooth settings first
- [ ] Open the app → **Radio** tab → select your RNode → tap **Connect**
- [ ] Verify connection status shows **CONNECTED**
- [ ] Note your **LXMF address** — share it with the receiver operator

---

## Workflow summary

| Trigger | Jobs run |
|---|---|
| Push to `main` or `develop` | Debug APK + Lint |
| Pull request to `main` | Debug APK + Lint |
| Tag `v*.*.*` | Release APK → GitHub Release |
| Manual (debug) | Debug APK only |
| Manual (release) | Release APK (signed if secrets set) |
