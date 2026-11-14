# QUEUE-BACKPRESSURE.md – Queue Backpressure & Overload Audit

**Project:** MB File Manager (Android, Kotlin, package `com.mbnetwork.filemanager`)
**Status (v2.9.0.041):** findings F-01 to F-07 and potential risk P-02 were fixed in code (compiled by CI, **not tested on a device**). P-01, P-03, P-04 and P-05 were deliberately left unchanged (see section 14). The findings below describe the code as audited, before these fixes.
**Audit type:** read-only source inspection. No code, config, dependency or infrastructure was changed. **No test, load test or device run was performed.** Everything below comes from reading source; statements such as "would happen" are reasoning, not observation.
**Scope note:** this is a single-user on-device app. There is no server, message broker, database server, HTTP API or multi-tenant traffic. "Queue" here means in-process work buffers (thread-pool queues, coroutine launches, the music queue, the shared operation-progress state) and "overload" means a phone or car radio running out of threads, memory, main-thread time or network patience.

---

## 1. Executive Summary

- **No message broker or job framework exists.** No WorkManager, JobScheduler, RabbitMQ, Kafka, SQS, Room or server (checked by search; the only background mechanisms are coroutines, three `java.util.concurrent` executors and raw `Thread`s).
- Work enters only through **user taps** (and the Android share sheet). There is no external producer that can flood the app, so classic queue overload is mostly **not applicable**. The real risks are: one operation corrupting the state of another, work that can never be cancelled, blocking calls with no timeout, and main-thread work that grows with library size.
- **7 confirmed findings** (code-evidenced, not reproduced) and **5 potential risks / unknowns** are listed in sections 11 and 14.
- **No retry mechanism exists anywhere**, so retry storms are not possible (section 7). The cost is no automatic recovery.
- **No metrics, logging or alerting exists** (section 9). Overload cannot be detected by an operator; only the user sees a progress bar.
- Biggest item: **F-01** – if the activity is destroyed during a copy / move / delete (for example the user presses Back), the completion call is skipped, so the shared progress state and the foreground notification can stay "active" until the process dies.

Overall readiness for the app's real use case: **acceptable for typical use, with several robustness gaps** (section 15).

---

## 2. Project Architecture Overview

| Item | Evidence |
|---|---|
| Language / UI | Kotlin, Android Views, Material 1.12, min API 21 (`docs/CLAUDE.md`) |
| Entry points | `MainActivity` (launcher, `AndroidManifest.xml`), `ShareReceiverActivity` (share target), player / viewer / editor activities, `remote/RemoteHomeActivity`, `remote/RemoteBrowserActivity` |
| Services | `OpService` (`foregroundServiceType="dataSync"`), `MusicService` (`mediaPlayback`) – both declared in `app/src/main/AndroidManifest.xml` |
| Concurrency primitives | Kotlin coroutines (`lifecycleScope`, `Dispatchers.IO`), `Executors.newFixedThreadPool(3)` (`ThumbLoader.kt:18`), `Executors.newFixedThreadPool(48)` per scan (`remote/LanScanner.kt:26`), `Executors.newSingleThreadExecutor()` (`remote/RemoteBrowserActivity.kt:67`), raw `Thread {}` (`RootShell.kt`, `remote/RemoteHomeActivity.kt:245`, `MusicService.kt:99`) |
| Network | okhttp client `remote/Http.kt`, commons-net FTP (`remote/FsLan.kt`), jcifs-ng SMB, jsch SFTP, `HttpURLConnection` in `Updater.kt` |
| Storage index | MediaStore queries (`FileIndex.kt`, `MusicPlayer.kt`) |
| Root | `su` subprocess per command (`RootShell.kt`) |

---

## 3. Queue and Workload Discovery

