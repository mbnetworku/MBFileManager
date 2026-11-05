# MB File Manager – Technical documentation

© 2026 MB Network. All rights reserved.

This document explains how the app is built, so it can be maintained, extended and released safely.
User-facing features are listed in the [README](../README.md).

---

## 1. Platform and toolchain

| Item | Value |
|---|---|
| App id / package | `com.mbnetwork.filemanager` |
| Minimum Android | **5.0 (API 21)** |
| Target / compile SDK | 34 / 35 |
| Language | Kotlin 2.0.21, Java 17 bytecode |
| Android Gradle Plugin | 8.7.3 (Gradle wrapper 8.14.3) |
| UI | Views (XML + Kotlin), Material Components 1.12, no Compose |
| Concurrency | Kotlin coroutines (`lifecycleScope`, `Dispatchers.IO`) |
| USB | `me.jahnen.libaums:core:0.10.0` (Apache 2.0) |
| Version scheme | `versionName` = `2.9.0.00N`, `versionCode` increases by 1 each release |

Runs on phones, tablets, Android TV and Android-based car radios (head units).
There is **no local Android SDK**: the app is compiled by GitHub Actions (see section 8).

---

## 2. Source layout

```
app/src/main/java/com/mbnetwork/filemanager/
  MainActivity.kt          single main activity: tabs, drawer, glass bars, mini player, navigation
  HomeFragment.kt          "Browse" screen (search, chips, Locations / Favorites / Tags)
  FilesFragment.kt         folder / category / search / Recents list, Select mode, action sheet
  AppsFragment.kt          installed apps (uninstall, root actions, APK export)
  TrashFragment.kt         Recently Deleted
  FileAdapter.kt           list / grid / icon-view adapter (selection, thumbnails, tag dots)
  FileOps.kt               copy / move / delete / rename (java.io, or `su` shell in root mode)
  FileIndex.kt             categories + search through MediaStore (fast)
  FileIcons.kt             Windows-style icons drawn with Canvas (folder, pages with type labels)
  ThumbLoader.kt           photo / video / album-art / PDF / APK-icon thumbnails (LRU cache)
  GlassView.kt             Liquid Glass surface (live blur) – see section 4
  DeviceUi.kt              device detection (phone / tablet / TV / car), theme, helpers
  Sidebar.kt, About.kt     drawer content, About and copyright dialog
  Tags.kt                  colour tags (Red / Orange / Blue / Green) stored by file path
  Trash.kt                 Recycle Bin (hidden folder on the same volume)
  StorageVolumes.kt        internal / SD / USB detection (4 methods)
  UsbToolsActivity.kt, FatFormatter.kt   USB drive tools (libaums): read, browse, format, rename, merge
  MusicPlayer.kt, MusicService.kt, PlayerActivity.kt, EqualizerView.kt   music
  PhotoViewerActivity.kt, ZoomImageView.kt, PhotoEditorActivity.kt, EditorCanvas.kt, ImageUtil.kt   photos
  VideoPlayerActivity.kt, VideoEditorActivity.kt, VideoTrimmer.kt   video
  OfficeActivity.kt, OfficeDoc.kt   Word / Excel / PowerPoint viewer + editor (OOXML = zipped XML)
  TextEditorActivity.kt    notepad
  ZipUtil.kt               zip / unzip (zip-slip safe)
  Updater.kt, ApkInstaller.kt   in-app updates and APK install
  PermissionHelper.kt, RootShell.kt, Prefs.kt, MediaTypes.kt, UiKit.kt
app/src/main/res/            layouts, drawables (vector), anim, values, values-night, values-land
.github/workflows/build.yml  CI: build, sign, publish release
docs/                        this documentation + AI memory file
```

---

## 3. Navigation and screens

* **One `MainActivity`**, fragments in `R.id.container`.
* Bottom tabs (`menu/bottom_nav.xml`): **Recents** (a `FilesFragment` in "pinned category" mode), **Browse** (`HomeFragment`), **Apps**.
  Tablet / TV / car use a `NavigationRailView` instead of the bottom capsule.
* Opening a folder / category / Recently Deleted **pushes** a fragment on the back stack (`show(f, push = true)`), so Back returns to Browse.
* `MainActivity.setNavMode(back)` switches the toolbar button between the sidebar (☰) and a back arrow.
* `topInset` / `bottomInset` tell fragments how much room the floating glass bars need; the mini player adds extra bottom room
  (`applyMiniInset`) and lifts the FAB.
* Extra full-screen activities: music player, photo viewer / editor, video player / editor, notepad, Office, USB tools.
  They slide in / out through `DeviceUi.enterSlide / exitSlide / enterRise / exitDrop`.

---

## 4. Design system

### Palette
iOS system colours. **White** background / light-grey cards, or **true black** / `#1C1C1E` cards in dark mode
(`values/colors.xml`, `values-night/colors.xml`). Accent `#007AFF` (`#0A84FF` in dark). **No pink / purple anywhere.**

### Liquid Glass (`GlassView`)
Used for the top bar, the floating tab capsule, sidebar, action sheet, Select toolbar, mini player and queue sheet.

1. Every pre-draw it draws the `source` view into a bitmap 1/8 of the size, box-blurs it (2 × H+V passes), and paints it back.
2. A frosted tint (white or `#1C1C1E`), a top "sheen", and a specular rim (hairline + bright top-left / bottom-right highlight).
3. It only re-blurs when the pixels behind it change (hash of the tiny bitmap) → no endless redraw loop.
4. **Fallbacks:** if anything throws, `broken = true` → plain frosted fill. On TV, car radios and low-RAM devices
   (`DeviceUi.lowPower`) live blur is skipped. The user can switch Liquid Glass off in Appearance.

