# CLAUDE.md – project memory for AI assistants

Read this first. It records what the project is, how it is built, what the owner wants, and the mistakes already made.
Technical details are in [TECHNICAL.md](TECHNICAL.md). User-facing text is in [../README.md](../README.md).

## The project
* **MB File Manager** – an Android file manager + app manager by **MB Network** (© 2026, all rights reserved).
* Repo: `mbnetworku/MBFileManager`. Work branch: `claude/android-file-manager-app-vazx9g`.
* Min Android **5.0 (API 21)**. Must also work on **Android TV** and **Android car radios**, in portrait and landscape.
* Package `com.mbnetwork.filemanager`. Kotlin + Views + Material 1.12. No Compose.

## Hard rules
1. **Never put secrets in the repo, chat or docs.** Signing key / passwords live only in GitHub Secrets
   (`SIGNING_KEY_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`). If the owner pastes a password
   or uploads a file with one, do not use or repeat it, delete the uploaded copy, and suggest making a new key.
   Never commit `*.jks`, `*.keystore`, `mbfm-password.txt` (they are in `.gitignore`).
2. **There is no Android SDK in the sandbox** (`dl.google.com` is blocked). You cannot compile locally. **GitHub Actions is the compiler.**
   After every push, check the run (`actions_list` → `get_job_logs`), fix errors, push again. Say plainly when something is
   compiled but **not tested on a device**.
3. **Do not create a pull request unless asked.** Push to the work branch.
4. **Colours: no pink or purple.** Palette is iOS system colours; white or true-black backgrounds.
5. Keep **Android 5 compatibility**: guard newer APIs with `Build.VERSION.SDK_INT`, avoid APIs above 21 without a check.
6. Commit messages end with the attribution lines the harness gives. Do not put model names in code or files.

## Release routine (every change the owner asks for)
1. Edit code. 2. Raise `versionCode` (+1) and `versionName` (`2.9.0.00N`, N+1) in `app/build.gradle.kts`.
3. `git add -A && git commit && git push -q origin claude/android-file-manager-app-vazx9g`.
4. Wait ~3 min (`sleep` in background), check the build, fix errors if red.
5. Tell the owner the download link: `https://github.com/mbnetworku/MBFileManager/releases/latest/download/MBFileManager.apk`.
   It installs as an **update** (same signing key). Say what was and was not tested.

## What the owner wants (design decisions already made)
* UI like the **iOS Files app** (Browse screen with Locations / Favorites / Tags, "…" menu, Select mode with batch toolbar)
  and **Liquid Glass** bars (live blur; white / black only). Floating rounded bottom capsule, small (smaller still in landscape).
* **Windows-style file icons** (yellow folder, white page with type label). **Every row / card rounded.**
* Home screen = **Browse**; **no storage card at the top of the file list**.
* Theme options: White, Black, System. Transparency slider. Animations on / off. Music notification on / off.
* Sidebar (hidden, swipe or ☰): storage with usage bars, quick folders, USB tools, Appearance, Updates, About and copyright.
* Root features **only on rooted phones** (detect `su`); never show root options otherwise.
* Install / uninstall apps; open APK from the file list; update the app from inside.
* USB: detect OTG / SD live; format as FAT32, rename, merge partitions, browse + write FAT32.
* Media: music player (cover, equalizer animation, queue with delete, notification, mini player), photo viewer + editor,
  video player + editor (trim / mute / rotate), Office viewer + editor, notepad, zip / unzip, Recycle Bin, colour tags.
* Version names look like `2.9.0.001`.
* Owner prefers short, clear answers, one download link, and honesty about what is untested. They often write in broken English /
  speech-to-text: infer the intent, and ask only when truly blocked.
* Windows tooling preference: if asked for PowerShell tools, make them integrate with a local Web UI started from a `.bat` that
  runs **as administrator** (local admin rights needed).
* Docs live in the **`docs/`** folder (this file, `TECHNICAL.md`). The root `CLAUDE.md` only points here.

## Mistakes already made – avoid them
* Kotlin Int literals above `0x7FFFFFFF` (e.g. `0xFF123456`) are `Long` → append `.toInt()`.
* Do not use `catch (_: Throwable)`; use `catch (e: Throwable)`.
* A removed menu id still referenced in Kotlin breaks the build (e.g. `R.id.nav_trash`). Keep ids in `values/ids.xml` or update the code.
* Do not use `window.setBackgroundDrawable` + translucent surfaces without a solid base: text became unreadable. Sidebar / sheets
  must stay readable (use `GlassView`, tint ≥ 0.6).
* `MaterialToolbar` icons that are white vectors vanish on a light glass bar: use `?attr/colorControlNormal` tint.
* Keep `NavigationBarView` indicator / padding attributes to ones that definitely exist (`itemPaddingTop/Bottom`, indicator style).
* Never block the UI thread with file / USB work; use `lifecycleScope` + `Dispatchers.IO`.
* The `sleep N` wait must run in the background (`run_in_background`); long foreground sleeps are blocked.

## Where things are
See `docs/TECHNICAL.md` section 2 (file map). Quick pointers:
navigation → `MainActivity.kt`; glass → `GlassView.kt`; icons → `FileIcons.kt`; file list → `FilesFragment.kt` + `FileAdapter.kt`;
Browse → `HomeFragment.kt`; music → `MusicPlayer.kt` / `MusicService.kt` / `PlayerActivity.kt`; media editors → `Photo*`, `Video*`;
USB → `UsbToolsActivity.kt` + `FatFormatter.kt`; CI → `.github/workflows/build.yml`.

## Checklist when adding a feature
- [ ] Works on Android 5+, phone portrait + landscape, tablet, TV (D-pad focus), car radio.
- [ ] Colours from `values/colors.xml` (light + night), rounded corners, glass bars not covered (`topInset` / `bottomInset`).
- [ ] Strings in `res/values/strings.xml` (no duplicates – grep first).
- [ ] New activity registered in `AndroidManifest.xml`; permissions added only if needed.
- [ ] Version bumped, README feature list and `docs/TECHNICAL.md` updated.
- [ ] Pushed, CI green, owner told what to test.
