# MB File Manager (Android 5.0+)

A file manager and app manager for Android **5.0 Lollipop (API 21) and newer**.

## Features

### Smart permission check
On launch, the app checks whether the **Android version needs a permission**:

| Android version | What happens |
|---|---|
| 5.0 – 5.1 (API 21–22) | Permissions are granted at install time. **No popup**, it just works. |
| 6.0 – 10 (API 23–29) | If storage permission is missing, a popup explains it and the system "Allow" dialog appears. |
| 11+ (API 30+) | If "All files access" is off, a popup explains it and opens the settings switch. |

If the permission is already granted, nothing is shown.

### Root detection
At startup the app checks whether the device is **actually rooted** (an `su` binary exists).
- **Not rooted:** all root features are hidden: no Root mode, no "Root (/)" storage, no root app actions.
- **Rooted:** root features appear. They are used only after you allow access in your root manager.

### Files tab
- Browse internal storage, SD cards and USB drives
- Open, open with, share, rename, copy, cut/move, paste, delete, details
- **Install apps:** tap any `.apk` file. On Android 8+ the app first checks the "Install unknown apps"
  permission and only asks if it's missing. Rooted devices also get *Install silently (root)*.
- New folder (＋ button) / new file, show hidden files, sort by name/size/date
- **Root mode** (menu → Root mode, or Storage → Root): browse `/`, `/system`, `/data` and other
  protected folders through `su`. Protected files can still be opened (a temporary copy is made).
  A red **ROOT MODE** banner shows when it's active.

### Apps tab
- Lists installed apps, with search and a **Show system apps** toggle
- **Install APK…** (menu) finds every APK on your storage and installs the one you pick
- **Uninstall** normal apps (standard Android uninstall dialog, no root needed)
- **Uninstall system apps** (rooted devices only): `pm uninstall -k --user 0`. Removed system apps stay in the
  list as *REMOVED* and can be **restored** later.
- Disable/enable apps (root), open the app, open App info, save the APK to `Download/`

## Build
```bash
./gradlew assembleDebug
```
The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
Every push also builds the APKs on GitHub Actions (**Actions → Build APK → Artifacts**).

## Install
Copy the APK to the phone, open it and allow "Install unknown apps" when asked.
Root features need a rooted device (Magisk, SuperSU, KernelSU…). Grant access when the root manager asks.
