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

### 🍏 iOS-style design (Liquid Glass)
- **Browse screen** like the iOS Files app: large title, search with filter chips, collapsible **Locations / Favorites / Tags**
- **Tabs:** Recents · Browse · Apps in a floating glass capsule; the top bar is glass too and blurs what scrolls under it
- **Folder screens:** back arrow, round "…" menu (Select, New Folder, Icons / List, Sort…)
- **Select mode:** "Select All / Done", "N Selected", round check badges, and a batch toolbar (Share, Copy, Move, Zip, Delete) that replaces the tab bar
- **Colour tags:** Red / Orange / Blue / Green (long-press a file → Tags)
- **Windows-style file icons:** yellow folders and white pages with coloured type labels (PDF, DOCX, XLSX, MP3 …)

### ✨ Animations
Smooth slide / fade between screens, list items that rise in, a selection toolbar that slides up, pop-in check badges, a sidebar that glides open, and an album cover that grows and shrinks with play / pause. Turn them off in **☰ → Appearance → Animations** (useful on weak car units).

### 🏠 Browse screen
- Search all files by name
- Storage cards with usage bars
- Categories with counts: Images, Videos, Music, Documents, APKs, Archives, Downloads, Large files
- Recent files with thumbnails, and quick tools

### 🧭 Sidebar
- Swipe from the left or tap ☰ to open, and it hides again when you're done
- All storage with usage bars (internal, SD card, USB), quick folders (Downloads, Camera, Pictures, Music, Videos, Documents), OBB, Recycle Bin, Apps
- Root places (`Android/data`, `/`) only on rooted devices

### 📁 File Explorer
- **Icons view** with real photo/video thumbnails and APK icons, or **List view** (⋮ → View)
- **Select mode**: tick many files → glass action bar: **Share · Copy · Move · Zip · Delete · More**
- **Action sheet**: long-press any file → share straight to an app, copy, move, zip, rename, details, delete
- 🖼️ **Thumbnails everywhere**: photos, video frames, music album art, PDF first page, APK icons
- 📄 **Office viewer & editor**: Word `.docx` (view formatted, edit text), Excel `.xlsx` (sheets grid, edit cells), PowerPoint `.pptx` (slides, edit text)
- 🗜️ **Zip & Unzip**: compress files/folders to `.zip`; tap a `.zip` to extract it
- Browse **internal storage**, **SD card** and **USB drives**
- Open, share, rename, copy, move, paste, delete, details
- Create folders and files · show hidden files · sort by name / size / date
- **Tap an `.apk` to install it**
- 🖼️ **Photo viewer**: swipe through the folder, pinch / double-tap zoom, share, delete
- 🎨 **Photo editor**: crop (1:1, 4:3, 16:9…), rotate, flip, 9 filters, brightness / contrast / saturation, save as a copy or replace
- ✨ **Smart (on-device) photo tools**: auto enhance, smart crop, blur faces, portrait blur
- 🎬 **Video player** (±10 s, seek bar) and **video editor**: trim, mute, rotate, saved as a new MP4 without losing quality
- 🎵 **Built-in music player**: tap a song to play the whole folder (shuffle, repeat, seek), album art, an **equalizer that follows the real sound** (microphone permission, optional), a **playing queue** (tap to play, remove or delete songs) and a notification with controls
- 📝 **Built-in notepad**: text files open directly. Read, edit and save. Create new `.txt` files with ＋
- 🎮 **OBB folder** shortcut (Storage → OBB folder) for game data

### 🔌 USB drive tools
- Finds USB OTG drives, card readers and SD cards, with a live "USB drive connected" alert
- Works even when the phone does not show the drive: read format & size, **open the drive with read and write**: upload from phone, new folder, rename, delete, copy to phone
- **Format as FAT32** (no root needed; converts NTFS / exFAT drives too) and **rename the drive**
- **Merge partitions**: see all partitions (MBR / GPT) and combine them into one full-size drive
- NTFS / exFAT: mount with root; without root use Format as FAT32

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

**Appearance** (⋮ → Appearance): theme **White**, **Black** or **System default**, plus **Liquid Glass** (frosted glass cards on a clean white or black background, with touch effects) and a **Transparency** slider for see-through glass cards, bars and panels (with background blur on Android 12+).

## 🔒 Block internet for AI

One switch (☰ sidebar or Appearance → **Block internet for AI**, **on by default**) controls the AI features.

* **On:** AI features stay on your phone only. The sidebar shows "AI: on this phone only".
* **Off:** AI features may use online services. The AI tools included today (auto enhance, smart crop, blur faces, portrait blur)
  are on-device, so they work the same either way.
* **Updates are never blocked.** The app can always check for and download updates.
* The build fails automatically if network code or an online AI library is ever added to the photo / video editing code.

## 🛠️ Technical documentation

* [`docs/TECHNICAL.md`](docs/TECHNICAL.md): architecture, file map, design system, build / signing / release, limits
* [`docs/CLAUDE.md`](docs/CLAUDE.md): project memory for AI assistants (rules, owner preferences, mistakes to avoid)

| | |
|---|---|
| Min Android | 5.0 (API 21), target 34 |
| Language | Kotlin 2.0, Views + Material 1.12 |
| Build | GitHub Actions (`.github/workflows/build.yml`), signed with a key kept in GitHub Secrets |
| Version scheme | `2.9.0.00N` |

## ©️ Copyright

© 2026 **MB Network**. All rights reserved. The same notice, with the open-source notices (libaums, AndroidX, Material, Kotlin: Apache License 2.0), is inside the app under **☰ → About and copyright**.

## 🔄 Updates

- The app checks for new versions automatically, about once a day, or tap **Sidebar → Check for updates**
- To install new versions **as updates** (no uninstall), builds must be signed with one fixed key stored in GitHub Secrets: `SIGNING_KEY_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`

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
