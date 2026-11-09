# DrivePool — Copyright (c) 2026 Redowansky
# Licensed under the PolyForm Noncommercial License 1.0.0.
# See LICENSE in the repository root. Commercial use requires permission.

# v0.8.0: R8 is on. The app uses no reflection-based libraries
# (JSON is org.json, HTTP is OkHttp with its own consumer rules),
# so shrinking is safe. These keeps are belt-and-braces for the
# app's own entry points and data classes.
-keep class app.pepper.drivepool.MainActivity { *; }
-keep class app.pepper.drivepool.UploadService { *; }
-keep class app.pepper.drivepool.data.** { *; }
