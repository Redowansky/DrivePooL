# DrivePool

A personal Android dashboard that pools your own Google Drive accounts into one view: per-account storage bars, a pooled total bar, and Today / Accounts / Files / Free tiles. Dark, polished Compose UI.

- Package: `app.pepper.drivepool`
- v0.1.0 — dashboard + Add Drive (Google sign-in, Drive read-only scope)
- Built with real Jetpack Compose via GitHub Actions (Gradle does not work on the dev machine)

## How Add Drive works

DrivePool does **not** ship with any Google OAuth client baked in. In the app, open Settings and paste the **Web client ID from your own Google Cloud project** (the same project you use for Round Sync / rclone). It stays on your phone only.

You also need an **Android OAuth client** for package `app.pepper.drivepool` in that same Cloud project, otherwise Google sign-in fails with error 10. That is a one-time Console step.

Access tokens are held in memory only and are never stored. Only email + quota numbers are stored locally.

## Build

Push to `main` — GitHub Actions builds a debug APK (`assembleDebug`) and uploads it as `drivepool-debug`.

## Roadmap

- v0.1: dashboard, Add Drive, refresh per account
- Later: browse / move files between accounts, union-style pooled uploads, in-app updates