| Workload | Source | Sync / async | Can accumulate? | Queue / buffer | Capacity exhausted → | Worker crash |
|---|---|---|---|---|---|---|
| Copy / move / delete many items | `FilesFragment.runWithProgress` | async (coroutine, `Dispatchers.IO`) | Yes, user can start another while one runs | none (each launch runs immediately) | no admission control (F-02) | exception per item is caught; loop continues |
| Single delete / rename / mkdir | `FilesFragment.runOp` | async (IO) | Yes | none | swipe spinner only | `runCatching` → toast |
| Thumbnails | `ThumbLoader.load` | async, fixed pool of 3 | Yes (fast scroll) | pool's internal unbounded `LinkedBlockingQueue` | tasks wait; stale tasks still run (F-06) | `catch Throwable` → null |
| Category listing (images, music, duplicates…) | `FileIndex.list` via `FilesFragment` loader (line ~199) | async (IO) | Possible overlap (P-02) | none | slow query | `getOrDefault(emptyList())` |
| Duplicate finder | `DuplicateFinder.find` | inside category loader | n/a | none | CPU / IO heavy (P-01) | per-file `catch` |
| Music queue | `MusicPlayer.queue` | in-memory `List<File>` | Bounded by songs on device | the list itself | n/a | n/a |
| Music library load | `MusicPlayer.library` | **main thread** on player open (F-04) | n/a | none | UI stall | `catch Throwable` → empty |
| LAN scan | `RemoteHomeActivity` → `LanScanner.scan` | raw Thread + 48-thread pool | Yes, each tap | pool per scan | cancel does not stop it (F-05) | per-host `catch` |
| Remote browse / download / upload | `RemoteBrowserActivity` | single-thread dispatcher | Yes, user taps queue behind each other | executor's unbounded queue | strictly serial | caught → toast |
| Share-sheet save | `ShareReceiverActivity.save` | async (IO), modal non-cancellable dialog | Bounded by shared items | none | n/a | per-item `catch`, counted |
| App update download | `Updater.download` | async | no | none | n/a | caught |
| Root commands | `RootShell.run` | blocking call on caller's IO thread | per item | none | no timeout (F-03) | exit code → `IOException` |
| Webhooks, payments, email, scheduled jobs, AI/LLM network | **not present** (AI is on-device only, enforced by a CI step in `.github/workflows/build.yml`) | n/a | n/a | n/a | n/a | n/a |

---

## 4. Producer-Consumer Flow

1. **File operations:** user selects items → `runWithProgress(title, paths, action)` (`FilesFragment.kt:827`) calls `OpProgress.start`, starts `OpService` as a foreground service, then launches a coroutine in the **activity's** `lifecycleScope` that loops over paths on `Dispatchers.IO`, calling `OpProgress.step()` after each item and `OpProgress.finish(msg)` at the end.
2. **Progress consumers:** `MainActivity` (in-app bar, `updateOpBar`) and `OpService` (notification) both listen to the `OpProgress` singleton (`OpProgress.kt`). `OpService` **does not do the work**; it only mirrors the state.
3. **Thumbnails:** adapter bind → `ThumbLoader.load` → cache hit, otherwise `pool.execute`; result is posted to main and applied only if `view.tag == path`.
4. **Music:** tap → `MusicPlayer.playFolder` builds a queue of the whole library, one `MediaPlayer` at a time, audio focus handled in `MusicPlayer`.
5. **Remote:** every server action goes through one single-thread dispatcher so FTP / SSH sessions are never used concurrently.

---

## 5. Backpressure Implementation Analysis

| Mechanism | Present? | Evidence |
|---|---|---|
| Bounded queues | No. Executors use default unbounded queues | `ThumbLoader.kt:18`, `RemoteBrowserActivity.kt:67` |
| Admission control for operations | **No** – `runWithProgress` never checks `OpProgress.active` | `FilesFragment.kt:827-843` |
| Rate limiting / load shedding | No (not needed for network ingress; absent for local operations) | – |
| Request deadlines | Network only (section 8). None for local file work or `su` | `Http.kt`, `FsLan.kt`, `RootShell.kt` |
| Natural backpressure | Remote I/O is serial by design (single-thread dispatcher). `ThumbLoader` is capped at 3 threads | as above |
| Cancellation | Remote download has a cancel flag (`CountingOut`, `RemoteBrowserActivity.kt:264`). **Copy / move / delete has none.** LAN scan cancel is cosmetic | F-05, F-07 |

