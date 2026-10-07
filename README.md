<div align="center">

# 📱 OurHomeApp

**The Android companion for [OurHomeWeb](https://github.com/EOSOClub/OurHome).**

Your self-hosted household (tasks, shopping, inventory, bills and requests) in your pocket,<br>
with NFC tags and phone notifications.

[![Android](https://img.shields.io/badge/Android-12%2B%20(API%2031)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#-requirements)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![Version](https://img.shields.io/badge/version-0.1.0-orange?style=for-the-badge)](#)

[Features](#-features) · [Requirements](#-requirements) · [Setup](#-setup) · [Development](#development) · [🌐 Web server](https://github.com/EOSOClub/OurHome)

</div>

> [!IMPORTANT]
> This app talks to the web server's API, so you need a running **[OurHomeWeb](https://github.com/EOSOClub/OurHome)** server first.

---

## ✨ Features

<table>
<tr>
<td width="50%" valign="top">

### 🏠 The whole household
Dashboard, tasks (with steps), shopping list, inventory, bills, requests, notifications and profile.

### 🛡️ Role-aware UI
Controls a role can't use are hidden. The server enforces the real rules.

### 🔔 Phone notifications
Request reminders, deadline reminders and bug-report alerts, from an hourly background check.

</td>
<td width="50%" valign="top">

### 🏷️ NFC tags
- Scan a tag to open its item, or get a quick **used / restocked** notification
- Write tags as `ourhome://tag/<id>`. Existing Home Assistant tag ids are kept.

### 🔒 Lock-screen safe
Notification content is hidden on a locked phone, and every action that changes data requires the device to be unlocked first.

### 🌙 Dark by default
Dark theme with Material You colors.

</td>
</tr>
</table>

## 📋 Requirements

- Android **12 (API 31)** or newer
- A running [OurHomeWeb](https://github.com/EOSOClub/OurHome) server and an account created on it
- An NFC-capable phone for tag features (optional)

## 🚀 Setup

1. **Install Android Studio** (it includes the JDK and Android SDK).
2. **Clone this repo** and open it in Android Studio.
3. **Optional: set a default server.**
   ```bash
   cp config.example.properties config.properties
   ```
   Edit `server.url` to point at your OurHomeWeb server (e.g. `https://home.example.com`). `config.properties` is gitignored.

   > [!TIP]
   > If you skip this step, the sign-in screen asks for the server address. You can always change it there under **Server**.
4. **Build and install:**
   ```bash
   ./gradlew installDebug
   ```
   You can also press **Run** in Android Studio.

Sign in with an account created on the web app. 🎉

<a id="development"></a>

## 🛠️ Development

```bash
./gradlew testDebugUnitTest assembleDebug
```

| | |
| --- | --- |
| **Package** | `com.eosoclub.ourhome` |
| **UI** | Kotlin + Jetpack Compose (Material 3) |
| **Networking** | OkHttp, kotlinx.serialization |
| **Background work** | WorkManager |

> [!WARNING]
> The role matrix in `data/Permissions.kt` mirrors `src/lib/permissions.ts` in the [web repo](https://github.com/EOSOClub/OurHome). **Keep the two in sync.**

---

<div align="center">
<sub>📱 Part of the Our Home project · Server: <a href="https://github.com/EOSOClub/OurHome">OurHomeWeb</a></sub>
</div>
