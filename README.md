# QuickNote for Android

A local-first Android app for capturing notes, tasks, and voice memos. The interface supports **English and Arabic**, including Android's per-app language settings on Android 13+.

## Features

- Bilingual capture and grouped settings screens, with System, Light, and Dark themes.
- Search, sorting, multiple normalized tags, favorites, pinning, archive, and a recoverable Trash.
- Open, read, edit, share, copy, duplicate, and delete saved items; deleting first moves the item to Trash and offers Undo.
- Optional task reminders with presets and notification actions to complete or snooze by 10 minutes.
- Local SQLite storage and private app files for voice memos; ZIP export/import uses Android's file picker and includes audio.
- Biometric/device-credential app lock, configurable relock timeout, screenshot/recents protection, and private notifications.
- Home-screen widget and separate Quick Settings tiles for note, task, and voice capture.
- Voice playback and, on Android 13+ devices with compatible on-device recognition, local transcription with a selectable recognition language.
- Optional, user-enabled floating capture card after every unlock, with an idle timeout and a persistent low-priority service notification.

## Requirements

- Minimum Android version: Android 8.0 (API 26).
- Compile/target SDK: Android 16 (API 36).
- JDK 17 for local builds.

## Permissions and background behavior

| Permission / access | Why QuickNote uses it |
|---|---|
| Microphone | Only when the user taps the voice-record control. Leaving the app stops and saves the recording. |
| Notifications | Task reminders, save confirmations, and the ongoing notification required while the optional popup service is running. Requested in context. |
| Display over other apps | Optional unlock capture card. The app explains the overlay and persistent-notification trade-off before opening Android Settings. |
| Boot completed | Restore scheduled reminders after restart; restart the popup service only when it was explicitly enabled. |
| Biometrics / device credential | Optional local app-lock screen. If the device can no longer authenticate, the lock disables itself instead of trapping the user. |

Task reminders use Android's inexact alarm API, so power-saving modes or the operating system can delay delivery. QuickNote does not request exact-alarm special access. The app lock is a local interface gate, not encryption of the database or media files. Notification details are hidden automatically while the app lock is enabled.

## Unlock popup

Open QuickNote or Settings and enable **Popup when I unlock**. Android requires the user to grant **Display over other apps** special access. The app shows an explanation first. The popup appears after every unlock while the feature and permission are enabled, and an idle timeout closes it. The ongoing low-priority notification indicates that the foreground popup service is active. Some device manufacturers may apply additional background limits.

## Data, backups, and privacy

Notes, tasks, recordings, and drafts are stored in app-private local storage. QuickNote does not upload note content to an app server, and Android cloud backup is disabled. **The local database, voice recordings, and exported ZIP backups are not encrypted by QuickNote.** Protect exported backup files as sensitive data and do not store them in a shared location unless intended.

Restore validates archive paths, file counts, sizes, fields, and available space, then replaces the current item list. Export a separate copy before restoring if you need to preserve the current data. Voice transcription is performed with Android's on-device recognizer only; it requires Android 13+ and a compatible installed speech service/language pack.

## Build and test

```sh
./gradlew assembleDebug
./gradlew lint testDebugUnitTest
```

To build a **signed release APK**, configure the release signing values as environment variables before running `./gradlew assembleRelease`:

```sh
export ANDROID_KEYSTORE_PATH=/path/to/quicknote-release.jks
export ANDROID_KEYSTORE_PASSWORD='your-keystore-password'
export ANDROID_KEY_ALIAS='quicknote'
export ANDROID_KEY_PASSWORD='your-key-password'
./gradlew assembleRelease
```

The release APK is written to `app/build/outputs/apk/release/app-release.apk`. Keep the keystore and passwords backed up securely; losing the keystore prevents signing compatible app updates. Do not commit signing credentials or the keystore.

## GitHub Actions

The `Android release` workflow runs Gradle wrapper validation, Android lint, unit tests, and a release APK build on pushes and pull requests. It verifies the APK signature and uploads distinctly named signed or unsigned APK artifacts. Only a signed build from a `v*` tag can be published as a GitHub Release. Configure these repository Actions secrets for signed CI releases:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Unsigned artifacts are for validation only and cannot update an installation signed with the release key.

## App icon attribution

The launcher icon is the **Sticky Notes** icon by [Icons8](https://icons8.com/icon/xZCHuxKdTCDa/sticky-notes), downloaded from the Icons8 image CDN. If distributing the app under an Icons8 free-use plan, keep the required Icons8 attribution according to their current license terms.
