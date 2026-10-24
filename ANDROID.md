# Castor Android browser — build and behavior

This Android client is a native WebView browser that uses the Castor icon supplied for this build. It boots directly into the browser: the only setup stages shown are **“Baixando ambiente”** and **“Iniciando”**. It downloads EasyList and EasyPrivacy filter rules on first launch (and refreshes them roughly once a day), caches them in the app-private directory, and falls back to bundled rules if the device is offline.

## Included in this Android build

- Web browser with address/search field, back, forward, home, refresh and a page-media action.
- Media URL detection for direct MP4, M3U8/HLS, MPD/DASH, WebM, M4V, MOV, MKV, TS, FLV and common audio URLs found in HTML video/source elements, links and browser resource entries.
- Media action list with URL, detected format, opening in a compatible Android app, copy and share actions.
- Ad/tracker blocking with built-in domain rules plus downloaded EasyList/EasyPrivacy rules where their common domain/path syntax is supported.
- Popup and automatic-redirect controls, all **enabled by default**.
- Compact blocking notice showing the URL and **“Abrir” / “Ignorar”** actions. By default the notice closes after 3 seconds. Settings allow 0 seconds (stay until a choice) or a timeout from 1 to 15 seconds.
- User-facing configuration is limited to the protection toggles and timeout; no server URL/setup screen is shown.
- App launcher icon is generated from the provided `castor.svg` image.

## Important limitation: the Castor server is not embedded yet

This is **not** a full Android port of every Castor backend component. The app currently starts its local browser/filter runtime; it does not start the original Go `castor api-server`, `media-server` and `scraping-server` processes. Upstream Castor's page extraction requires headless Chrome/Chromium and its media processing requires FFmpeg/FFprobe 7.1+; the upstream Linux release binary is not an Android runtime, and the scraping path cannot be assumed to work inside Android WebView. Properly embedding those services needs a separately tested Android-compatible build of the Go backend, media toolchain and browser extractor. The app does not pretend that stage has completed.

The media detector extracts direct URLs exposed to WebView. It cannot guarantee Web Video Caster-level compatibility: DRM-protected playback, encrypted/obfuscated site APIs, `blob:` media that has no accessible direct URL, signed/expiring links and site-specific playback flows may require an additional extractor. This build does not bypass DRM or site access controls. For supported direct URLs, the Android app opens a compatible media player where available; MP4 and HLS support depends on the installed player/device.

## Build an APK with GitHub Actions

1. Push this repository to GitHub, including `android/` and `.github/workflows/build-android-apk.yml`.
2. Open **Actions → Build Castor Android APK → Run workflow** (or push a change inside `android/`).
3. When the job finishes, download the `castor-android-debug-apk` artifact, extract `castor-android-debug.apk`, and install it on Android.

The workflow builds a debug APK using JDK 17, Gradle 8.13 and Android SDK 36. Debug APKs are not release-signed.

## Privacy and network

Filter lists are downloaded from `easylist.to` over HTTPS. URLs visited by the user are loaded in Android WebView. Filter rules are stored inside the app-private storage; no account is required. Clear the app's storage to erase those cached rules and browser state. The browser blocks invalid HTTPS certificates and does not bypass DRM.

## License

The upstream Castor source remains under its existing MIT license. This Android client layer does not change the upstream license or the source's content/access limitations.