---

## 6. Queue Capacity and Concurrency

| Pool / scope | Size | Queue bound | Notes |
|---|---|---|---|
| `Dispatchers.IO` (file ops, listings, MediaStore, su) | shared default (64 threads max) | none | everything file-related shares it (no bulkhead, P-05) |
| `ThumbLoader.pool` | 3 | unbounded | LRU cache holds 120 entries (`LruCache<String, Any>(120)`) |
| `LanScanner` pool | 48 per scan | unbounded (254 tasks max per scan) | new pool each scan, `shutdown()` then `awaitTermination(60s)` |
| `RemoteBrowserActivity.io` | 1 | unbounded | closed in `onDestroy` |
| `MusicPlayer` | 1 `MediaPlayer` | n/a | queue = all songs on device |
| `PhotoEditorActivity.undo` | 6 bitmaps | bounded (`push()` trims at 6) | memory-conscious |

---

## 7. Retry and Failure Handling

- **No retry, backoff, jitter, dead-letter queue, or redelivery logic exists.** A search for `retry`, `Retry`, `backoff` across `app/src/main/java` found no implementation. Failed operations show a toast and stop.
- **Retry storms:** not possible by construction. Trade-off: transient network errors need a manual retry.
- **Duplicate processing / idempotency:** copy and move use `uniqueTarget` (`FileOps.kt:94`) to avoid overwrite, so repeating a copy creates "name (1)" rather than corrupting data. Remote `replace` overwrites by design.
- **Per-item failure handling:** `runWithProgress` catches each item's exception, keeps only the **last** error message, and continues (`FilesFragment.kt:836`).
- **Worker crash recovery:** there is no persistent job state. If the process dies mid-copy, the operation is simply lost; a partially copied folder may remain (not verified on device). `OpService` returns `START_NOT_STICKY`, so it is not restarted.
- **Poison items:** one failing path cannot block later paths.

---

## 8. Resource Protection

| Resource | Protection found | Gap |
|---|---|---|
| Threads | thumbnail pool capped at 3; remote serial | LAN scan creates up to 48 threads per scan, new each time |
| Memory | thumbnails sub-sampled to ≈200 px; `LruCache(120)`; undo stack capped at 6 | `FileIndex.query` allows up to 100 000 rows (`dup`); `MusicPlayer.library` is unbounded (by design: "no limit") |
| Network timeouts | okhttp connect 20 s / read 120 s / write 120 s (`Http.kt`); FTP connect 15 s, default 30 s, data 60 s (`FsLan.kt`); SMB 10 s / 20 s; SFTP 20 s; `Updater` 10 s / 10 s | no `callTimeout` on okhttp |
| Local file work | none | no time limit, no cancel |
| `su` subprocess | none | `RootShell.run` calls `p.waitFor()` with no timeout; stdout is read with `readText()` (unbounded) |
| Disk | cache cleanup (`tmp.delete()` in `finally` on upload) | upload copies the **whole** file to cache first (P-04) |
| Cross-workload isolation | none | a stuck `su` call or large duplicate scan occupies shared `Dispatchers.IO` threads |
| Foreground service | declared `dataSync` type + permission | state can be left active (F-01) |

---

## 9. Observability and Monitoring

| Metric | Exists? | Where |
|---|---|---|
| Items done / total for file ops | Yes (user-facing only) | `OpProgress.done/total`, in-app bar and notification |
| Queue depth, wait time, worker utilisation, throughput | No | – |
| Failed / retried counts | No (last error only) | `runWithProgress` |
| Rejected requests | N/A | – |
| Logs, crash reporting, alerts, dashboards | None found in source (Firebase / Play Services are intentionally banned, `docs/CLAUDE.md`) | – |

