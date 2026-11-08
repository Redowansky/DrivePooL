<div align="center">

# ☁️ DrivePool

### Pool all your Google Drive accounts into one beautiful Android dashboard

[![Build](https://github.com/Redowansky/DrivePooL/actions/workflows/android.yml/badge.svg)](https://github.com/Redowansky/DrivePooL/actions)
[![Version](https://img.shields.io/badge/version-0.1.0-blue.svg)](https://github.com/Redowansky/DrivePooL/releases)
[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-2024.09.00-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

**One home screen. Every Drive. One total.**

> 2 × 15 GB free Drives? DrivePool shows **30 GB total — 29 GB free, 100% free** — in one pooled bar, with a coloured bar for each account underneath.

[📥 Download the APK (v0.1.0)](https://pub-89e47025e7844f1eaf672a00cfdbba12.r2.dev/app/drivepool-debug.apk)

</div>

---

## ✨ What it does

| Feature | Details |
|---|---|
| 📊 **Pooled total bar** | Total used / total limit across *all* linked Drives, on one gradient bar (blue → green → yellow) |
| 🟦🟩🟨 **Per-account cards** | Each Drive gets its own card — email, used / limit, coloured progress bar, Connected / Syncing status |
| 🛰️ **Orbit animation** | A little live orbit in the middle of the dashboard, just because it looks good |
| 🧮 **Stat tiles** | Today (GB added today) · Accounts linked · Total files · Total free space |
| 🔐 **Read-only by design** | Drive `readonly` scope only — the app can *see* your storage, never edit or delete files |
| 👆 **Long-press to remove** | Long-press any account card to unlink it |

## 📱 Screenshots

> Screenshots are coming from a real phone test — the dashboard in v0.1.0 is the dark, polished design: black background, rounded cards, coloured bars, and the pooled total card at the bottom.

## 🚀 Get started

1. **Install** the APK above (allow install from unknown sources once).
2. **Settings ⚙️** → paste your **Web client ID** from your own Google Cloud project — the same project you use for Round Sync / rclone. It stays on your phone only, nothing is baked into the app.
3. **Add Drive** → sign in with Google and grant Drive read-only access.
4. Repeat for each Drive. Watch the total bar grow: 15 + 15 = **30 GB**.

> ⚠️ **Sign-in error 10?** The Android OAuth client for package `app.pepper.drivepool` still needs adding in your Google Cloud project (Console → Credentials). One-time step — the app tells you on screen if it's missing.

## 🔒 Privacy

- **No OAuth client baked in** — you bring your own Web client ID.
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
- [ ] **v0.2** — Browse files per account, search across accounts
- [ ] **v0.3** — Move files between Drives, union-style pooled uploads (most-free-space first)
- [ ] **v0.4** — Signed release builds + in-app updater

## 🤝 Contributing

DrivePool is Redowan's personal project, open for friends to poke at. Issues and pull requests welcome — keep it simple, dark, and honest about what it can and can't do.

## 📄 License

MIT — see [LICENSE](LICENSE).

---

<div align="center">

Made with 🌶️ by [Redowansky](https://github.com/Redowansky) & Pepper

</div>