### Icons
`FileIcons` draws a yellow Windows-style folder and white "pages" with a coloured type label (PDF, DOCX, XLSX, MP3, APK …) in a
100×100 design box (shared `Paint`s and `Path`s, no per-bind allocation). Real thumbnails replace them for photos, videos,
album art, PDF first page and APK icons (`ThumbLoader`).

### Animations
Fragment transitions (`res/anim`), list rise-in (`layout_anim`), Select bar slide, check-badge pop, sidebar stagger,
player cover scale, equalizer bars. All can be disabled with **Appearance → Animations** (`Prefs.animations`).

### Responsive
`DeviceUi.isLarge` (tablet ≥ 600 dp, TV, car) → rail + grids. Landscape phones get a smaller capsule
(`values-land/dimens_nav.xml`). Focus effects (`applyFocusEffect`) support D-pad / remote / rotary knobs.

---

## 5. Storage, permissions and root

| Android | Storage access |
|---|---|
| 5.0 – 5.1 | granted at install, no popup |
| 6 – 10 | `READ/WRITE_EXTERNAL_STORAGE` runtime popup, only if missing |
| 11+ | "All files access" (`MANAGE_EXTERNAL_STORAGE`) settings switch, only if off |

* Android 8+: `REQUEST_INSTALL_PACKAGES` ("Install unknown apps") is checked only when installing an APK.
* Android 13+: `POST_NOTIFICATIONS` is requested once when music starts.
* **Root features are shown only if an `su` binary exists** (`RootShell.deviceRooted`). `FileOps(root = true)` runs shell commands.
* `StorageVolumes.list` combines `StorageManager`, app dirs, `/proc/mounts` and a `/storage` scan to find USB / SD volumes;
  `MainActivity` also listens to media / USB broadcasts for live "USB drive connected" alerts.

---

## 6. Feature modules

### Music
`MusicPlayer` (singleton `MediaPlayer` + queue) → observers. `MusicService` is a foreground service (`mediaPlayback`) showing the
notification (cover, Previous / Play / Next / Stop, swipe = stop). `PlayerActivity` shows cover, equalizer, seek bar, queue sheet
(tap, remove from list, delete file → Recycle Bin). Setting: `Prefs.musicNotification`.

### Photos
`PhotoViewerActivity` (swipe through the folder, pinch / double-tap zoom, share, delete, edit) and `PhotoEditorActivity`
(crop with aspect presets, rotate, flip, 9 filters, brightness / contrast / saturation, undo; save as copy or replace).
Filters and sliders are combined into one `ColorMatrix` used for preview and for the saved image. JPEG quality 95.

### Video
`VideoPlayerActivity` (VideoView, ±10 s, seek bar). `VideoEditorActivity` trims, mutes and rotates **without re-encoding**:
`VideoTrimmer` copies samples between two times with `MediaExtractor` + `MediaMuxer` into an MP4 (start snaps to the previous
key frame). Works with H.264 / H.265 + AAC (MP4, MOV, 3GP); other codecs show a clear message.

### Office
`.docx` / `.xlsx` / `.pptx` are zips of XML. `OfficeDoc` reads / rewrites only the changed XML parts and copies the rest.

### USB tools
libaums reads the USB mass-storage device directly (works even when Android does not mount it): FAT32 browse / write,
rename label, quick-format as FAT32 (`FatFormatter`, MBR; wipes GPT) and merge partitions. NTFS / exFAT need root to mount.

### Updates
`Updater` calls the GitHub "latest release" API once a day (or from the sidebar), compares versions numerically
(`2.9.0.010 > 2.9.0.009`), downloads `MBFileManager.apk` and starts the installer.

---

## 7. Data stored on the phone

| Where | What |
|---|---|
| SharedPreferences `settings` | theme, transparency, Liquid Glass, animations, view mode, columns, root mode, music notification |
| SharedPreferences `tags` | file path → tag colour |
| `<volume>/.MBFileManagerTrash` | Recycle Bin items + `.origin` files |
| `Download/USB` | files copied from USB drives |

---

## 8. Build, signing and release

* **Only GitHub Actions builds the app** (`.github/workflows/build.yml`): `./gradlew assembleRelease`, then publishes the release.
* Branch: `claude/android-file-manager-app-vazx9g`. Every push builds and publishes release `v<versionName>` with
  `MBFileManager-v<versionName>.apk` and `MBFileManager.apk` (so `releases/latest/download/MBFileManager.apk` always works).
* **Signing:** the key lives only in GitHub Secrets (`SIGNING_KEY_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
  `SIGNING_KEY_PASSWORD`). Never commit a keystore or password. `setup-signing.bat` (Windows, run as administrator) creates the key and
  stores the secrets. Keep the same key forever, otherwise updates cannot install over old versions.
* To release: raise `versionCode` and `versionName` in `app/build.gradle.kts`, push, wait for the green build.

---

## 9. Known limits

* Tags are tied to the file path (lost when the file is moved or renamed).
* Video editing is lossless trimming / mute / rotate, not filters or re-encoding.
* NTFS / exFAT drives can only be mounted with root (otherwise format as FAT32).
* FAT32 files are limited to 4 GB.
* Live blur needs a device that can render the tiny snapshot in real time; weak devices use plain frosted glass.
* Android 11+ blocks `Android/data` and `Android/obb` for normal apps; root mode opens them.
