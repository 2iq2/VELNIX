# VELNIX TV

Standalone native Android TV / Google TV application. The TV app is kept separate from the VELNIX website.

## Current foundation
- Arabic-first, landscape 10-foot interface with D-pad / remote focus navigation.
- Sign in and account creation through the existing Supabase Auth and VELNIX registration gateway.
- Reads active channel and category metadata from the existing Supabase catalog using the public catalog policy.
- Requests each playback URL from the existing `xtream-gateway` using the signed-in user's access token. Provider source URLs and credentials are not stored in the APK.
- Media3 / ExoPlayer playback for HLS and DASH, adaptive buffering, and reconnect attempts.
- Local favorites stored on the TV device.
- No duplicate database and no schema changes.

## Build locally
Open this directory in Android Studio, or run with JDK 17 and Gradle 8.13:

```bash
gradle :app:assembleDebug
```

APK output:

`app/build/outputs/apk/debug/app-debug.apk`

## Build with GitHub Actions
Every push that changes `tv-app/**` starts the **Build VELNIX TV APK** workflow. After it succeeds:
1. Open the repository's **Actions** tab.
2. Open the latest **Build VELNIX TV APK** run.
3. Download the `VELNIX-TV-debug` artifact and extract the APK.

The first build is a debug APK for device testing. A separately signed release APK/AAB will be prepared after the TV build is tested on a real Android TV / Google TV device.
