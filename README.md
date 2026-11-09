<div align="center">

<img src="app-icon.png" width="128" alt="DrivePool icon">

# ☁️ DrivePool

### Pool all your Google Drive accounts into one beautiful Android dashboard

[![Build](https://github.com/Redowansky/DrivePooL/actions/workflows/android.yml/badge.svg)](https://github.com/Redowansky/DrivePooL/actions)
[![Version](https://img.shields.io/badge/version-0.6.0-blue.svg)](https://github.com/Redowansky/DrivePooL/releases)
[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-2024.09.00-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-PolyForm%20Noncommercial-blue.svg)](LICENSE)

**One home screen. Every Drive. One total.**

> 2 × 15 GB free Drives? DrivePool shows **30 GB total — 29 GB free, 100% free** — in one pooled bar, with a coloured bar for each account underneath.

[📥 Download the APK (v0.6.0)](https://pub-89e47025e7844f1eaf672a00cfdbba12.r2.dev/app/drivepool-debug.apk)

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

## 🤝 Contributing

DrivePool is Redowan's personal project, open for friends to poke at. Issues and pull requests welcome — keep it simple, dark, and honest about what it can and can't do.

## 📄 License

PolyForm Noncommercial 1.0.0 — see [LICENSE](LICENSE).

Free for personal and noncommercial use. You may use, share, and modify it, but you may **not** use it commercially — selling it, charging for it, or building a paid product or service on it needs the author's permission first.

---

<div align="center">

Made with 🌶️ by [Redowansky](https://github.com/Redowansky) & Pepper

</div>
