# RAVE Photo Editor

Android photo editor with on-device AI, by RAVE Management LLC.

- **AI Cutout** – background removal (ML Kit subject segmentation, on device), saved as transparent PNG
- **Portrait** – background blur behind the subject
- **Backdrop** – replace the background with a solid colour
- **Enhance** – one-tap auto levels and colour
- **Filters / Adjust** – eight looks; brightness, contrast, saturation, warmth
- Save to `Pictures/RAVE Photo Editor`, share via the Android share sheet, or share a photo *to* the app

Package: `com.ravemanagementllc.ravephotoeditor` · minSdk 29 · targetSdk 36 · Kotlin + Jetpack Compose.

## Build

GitHub Actions (`.github/workflows/build.yml`) builds on every push to `main` (changes to `docs/`, `store/` and
Markdown files don't trigger a build):

- `app-release.aab` – Android App Bundle for Google Play (signed when the signing secrets are set)
- `app-release.apk` and `app-debug.apk` – for installing directly on a phone
- a smoke test on an Android emulator: it opens a sample photo, runs each tool, takes screenshots, saves a photo and
  checks logcat for crashes (`.github/scripts/screenshots.sh`)

Download the outputs from the run's **Artifacts** section; the latest build is also on the `ci-builds` branch.
The version code is the workflow run number, so every build can be uploaded.

Signing: without secrets the bundle is unsigned and can be signed afterwards with
`jarsigner -keystore rave-upload-key.jks app-release.aab rave-upload`. To have CI sign it, add these secrets
(Settings → Secrets and variables → Actions): `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_STORE_PASSWORD`,
`UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`. The upload key is used with Play App Signing; keep a backup of it.

Local build: Android Studio, or `./gradlew bundleRelease` with JDK 17 and the Android SDK (API 36).

## Store listing and policy

- Listing text, Data safety and content-rating answers: [`store/listing.md`](store/listing.md)
- Graphics: [`store/play-icon-512.png`](store/play-icon-512.png), [`store/feature-graphic-1024x500.png`](store/feature-graphic-1024x500.png)
- Privacy policy (GitHub Pages): https://rvats.github.io/rave-photo-editor/privacy.html

Contact: info@ravemanagementllc.com