Operators cannot see overload. The only external signal is Android's own ANR / crash data, which the app does not collect (unknown whether the owner reviews it).

---

## 10. Real-World Failure Scenarios

*All classifications are from code inspection only. None was tested.*

| # | Scenario | Result | Reasoning |
|---|---|---|---|
| A | 10× traffic spike | **Not applicable** (no external traffic). User-equivalent: rapid repeated taps / scrolling → thumbnails queue up, stale tasks still run (F-06) – *partially protected* | pool of 3 prevents thread explosion |
| B | Workers slower than arrivals (second copy started during first) | **Vulnerable** (F-02) | shared counters reset, two loops step one counter |
| C | All workers unavailable | **Partially protected** | no unbounded intake exists; but if `OpService` fails to start the work still runs, only without the notification (`FilesFragment.kt:831`) |
| D | External server slow | **Partially protected** | timeouts exist (≤120 s read); serial executor means later actions wait behind a slow one; no `callTimeout` (P-03) |
| E | DB connection exhaustion | **Not applicable** (no database server; MediaStore only) |
| F | Retry storm | **Protected** (no retries exist) |
| G | Large payloads / files | **Partially protected**: copy streams via `copyRecursively` and `copyTo` (not fully buffered); upload stages a full copy in cache (P-04); `DuplicateFinder` reads in 64 KB chunks |
| H | Recovery after outage | **Not applicable** (no persistent backlog; operations are lost on process death) |

---

## 11. Confirmed Findings and Evidence

"Confirmed" = the behaviour follows directly from the source code shown. None was reproduced on a device.

### F-01 – Progress state can stay "active" when the activity is destroyed mid-operation
- **Severity:** Medium (confidence: high from code reading; not reproduced)
- **Component:** `FilesFragment.runWithProgress` (`FilesFragment.kt:827-843`), `OpProgress`, `OpService`
- **Evidence:** the work runs in `act.lifecycleScope.launch { withContext(Dispatchers.IO) { for (p in paths) { action(p); OpProgress.step() } } ... OpProgress.finish(msg) }`. `OpService` only observes `OpProgress` and stops itself when `OpProgress.active` becomes false.
- **Explanation:** if the activity finishes (for example the user presses Back) while the loop runs, its scope is cancelled. The IO loop has no suspension point or `isActive` check, so it keeps going, but when `withContext` returns the cancellation is thrown and the lines after it, including `OpProgress.finish`, do not run.
- **Impact:** the foreground "Copying…" notification and `OpProgress.active = true` remain until the process is killed; the in-app bar would reappear stuck on the next launch of the same process.
- **Trigger:** leave the activity (not rotate: `MainActivity` handles `configChanges`) during a copy / move / delete.
- **Existing protection:** per-item exception handling only.
- **Mitigation:** run the loop in an application-level scope tied to the service lifetime, or call `finish` in a `finally`/`NonCancellable` block. *Risk:* running on an app-level scope means `refresh()` and toasts must tolerate a missing UI.

### F-02 – No admission control for concurrent copy / move / delete
- **Severity:** Medium
- **Evidence:** `runWithProgress` calls `OpProgress.start(...)` unconditionally; `start` resets `done = 0`, replaces `total`, `title`, `result`. `step()` does `done++` on a `@Volatile` field (not atomic).
- **Impact:** starting a second operation while one is active makes both loops update one counter; the bar jumps, the first operation's `finish` marks the state inactive while the second still runs, and the notification can disappear early. Overlapping operations on the same paths are not prevented.
- **Mitigation:** reject / queue a second operation while `OpProgress.active`, and make the counter atomic. *Risk:* user-visible behaviour change (a "busy" message or a pending queue).

