# Castor for Android — build and setup

This repository now includes an Android client in `android/`. It packages the existing Castor web interface in a native Android WebView and adds a small connection screen where you enter the address of your Castor web app.

## Important scope limitation

This is an Android client, **not a fully self-contained port of all Castor services**. It exposes the functions provided by Castor's existing web interface. The API, webpage extraction, media conversion/serving, TV discovery and playback must still run on a computer or NAS that can reach the TV on the same LAN. In particular, the APK does not bundle headless Chromium, FFmpeg/FFprobe or Whisper. The original command-line/TUI commands are not all recreated as Android-native screens.

## Build an APK with GitHub Actions

1. Commit the repository with the `android/` folder and `.github/workflows/build-android-apk.yml`.
2. Open the repository on GitHub and choose **Actions → Build Castor Android APK → Run workflow** (or push a change under `android/`).
3. When the workflow finishes, open the run and download the artifact **`castor-android-debug-apk`**.
4. Extract `castor-android-debug.apk` and install it on Android. Since it is a debug build, Android may ask you to allow installation from the app used to download it.

For a local build, install JDK 17, Gradle 8.13, and Android SDK platform 36, then run `cd android && gradle --no-daemon assembleDebug`. This execution environment has no Android SDK/Gradle installation and cannot download them, so the APK itself has not been compiled or device-tested here.

## Server requirements

Run the upstream Castor services on a Linux computer/NAS connected to the same network as the TV. Follow the original [`README.md`](README.md), [`config.yaml`](config.yaml), and [`docker-compose.yml`](docker-compose.yml). Device discovery and the media server need access to the TV's LAN; Docker host networking is used by the provided Compose setup.

The Android app connects to the Castor **web interface**, not directly to the API. Build and run the existing web app on a host the phone can reach:

```bash
# From the repository root; Node.js and Corepack/Yarn are required.
corepack enable
yarn install
yarn workspace castor-app build

# Set your real API address/token and a web password outside source control.
export CASTOR_SERVERS__API__URL=http://127.0.0.1:8411
export CASTOR_SERVERS__API__TOKEN='replace-with-your-api-token'
export CASTOR_WEB__PASSWORD='choose-a-long-web-password'

# Run on LAN interfaces so the phone can reach it.
yarn workspace castor-app start:lan
```

The web app listens on port `3000` by default. Find the host's LAN IP (for example `192.168.1.20`) and enter `http://192.168.1.20:3000` in the Android app. Keep the phone, web server, API and TV on a network path that permits the required connections. If you expose the web app beyond a trusted home LAN, use HTTPS and firewall rules; do not expose service ports or tokens to the public internet.

The web app's `CASTOR_WEB__PASSWORD` is separate from `CASTOR_SERVERS__API__TOKEN`. The APK asks for the web password when challenged and keeps it only in memory for the current app session.

## Android client behavior

- Stores the web interface URL locally.
- Opens the original responsive Castor interface.
- Supports JavaScript, page navigation and refresh.
- Keeps same-origin app navigation inside the APK and opens external links in the browser.
- Does not bypass invalid HTTPS certificates.
- HTTP is permitted for local-LAN addresses for compatibility with a private setup. Prefer HTTPS whenever the service is reachable outside a trusted LAN.

## Project and licence

Castor upstream is licensed under MIT; see [`LICENSE`](LICENSE). This Android wrapper is an additional client layer and does not change the underlying server requirements or the upstream project's media/DRM limitations.
