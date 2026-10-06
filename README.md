# QuickNote for Android

A lightweight Android app for capturing notes, to-dos, and voice memos. The interface supports **English and Arabic**, with the selected language available in Settings (and in Android's per-app language settings on Android 13+).

## Features

- Refined bilingual capture and settings screens, with light and dark themes.
- Search across notes, tasks, and tags; filter by item type or favorites; add tags and mark favorites.
- Optional inexact reminders for tasks, with notification actions to complete or snooze by 10 minutes.
- Local SQLite storage and private app files for voice memos; import/export a ZIP backup with attached recordings using Android's file picker.
- Biometric/device-credential app lock and an option to hide notification details.
- Home-screen widget and Quick Settings tile for one-tap note, task, or voice capture.
- Saved voice memos can be played and, on Android 13+ devices with an on-device speech recognizer that supports audio-source input, transcribed locally.
- Optional floating capture card after unlocking the phone, controlled from the home screen or Settings.
- Language selector for device language, English, and Arabic; Android's per-app language settings are supported on Android 13+.

## Enable the unlock popup

Open QuickNote and enable **Popup when I unlock**. Android requires the user to grant the app **Display over other apps** special access. The ongoing low-priority notification indicates that the foreground popup service is active. The popup is shown only while this option is enabled and the special access is granted. If the device manufacturer applies aggressive background limits, allow QuickNote to run in the background.

## Reminders, backups, and privacy

Task reminders use Android's inexact alarm API, so battery-saving modes or the operating system can delay delivery. QuickNote intentionally does not request exact-alarm special access. Backup export creates a ZIP file chosen by the user and includes voice recordings; restoring replaces the current item list, so export a copy first if needed. The biometric lock is a local access gate; Android's system credential prompt handles authentication. Voice transcription uses the on-device recognizer only and requires Android 13+ plus a recognizer that supports audio-file input. All note data remains in the app's local storage unless the user exports a backup.

## Build

- Android Studio / Android SDK with API 35
- JDK 17

Build a debug APK: `./gradlew assembleDebug`

To build a **signed release APK**, configure the release signing values as environment variables before running `./gradlew assembleRelease`:

```sh
export ANDROID_KEYSTORE_PATH=/path/to/quicknote-release.jks
export ANDROID_KEYSTORE_PASSWORD='your-keystore-password'
export ANDROID_KEY_ALIAS='quicknote'
export ANDROID_KEY_PASSWORD='your-key-password'
./gradlew assembleRelease
```

The signed APK is written to `app/build/outputs/apk/release/app-release.apk`. Keep the keystore and passwords backed up securely; losing the keystore prevents signing compatible app updates. Do not commit signing credentials or the keystore.

## GitHub Actions release

The `Android release` workflow builds the release variant and uploads the APK as an Actions artifact. It attaches a signed APK to a GitHub Release for a `v*` tag only when all signing secrets are configured. Without them, CI builds an unsigned APK for validation; do not install that artifact over the signed app. Configure these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The signing key is sensitive. Notes, tasks, and recordings stay in local app storage unless the user chooses to export a backup. QuickNote does not upload note content to an app server.