### F-03 – Root shell commands have no timeout and spawn one `su` process each
- **Severity:** Medium (root devices only)
- **Evidence:** `RootShell.run` (`RootShell.kt`) calls `Runtime.exec("su")`, `readText()` on stdout, then `p.waitFor()` with no deadline. `FileOps.exists` (`FileOps.kt:104`) runs a separate `su` per probe; `uniqueTarget` loops over `(1), (2), …` and probes each.
- **Impact:** a hung command, or a root manager prompt left unanswered, blocks an IO thread indefinitely; in a directory with many numbered copies each collision costs a process launch.
- **Mitigation:** timeout with `destroy()`; batch the existence probes in one shell call. *Risk:* killing a half-finished `cp`/`mv` can leave partial data.

### F-04 – Music library is read on the main thread when the player opens
- **Severity:** Medium
- **Evidence:** `PlayerActivity.kt:189` calls `MusicPlayer.playFolder(File(it))` directly; `playFolder` calls `library(it)` (`MusicPlayer.kt:60`) which runs a MediaStore query and a `File.isFile` check per song with no limit.
- **Impact:** UI stall grows with library size; a very large library on a slow storage (car radio, SD card) could trigger an ANR (not measured).
- **Mitigation:** build the queue off-thread, start the tapped song immediately, fill the rest afterwards. *Risk:* queue contents change slightly after start (UI must handle it).

### F-05 – LAN scan cancel does not stop the scan; each scan creates a new 48-thread pool
- **Severity:** Low
- **Evidence:** `RemoteHomeActivity.kt:244-262` uses `setNegativeButton(R.string.cancel, null)` and a raw `Thread`; `LanScanner.scan` (`remote/LanScanner.kt:23-45`) uses `pool.shutdown()` + `awaitTermination(60s)`, never `shutdownNow()`, and has no cancel flag.
- **Impact:** tapping Scan repeatedly stacks scans (up to 254 hosts × 3 ports × 400 ms connect timeout each) in the background, wasting battery and network; bounded by ~60 s per scan.
- **Mitigation:** cancel flag + `shutdownNow()`; ignore a new scan while one runs.

### F-06 – Thumbnail pool queue is unbounded and stale requests are not dropped
- **Severity:** Low
- **Evidence:** `ThumbLoader.load` (`ThumbLoader.kt:31-40`) enqueues `pool.execute` for every bind that misses the cache and checks `view.tag == path` only **after** decoding.
- **Impact:** fast scrolling through a large folder queues many decodes whose result is discarded; the 3 threads stay busy and visible rows load late. Memory is capped by the 120-entry cache.
- **Mitigation:** drop tasks whose view was rebound before they start (check the tag at the start of the task).

### F-07 – Copy / move / delete cannot be cancelled and progress counts items, not bytes
- **Severity:** Low
- **Evidence:** `runWithProgress` has no cancel flag; `FileOps.copy/move` use `copyRecursively` and `deleteRecursively` on whole folders; `OpProgress.step()` is called once per top-level path.
- **Impact:** one large folder or file shows no progress and cannot be stopped; "Hide" only hides the bar.
- **Mitigation:** cancel flag and byte-level progress. *Risk:* partial results need defined clean-up rules.

---

## 12. Risk Severity Matrix

| ID | Type | Severity | Likelihood (reasoned) | Impact |
|---|---|---|---|---|
| F-01 | Confirmed | Medium | Medium (user leaves app during copy) | stuck notification / bar |
| F-02 | Confirmed | Medium | Medium | wrong progress, early "done" |
| F-03 | Confirmed | Medium | Low (root only) | blocked IO thread |
| F-04 | Confirmed | Medium | Medium on large libraries | UI stall / possible ANR |
| F-05 | Confirmed | Low | Low | wasted battery / network |
| F-06 | Confirmed | Low | Medium | slow thumbnails |
| F-07 | Confirmed | Low | High (feature absent) | cannot stop long operation |
| P-01 – P-05 | Potential | see section 14 | – | – |

No Critical or High severity finding was identified.

---

## 13. Recommended Mitigations

Prioritised, only where justified by the actual code. None has been implemented.

