# FollowerMirror

FollowerMirror is a native Android app for comparing Instagram follower and following data exports on your device. It helps you review account relationships and changes between saved exports. It does not require your Instagram password or perform Instagram actions.

## What FollowerMirror does

Import the follower and following JSON files from an Instagram data export to compare the accounts you follow with the accounts that follow you. Review results, save follower snapshots, and compare snapshots over time. Processing and saved app data are kept locally on the device.

## Key features

- Import Instagram follower and following JSON exports using Android's document picker. Multiple split follower files can be selected together.
- Validate the selected dataset category and export structure before importing; keep Followers and Following as independently managed datasets.
- Compare Followers and Following to find mutual accounts, accounts you follow that do not follow you back, and followers you do not follow back.
- Search results, select accounts, and open profiles for manual review.
- Save up to 30 follower snapshots, browse snapshot history, and compare saved snapshots or a snapshot with a later follower export.
- Use Review Manager to queue accounts for review, record statuses and notes, protect accounts from unfollow review, and view review history. Any unfollowing is a separate action performed manually by the user in Instagram.
- Recover from unreadable saved data with recovery notices, raw-data export, validated backup restore, retry, and explicit preserve-and-clear controls. Writes to affected data are paused while recovery is required.
- Choose Light or Dark theme; the preference is saved locally.

## How it works

1. Request and download an Instagram information export in JSON format. Instagram's menus and export options may change over time.
2. Extract the archive on your device.
3. Import all follower parts (for example, `followers_1.json` and `followers_2.json`) together. Import the following file (commonly `following.json`) into the Following category.
4. Review the comparison results. Save a follower snapshot to establish a baseline, then import a later follower export to compare changes over time.
5. Optionally add selected accounts to Review Manager and inspect profiles yourself before deciding what to do.

Follower comparisons use the usernames present in the imported files. Historical change detection reports accounts that are absent from a later imported snapshot or export. These disappearances are **not confirmed unfollows** and are not detected live. Account deletion, suspension, username changes, export timing, or incomplete exports can also explain an account's absence.

## Privacy and data handling

FollowerMirror does not ask for Instagram passwords, connect to Instagram through a live API, automate account actions, or upload imported follower data to a FollowerMirror server. Imports and comparisons are performed on the device. Saved datasets, snapshots, Review Manager information, and preferences use app-private local storage.

Recovery copies can contain the same personal account information as the original saved data. Export them only to a location you trust. Uninstalling the app or clearing its storage removes locally saved app data; keep a separate backup if you need to retain it.

## Requirements

The project is configured for:

- Android compile SDK: 36
- Android target SDK: 36
- Android minimum SDK: 26 (Android 8.0)
- Android Gradle Plugin: 8.10.1
- Gradle wrapper: 8.11.1
- Kotlin: 2.2.21
- JDK: 17

Android SDK Platform 36 and the SDK Build Tools required by the Android Gradle Plugin must be installed. The project-local `local.properties` file should point to the SDK on your machine; it is intentionally not committed.

## Build

From the project root on Windows, with JDK 17 and the required Android SDK installed, run:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. To open the project in Android Studio, open this project folder and allow Gradle sync to use the included wrapper.

## Project status

FollowerMirror includes local imports and comparison, snapshots and history, Import Manager, Review Manager, theme selection, and saved-data recovery workflows. It is a personal utility project; this README does not represent a store release or guarantee compatibility with every Instagram export format. Verify behavior with your own export and retain backups of data you need.

## Limitations

- There is no live Instagram monitoring or official Instagram API integration.
- Historical disappearances are inferred only by comparing imported data; the app cannot confirm why an account is missing.
- FollowerMirror does not unfollow accounts. Profile review and any account action must be performed manually in Instagram.
- Instagram may change its export format. An export that does not match the formats the app recognizes may need investigation before it can be imported.
- Data is stored locally, so it is not synchronized between devices. Android app data may be lost if the app is uninstalled or its storage is cleared.

## Contributing

For questions or proposed changes, open a GitHub issue with a clear description. For a proposed code change, include the relevant tests and describe how it was verified. Do not include Instagram exports, personal follower data, credentials, signing files, or generated APKs in issues or pull requests.

Licensing information has not yet been provided. No license is implied by this README or by public repository visibility.
