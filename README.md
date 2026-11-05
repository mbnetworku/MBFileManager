<div align="center">

<img src="docs/icon.svg" width="96" alt="MB File Manager icon">

# MB File Manager

**A file explorer and app manager for Android 5.0 and newer.**

[![Download APK](https://img.shields.io/badge/Download-APK-2ea44f?style=for-the-badge&logo=android&logoColor=white)](https://github.com/mbnetworku/MBFileManager/releases/latest/download/MBFileManager.apk)

[![Latest release](https://img.shields.io/github/v/release/mbnetworku/MBFileManager?style=flat-square)](https://github.com/mbnetworku/MBFileManager/releases/latest)
![Android 5.0+](https://img.shields.io/badge/Android-5.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
[![Build](https://img.shields.io/github/actions/workflow/status/mbnetworku/MBFileManager/build.yml?style=flat-square&label=build)](https://github.com/mbnetworku/MBFileManager/actions)

</div>

---

## 📥 Download

| | Link |
|---|---|
| **Latest APK (always newest)** | **[MBFileManager.apk](https://github.com/mbnetworku/MBFileManager/releases/latest/download/MBFileManager.apk)** |
| Version 1.1 | [MBFileManager-v1.1.apk](https://github.com/mbnetworku/MBFileManager/releases/download/v1.1/MBFileManager-v1.1.apk) |
| All versions | [Releases page](https://github.com/mbnetworku/MBFileManager/releases) |

### How to install
1. Open the **Download APK** link on your phone.
2. Tap the downloaded file.
3. If asked, allow **Install unknown apps** for your browser, then tap **Install**.

---

## ✨ Features

### 📁 File Explorer
- **Icons view** with real photo/video thumbnails and APK icons, or **List view** (⋮ → View)
- **Select mode**: tick many files → glass action bar: **Share · Copy · Move · Zip · Delete · More**
- **Action sheet**: long-press any file → share straight to an app, copy, move, zip, rename, details, delete
- 🗜️ **Zip & Unzip**: compress files/folders to `.zip`; tap a `.zip` to extract it
- Browse **internal storage**, **SD card** and **USB drives**
- Open, share, rename, copy, move, paste, delete, details
- Create folders and files · show hidden files · sort by name / size / date
- **Tap an `.apk` to install it**
- 🎵 **Built-in music player**: tap a song to play the whole folder (shuffle, repeat, seek)
- 📝 **Built-in notepad**: text files open directly. Read, edit and save. Create new `.txt` files with ＋
- 🎮 **OBB folder** shortcut (Storage → OBB folder) for game data

### 🗑️ Recycle Bin
- Deleted files go to the **Bin** tab first, so you can **see and restore** them
- *Delete forever* and *Empty Recycle Bin* when you're sure
- Only covers files deleted inside this app

### 📦 App Manager
- List all apps, with search and a **Show system apps** switch
- **Install** APKs: *Install APK…* finds every APK on your storage
- **Uninstall** any normal app
- Open an app, open its App info, save its APK to `Download/`

### 🔓 Root (rooted devices only)
The app **checks whether your device is rooted**. On a normal phone, root options are hidden.

| | Not rooted | Rooted |
|---|:---:|:---:|
| Browse `/`, `/system`, `/data` | ❌ hidden | ✅ |
| Open `Android/data` | ❌ hidden | ✅ |
| Uninstall / restore system apps | ❌ hidden | ✅ |
| Disable / enable apps | ❌ hidden | ✅ |
| Silent APK install | ❌ hidden | ✅ |

> ⚠️ Removing important system apps can make your phone unstable. They're removed only for your user and can be **restored** from the app list.

---

## 📱 Works on every screen

| Device | Layout |
|---|---|
| **Phone** | List view with bottom navigation |
| **Tablet** | Grid view with side navigation rail |
| **Android TV** | Grid view, remote/D-pad friendly with a glowing focus outline, listed on the TV home screen |

**Appearance** (⋮ → Appearance): theme **White**, **Black** or **System default**, plus a **Transparency** slider for see-through glass cards, bars and panels (with background blur on Android 12+).

## 🔐 Permissions: asked only when needed

The app checks your **Android version** and only asks when a permission is actually missing.

| Android version | Storage access | Installing apps |
|---|---|---|
| **5.0 – 5.1** | Granted at install, **no popup** | No popup |
| **6 – 7** | Popup only if missing | No popup |
| **8 – 10** | Popup only if missing | "Install unknown apps," only if off |
| **11 and newer** | "All files access" switch, only if off | "Install unknown apps," only if off |

---

## 🛠️ Build it yourself

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

Every push builds the APK on **GitHub Actions** and publishes it on the **[Releases page](https://github.com/mbnetworku/MBFileManager/releases)**.

| | |
|---|---|
| Min SDK | 21 (Android 5.0) |
| Target SDK | 34 |
| Language | Kotlin |
| UI | Material Design 3 |

<div align="center">

---

Made by **MB Network**

</div>