1. **F-01 + F-02 together (one small change):** make operation state robust – `finish` in a `finally` / `NonCancellable`, atomic counter, and refuse or queue a second operation while one is active.
2. **F-04:** move `MusicPlayer.library` off the main thread.
3. **F-03:** add a timeout and `destroy()` to `RootShell.run`; batch existence checks.
4. **F-06 / F-05 / F-07:** cheap hygiene (drop stale thumbnail tasks, real scan cancel, cancel button for operations).

Not recommended for this architecture: brokers, dead-letter queues, circuit breakers, rate limiting or retry/backoff libraries; there is no remote producer or shared downstream service that would benefit.

*Compatibility note for any fix:* keep Android 5 (API 21) support, and per `docs/CLAUDE.md` do not change existing UI beyond adding behaviour, and do not add network code to the guarded editing / AI files.

---

## 14. Missing Information and Unknowns

| ID | Item | Why unknown |
|---|---|---|
| P-01 | **Duplicate finder cost** | `FileIndex.list(..., "dup")` loads up to 100 000 media rows and fully hashes every same-size file ≤64 MB (`DuplicateFinder.kt`); no cancel. Real duration on large libraries not measured. |
| P-02 | **Overlapping category loads** | `FilesFragment` runs the loader in IO (line ~199); whether a previous in-flight `refresh()` job is cancelled when a new one starts was not verified. |
| P-03 | **okhttp without `callTimeout`** | `Http.kt` sets connect / read / write only; a server trickling data could keep a call alive longer than expected. |
| P-04 | **Upload stages a full copy in cache** | `RemoteBrowserActivity.upload` copies each file to `cacheDir2()` before uploading; behaviour on near-full storage not tested. |
| P-05 | **No bulkhead on `Dispatchers.IO`** | listings, file operations, MediaStore and `su` all share it; real contention under load not measured. |

Other unknowns: behaviour on real devices, TV and car radios; whether partial copies are left behind after process death; whether Android vitals / crash data is reviewed externally; the 4 cloud-provider SDK internals (jcifs-ng, jsch, commons-net) were not audited.

---

## 15. Production Readiness Assessment

| Area | Rating | Comment |
|---|---|---|
| Ingress overload | Not applicable | no external producers |
| Local operation robustness | Partially ready | F-01, F-02, F-07 |
| Main-thread safety | Partially ready | F-04 |
| Network timeouts | Mostly ready | P-03 only |
| Resource caps | Partially ready | thumbnails capped; scans and listings not |
| Retry / failure behaviour | Ready (simple, no storms) | no auto-recovery |
| Observability | Not ready | none present |

Verdict: suitable for its current single-user use; fixing F-01 / F-02 / F-04 would remove the most visible failure modes. Nothing here was verified on a device.

---

## 16. Final Audit Checklist

- [x] Project structure inspected (source tree, manifest, CI workflow)
- [x] No broker / job framework present (stated, not invented)
- [x] Every workload source classified (section 3)
- [x] Retry mechanisms searched; none found
- [x] Resource limits and timeouts listed with file references
- [x] Observability gaps listed
- [x] Scenarios A–H classified, marked as code-inspection only
- [x] Findings separated into confirmed (7) and potential (5)
- [x] No tests, load tests or device runs performed
- [x] No source, config, dependency or doc file modified; this file is new (`docs/QUEUE-BACKPRESSURE.md`)

---
*Files inspected:* `OpProgress.kt`, `OpService.kt`, `FileOps.kt`, `RootShell.kt`, `ThumbLoader.kt`, `FileIndex.kt`, `DuplicateFinder.kt`, `MusicPlayer.kt`, `PlayerActivity.kt`, `ShareReceiverActivity.kt`, `FilesFragment.kt`, `MainActivity.kt`, `PhotoEditorActivity.kt`, `Updater.kt`, `remote/Http.kt`, `remote/FsLan.kt`, `remote/LanScanner.kt`, `remote/RemoteBrowserActivity.kt`, `remote/RemoteHomeActivity.kt`, `app/src/main/AndroidManifest.xml`, `.github/workflows/build.yml` (all under `app/src/main/java/com/mbnetwork/filemanager/` unless a full path is shown).
