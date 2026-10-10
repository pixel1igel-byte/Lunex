# Lunex for Android

Android Compose UI prototype for Lunex. The supplied artwork is installed as the Android launcher icon. Version 1.0.0.

## Build an APK on GitHub

Upload the contents of this folder to a GitHub repository. Then open **Actions → Build Lunex APK → Run workflow**. When the run finishes, download the **Lunex-Android-APK** artifact, extract it, and install `app-debug.apk` on Android.

This workflow creates a debug APK, not a Play Store signed release. Production distribution needs a private signing key and secure signing configuration.

## Current scope

This Android target is a UI prototype with a dark/purple theme, sample chats, community screens, and theme controls. It is not connected to a live backend and does not yet provide live messaging, calls, or end-to-end encryption.
