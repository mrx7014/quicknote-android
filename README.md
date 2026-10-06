# QuickNote for Android

A lightweight Kotlin app for capturing notes, to-dos, and voice notes on-device.

## Features

- Quick capture screen with Note, To-do, and Voice note controls.
- Local SQLite storage for text items; voice recordings are stored in the app's private files directory.
- Saved items remain as ongoing Android notifications until a task is completed or an item is deleted.
- A high-priority QuickNote notification is posted on device unlock (and after boot) as a one-tap capture shortcut.
- To-dos can be checked off; saved voice notes can be played from the list.

### Android unlock behavior

Modern Android versions restrict apps from launching an activity over the lockscreen or another app after a background broadcast. QuickNote therefore displays a tap-to-open notification on unlock rather than forcing a popup window over the phone. Notification permission must be granted. The notification opens the capture screen.

## Build

- Android Studio / Android SDK with API 35
- JDK 17

To build a debug APK: `./gradlew assembleDebug`

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

The `Android release` workflow builds the release variant and uploads the APK as an Actions artifact. It signs with the persistent release key and attaches the APK to a GitHub Release for a `v*` tag only when all signing secrets are configured. Without them, it builds an unsigned APK for CI validation; do not install that artifact over the signed app. Configure these repository Actions secrets once:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The project release signing key should be treated as sensitive. Voice notes and text data are local to the device; the app does not send note content or recordings to a server.

## Permissions

- Notifications: unlock prompt and saved-item reminders.
- Microphone: record voice notes.
- Boot completed: restore the unlock prompt after reboot.
