# QuickNote for Android

A lightweight Android app for capturing notes, to-dos, and voice memos. The interface supports **English and Arabic**, with the selected language available in Settings (and in Android's per-app language settings on Android 13+).

## Features

- Refined capture screen and a matching Settings screen.
- Local SQLite storage for text items; voice recordings remain in the app's private files directory.
- Optional floating capture card after unlocking the phone, controlled from the home screen or Settings.
- Language selector for device language, English, and Arabic.
- To-dos can be checked off; saved voice memos can be played from the list.
- Saved item notifications, with localized channel labels.

## Enable the unlock popup

Open QuickNote and enable **Popup when I unlock**. Android requires the user to grant the app **Display over other apps** special access. The ongoing low-priority notification indicates that the foreground popup service is active. The popup is shown only while this option is enabled and the special access is granted. If the device manufacturer applies aggressive background limits, allow QuickNote to run in the background.

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

The signing key is sensitive. Notes, tasks, and recordings stay local; QuickNote does not send their contents to a server.
