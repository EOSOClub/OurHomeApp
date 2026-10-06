# Our Home (Android)

Android client for [Our Home](https://github.com/EOSOClub/OurHome), a self-hosted household app for tasks, shopping, inventory, bills and requests. The app uses the web server's API, so you need a running Our Home server first.

## Features

- Dashboard, tasks (with steps), shopping list, inventory, bills, requests, notifications and profile
- Role-aware UI: controls a role can't use are hidden. The server enforces the real rules.
- Phone notifications: request reminders, deadline reminders and bug-report alerts, from an hourly background check
- NFC tags:
  - scan a tag to open its item, or get a quick "used / restocked" notification
  - write tags as `ourhome://tag/<id>`. Existing Home Assistant tag ids are kept.
- Lock-screen safe: notification content is hidden on a locked phone, and every action that changes data requires the device to be unlocked first
- Dark theme by default, with Material You colors

Requires Android 12 (API 31) or newer.

## Setup

1. Install Android Studio (it includes the JDK and Android SDK).
2. Clone this repo and open it in Android Studio.
3. Optional: set a default server:
   ```
   cp config.example.properties config.properties
   ```
   Edit `server.url` to point at your Our Home server (e.g. `https://home.example.com`). `config.properties` is gitignored.
   If you skip this step, the sign-in screen asks for the server address. You can always change it there under **Server**.
4. Build and install:
   ```
   ./gradlew installDebug
   ```
   You can also press Run in Android Studio.

Sign in with an account created on the web app.

## Development

```
./gradlew testDebugUnitTest assembleDebug
```

- Package: `com.eosoclub.ourhome`
- Kotlin + Jetpack Compose, OkHttp, kotlinx.serialization, WorkManager
- The role matrix in `data/Permissions.kt` mirrors `src/lib/permissions.ts` in the web repo. Keep the two in sync.
