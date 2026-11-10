# VELNIX TV

Standalone Android TV / Google TV application. It does not alter the VELNIX website.

## Features in the first build
- Reads the existing active channel and category catalog from the current Supabase project.
- Uses the existing `xtream-gateway` Edge Function as the stream proxy; provider URLs and credentials are not embedded in the app.
- TV remote D-pad navigation, channel categories, search, local favorites, and Media3 playback.
- No database duplication and no changes to the website backend schema.

## Build
Open this directory in Android Studio, or run:

```bash
gradle :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds the APK and uploads it as the `VELNIX-TV-debug` artifact.
