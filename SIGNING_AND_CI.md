# Pipra Android Signing & GitHub Actions CI/CD Guide

This document explains how to generate your own Android release signing keystore, configure GitHub Actions secrets safely, and download built APK artifacts directly on an Android phone or desktop.

---

## 1. Keystore Generation (Run Locally — Never Commit the Keystore)

`app/build.gradle.kts` configures the `release` signing config with `keyAlias = "upload"` and reads `KEYSTORE_PATH`, `STORE_PASSWORD`, and `KEY_PASSWORD` from environment variables.

Generate a PKCS12 upload keystore locally with `keytool`:

```bash
keytool -genkeypair \
  -v \
  -keystore my-upload-key.jks \
  -alias upload \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storetype PKCS12
```

When prompted, enter a strong keystore password, key password, and your name/organization details (e.g., `Muhammad Omor`).

### Back Up Your Keystore Safely
- Store `my-upload-key.jks` and its passwords in an encrypted password manager or offline encrypted backup.
- Never commit `*.jks`, `*.keystore`, or password files to Git (`.gitignore` already ignores `.env` and keystore files; double-check before every commit).
- Losing your signing key prevents shipping seamless updates over previously installed signed APKs.

---

## 2. Base64-Encode the Keystore for GitHub Secrets

On Linux / macOS:
```bash
base64 -w 0 my-upload-key.jks
```

On macOS if `-w 0` is unavailable:
```bash
base64 -i my-upload-key.jks | tr -d '\n'
```

On Windows PowerShell:
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("my-upload-key.jks"))
```

---

## 3. Configure GitHub Repository Secrets

Open your GitHub repository: **Settings → Secrets and variables → Actions → New repository secret** and add these three secrets:

| Secret Name | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Single-line Base64 output from Step 2 |
| `STORE_PASSWORD` | Keystore password entered during `keytool` |
| `KEY_PASSWORD` | Key password for the `upload` alias |

### How the Workflow Uses Them (`.github/workflows/android-build.yml`)
1. Every push, pull request, or manual `workflow_dispatch` runs `gradle :app:testDebugUnitTest` and `gradle :app:assembleDebug`, then uploads `pipra-debug-apk` (`app-debug.apk`).
2. If all three signing secrets are present, the workflow decodes `ANDROID_KEYSTORE_BASE64` into a temporary file, sets `KEYSTORE_PATH`, `STORE_PASSWORD`, and `KEY_PASSWORD`, builds `gradle :app:assembleRelease`, deletes the temporary keystore file (`if: always()`), and uploads `pipra-signed-release-apk` (`app-release.apk`).
3. If any signing secret is missing, the workflow skips the release build cleanly and never mislabels an unsigned APK as signed.

---

## 4. Download & Install the APK on an Android Phone

1. Open your GitHub repository in a mobile browser and tap **Actions**.
2. Open the latest completed **Pipra Android CI & APK Build** run.
3. Scroll to **Artifacts** and tap `pipra-signed-release-apk` (or `pipra-debug-apk` for testing).
4. Extract the downloaded `.zip` in your Android Files app to obtain `app-release.apk` (or `app-debug.apk`).
5. Tap the `.apk` file and allow **Install unknown apps** for your file manager when Android prompts.
