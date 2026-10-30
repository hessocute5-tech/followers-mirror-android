# Follower Mirror (Android)

A local-first Android starter app for comparing Instagram followers and following lists. It never asks for your Instagram password and does not automate unfollowing.

## Features in this starter
- Black/red Compose UI
- Import follower and following JSON exports through Android's file picker
- Select multiple split follower files (such as `followers_1.json` and `followers_2.json`) in one import
- Manage Followers and Following as independent datasets, including replace, delete, import metadata, and reset-all controls
- Validate export structure and filenames against the selected category before replacing stored data
- Compare mutuals, accounts you follow who do not follow you back, and followers you do not follow back
- Normalize usernames case-insensitively and remove duplicates
- Search, select all, select individual accounts, and open profiles manually
- Save up to 30 local follower snapshots, view their history, and delete snapshots
- Compare adjacent snapshots and the latest imported follower list to detect accounts missing from a later export
- Local persistence using app-private SharedPreferences
- No analytics, network API, or server upload

## Build
1. Install Android Studio (current stable) and Android SDK 35.
2. Open this folder (`FollowerMirror`) in Android Studio, or build with the included Gradle 8.9 wrapper using `gradlew.bat assembleDebug`.
3. Run on an Android device/emulator (Android 8.0 / API 26 or newer).

## Import Instagram data
In Instagram, open Accounts Center → Your information and permissions → Export your information (labels may vary) and request a download that includes followers and following. Choose JSON format. Extract the downloaded archive on your phone. Select all follower files (`followers_1.json`, `followers_2.json`, and so on) together, then import the following list (`following.json`, commonly inside `connections/followers_and_following`) separately.

## Important notes
- Historical comparison reports detected disappearances between exports, not confirmed unfollows. Account deletion, suspension, username changes, export timing, and incomplete exports can also cause an account to be absent.
- Historical comparison requires a saved earlier follower snapshot and a later export. A first snapshot only establishes a baseline; it cannot identify earlier changes retroactively.
- Instagram export structures can vary; test against your own export before relying on the results.
- “Review selected” opens the first selected profile. Use each row's OPEN button to inspect individual accounts. No automatic unfollow action is performed.
- Data remains on this device in app-private storage. Uninstalling the app or clearing its data removes the stored lists and snapshots.
- Datasets imported before category validation was added are marked as older/unverified because their source filenames and import times were not recorded. Replace them from the Import Manager to validate the source files.

## Tests

Run JVM unit tests with `gradlew.bat testDebugUnitTest` and Android lint with `gradlew.bat lintDebug`.
