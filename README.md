<div align="center">

<img src="app-icon.png" width="128" alt="DrivePool icon">

# ☁️ DrivePool

### Pool all your Google Drive accounts into one beautiful Android dashboard

[![Build](https://github.com/Redowansky/DrivePooL/actions/workflows/android.yml/badge.svg)](https://github.com/Redowansky/DrivePooL/actions)
[![Version](https://img.shields.io/badge/version-0.11.6-blue.svg)](https://github.com/Redowansky/DrivePooL/releases)
[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-2024.09.00-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-PolyForm%20Noncommercial-blue.svg)](LICENSE)

**One home screen. Every Drive. One total.**

> 2 × 15 GB free Drives? DrivePool shows **30 GB total — 29 GB free, 100% free** — in one pooled bar, with a coloured bar for each account underneath.

[📥 Download the APK (v0.11.6)](https://pub-89e47025e7844f1eaf672a00cfdbba12.r2.dev/app/drivepool-debug.apk)

</div>

---

## ✨ What it does

| Feature | Details |
|---|---|
| 📊 **Pooled ring gauge** | Total used / total limit across *all* linked Drives, on a gradient ring with Used / Total stats and a Live Syncing pill |
| 🟦🟩🟨 **Per-account cards** | Each Drive gets its own card — email, used / limit, coloured progress bar, Connected status, and a red trash button to unlink it |
| 🛰️ **Orbit animation** | A little live orbit in the middle of the dashboard, just because it looks good |
| 🧮 **Stat tiles** | Today (GB added today) · Accounts linked · Total files · Total free space |
| 🔐 **Read-only by design** | Drive `readonly` scope only — the app can *see* your storage, never edit or delete files |
| 🗑️ **Red trash per account** | Tap the trash on an account card to unlink that Drive |
| 📂 **Browse files** | Tap an account card to open its files — folders, back navigation, load more; tap a file for its details |
| 🔍 **Search all Drives** | Top-bar search looks through every linked Drive by name, results grouped by account |
| ↔️ **Move files** | File details → Move → pick a Drive, pick a folder — the file downloads and re-uploads through your phone, then the original is deleted |
| ⬆️ **Pooled uploads** | Top-bar upload sends a file from your phone to the linked Drive with the most free space |
| ▶️ **Open in your apps** | File details → Open downloads the file and lets Android ask which player or app to use — DrivePool never plays media itself |
| 📏 **Exact file sizes** | Files show their real size (240 KB, 12.3 MB, 1.2 GB) instead of 0.0 GB |
| 🔔 **Background uploads** | Uploads and moves run in a foreground service with a persistent notification (progress bar + %) — they survive the app being swiped away, the screen going off, and Doze; uploads are resumable, so a killed transfer picks up where it stopped |
| ➕ **Floating + menu** | Google Drive-style + button at the bottom right — Upload Files and Add Account live under it; the top bar is just the brand and search |
| 🔀 **Server-side moves** | Moving a file copies it on Google's servers (share + files.copy across accounts) and verifies size + md5 before deleting the original — a move costs no data at all |
| 🗑️ **Trash** | Deletes go to trash (recoverable), with a Trash view to restore, delete forever, or empty the trash and reclaim quota |
| 🔍 **Browse & search** | Infinite scroll, sort by name/date/size, rename, and search filters for file type and date |

## 📱 Screenshots

> Screenshots are coming from a real phone test — the dashboard in v0.1.0 is the dark, polished design: black background, rounded cards, coloured bars, and the pooled total card at the bottom.

## 🚀 Get started

1. **Install** the APK above (allow install from unknown sources once). If you have v0.1.x installed, uninstall it first — v0.2.0 is signed with the stable release key, so it can't install over the debug build.
2. **Add Drive** → pick a Google account and grant Drive read-only access. That's it — no client ID to paste, no Console steps.
3. Repeat for each Drive. Watch the total bar grow: 15 + 15 = **30 GB**.

> ⚠️ **Sign-in error 10?** The Android OAuth client for package `app.pepper.drivepool` still needs adding in your Google Cloud project (Console → Credentials). One-time step — the app tells you on screen if it's missing.

## 🔒 Privacy

- **Own Cloud project** — sign-in runs through the DrivePool Google Cloud project (Drive read-only scope only).
- **Access tokens live in memory only** — never stored on disk.
- **Only email + quota numbers are stored locally** (SharedPreferences).
- **No account, no server, no tracking** — your Drives talk straight to Google.

## 🛠️ Built with

- **Kotlin 2.0.21** — 100% Kotlin, no Flutter, no WebView
- **Jetpack Compose** (BOM 2024.09.00) + Material 3 — the whole UI
- **Google Sign-In** (`play-services-auth`) + **Drive API v3** via OkHttp
- **GitHub Actions** — every push to `main` builds a debug APK (`assembleDebug`)

## 🗺️ Roadmap

- [x] **v0.1.0** — Dashboard, Add Drive, pooled total bar, stat tiles
- [x] **v0.1.1** — Clean UI flow: one Add Drive button, gear only opens Settings (with Save/Close), Refresh really re-reads quota
- [x] **v0.2.0** — Library-style sign-in: Web client ID baked in, Settings field removed, stable signed release builds
- [x] **v0.2.1** — Own launcher icon: blue cloud on dark, no longer Wansky's green triangle
- [x] **v0.2.2** — In-app updates (own OkHttp downloader, live progress, exact errors) + TB/PB storage sizes
- [x] **v0.2.3** — Unlimited accounts: Add Drive signs out first so the chooser always appears; Refresh re-reads every account
- [x] **v0.3.0** — Redesign (his mockup): ring gauge total card, collapsible accounts section, red trash on each account card
- [x] **v0.3.1** — Gauge refinement (his v2 mockup): ring shows only the used sliver, full "14.1 GB / 11.00 TB (0.13% used • 10.98 TB free)" line; app icon rebuilt from his cloud artwork, cloud centred in the safe zone so launchers can't crop it into a blue blob
- [x] **v0.4.0** — Browse files per account (tap a card, folders + back nav + load more), search across accounts
- [x] **v0.5.0** — Move files between Drives (pick a Drive, pick a folder), pooled uploads to the freest Drive; full Drive scope — every account re-approves once
- [x] **v0.5.1** — Exact file sizes (KB/MB/GB instead of 0.0 GB), Open files in external apps via Android's chooser
- [x] **v0.6.0** — Background uploads + moves: foreground service with persistent progress notification (bar + %), resumable uploads that continue from the last confirmed byte after a kill; updater download progress fixed to exact sizes too
- [x] **v0.7.0** — Cleaner dashboard: top bar is brand + search only (no Refresh, no Upload, no Add Drive button), Google Drive-style floating + menu with Upload Files and Add Account; title can no longer wrap as "DrivePoo l"
- [x] **v0.7.1** — Upload notifications actually appear (notification permission requested, channel created at app start); a one-off quota read failure no longer shows the scary "Drive API is enabled" error — the account keeps its last numbers and the refresh says so
- [x] **v0.8.0** — Safety + reliability pass (from an external code review): server-side moves (files.copy on Google's servers, verified by size + md5 before the original is deleted — a move costs no data now, with the old download+upload path as fallback, also verified); updater hardened (SHA-256 of the APK verified before install, User-Agent follows the real version); retries with backoff + Retry-After on 429/5xx everywhere; "Today" in the device timezone; honest "Synced HH:mm" label instead of "Live Syncing"; backups off; R8 minify + resource shrinking (smaller APK). New: Trash view (restore, delete forever, empty trash — deletes go to trash now, with confirmation), rename, infinite scroll + sort in the browser, type + date filters in search
- [x] **v0.8.1** — Floating upload button in the file browser: it follows you into every folder you open, and the picked file uploads straight into the folder on screen (on the account you are browsing) — no more uploading to the root and moving it after
- [x] **v0.9.0** — Multi-select in the file browser: long-press a file to start marking, tap to toggle, then **Copy to Drive** or **Move** — pick a destination account and folder, and the whole batch copies on Google's servers (verified by size + md5, originals only deleted for a move after verification). One file that can't be copied never blocks the rest. Folders can't be marked (a folder copy wouldn't copy its contents)
- [x] **v0.10.0** — The small gaps: **New folder** button in the browser (also in the move picker, so you can make the folder you're moving into); **Save to device** — downloads a file to the Downloads folder in the background service, like an upload; **folder move / rename** — long-press a folder for its details, move it within its own Drive (a single metadata call, no bytes — cross-account folder moves stay impossible, Google can't copy a folder's contents); **upload a whole folder** — the upload button now opens a menu (Upload files / Upload folder), subfolders are mirrored on Drive, and files already there with the same name and size are skipped so a restarted job resumes instead of duplicating
- [x] **v0.10.1** — **Select all** in the selection header — marks every file loaded in the folder in one tap (folders never marked). **Move within the same Drive** — the move picker now lists the account you're already on as "(this Drive)", so root → Images on the same account works: one metadata call per file, no bytes, no service; a batch move names the files that couldn't move instead of blocking the rest. Moving a file to the folder it is already in is a no-op, not an error
- [x] **v0.10.2** — **Move progress, like Drive/MEGA** — a batch move now shows a live dialog ("Moving 23 of 50…") with a progress bar instead of a frozen screen, and a single move shows a spinning bar. **The result is visible** — after a move you land back in the browser with a banner saying what happened ("Moved 50 files to Images." or "Moved 47, could not move 3: …"), dismissible with ✕. **Fewer files left behind** — Drive throttles bursts as a 403 rate-limit error, which the app used to take as a final failure; those now retry with backoff like 429s do
- [x] **v0.11.0** — **Settings** — a gear icon in the dashboard top bar opens a Settings screen. First setting: **Download folder** — pick any folder on your phone (including an SD card) and "Save to device" puts files there instead of Downloads; the choice is remembered, and a file with the same name is replaced, not duplicated. If the folder can't be opened anymore, saving tells you to pick it again instead of failing silently
- [x] **v0.11.1** — **Download multiple files + a proper download notification** — when files are marked, the old Copy/Move bottom bar is replaced by a floating button that opens a menu with **Copy to Drive**, **Move**, and the new **Download**: every marked file saves to the device one after another, into the same folder a single download uses (the one from Settings), with the notification counting "Saving 3 of 25…" and naming any file that couldn't be saved. The notification channel is now **Transfers** instead of Uploads, and a download shows a **download arrow** with the same live progress bar uploads have — the permission is now asked for downloads too, so the progress can actually show
- [x] **v0.11.2** — **The download bar actually moves** — Drive sends no file size with big downloads, so the bar had nothing to divide by and sat at 0% while bytes were flowing. The app now uses the size it already knows from the file listing, so the percentage climbs in real time — and the label shows it too, like "Saving INTERACTIVE_MUSIC.rpf — 12% · 368 MB of 2.99 GB"
- [x] **v0.11.3** — **Silent updater, the F-Droid way** — a background worker checks for updates every 6 hours, downloads a new version by itself (SHA-256 verified), and installs it with a PackageInstaller session — no tap at all when Android allows it, which it does for a self-update on Android 12+ when the app holds the install permission, declares `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, and targets a high enough API (DrivePool targets 34, the Android 16 bar). The new **Silent Updater** channel carries only the exceptions: a "tap to install" note if Android still wants one confirmation, a failure note with a retry tap, and "DrivePool updated" once the new build lands. One honest bootstrap catch: the very first self-update may still ask once — from the second update on, new versions just land
- [x] **v0.11.5** — **Downloads resume instead of restarting** — a dropped connection used to throw the whole file away ("Download failed. Nothing was saved.") and the next try started from zero, so a 3 GB file dying at 50% lost 1.5 GB of progress. Now the partial file is kept, each attempt resumes from its real size with a Range request and a fresh login, up to 3 tries, and a failure finally says how far it got and why — "Download stopped at 1.5 GB of 2.99 GB — connection reset. Tap Download again to pick up where it stopped." Also folded in: the download label stops lying (the v0.11.4 byte-formatter off-by-one — "159.6 GB of 2.8 TB" for a 3 GB file — never shipped on its own)
- [x] **v0.11.6** — **Downloads stop dying on a 10-second stall** — v0.11.5's resume worked, but every attempt still used OkHttp's default 10 s read timeout, so a stall longer than 10 s killed the stream ("timeout") and the retries, fired 1 s / 3 s later, hit the same stalled connection again at the same 1.5 GB. Downloads now get their own client with a 120 s read timeout (a download that stalls for two minutes is still a live download), and retries wait 5 s / 30 s so a short throttle window can pass first.

## 🤝 Contributing

DrivePool is Redowan's personal project, open for friends to poke at. Issues and pull requests welcome — keep it simple, dark, and honest about what it can and can't do.

## 📄 License

PolyForm Noncommercial 1.0.0 — see [LICENSE](LICENSE).

Free for personal and noncommercial use. You may use, share, and modify it, but you may **not** use it commercially — selling it, charging for it, or building a paid product or service on it needs the author's permission first.

---

<div align="center">

Made with 🌶️ by [Redowansky](https://github.com/Redowansky) & Pepper

</div>
