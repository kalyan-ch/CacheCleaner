# Cache Cleaner

A one-screen Android app that shows every app's cache size and clears them all with one tap, using [Shizuku](https://shizuku.rikka.app/) instead of root.

## Getting the APK

**On GitHub (no computer needed):**
1. Push this folder to a GitHub repository.
2. Open the repo's **Actions** tab and wait for "Build APK" to finish (about 3–5 minutes).
3. Open the run and download the **CacheCleaner-apk** artifact (a zip that contains the APK).

**With Android Studio:** open this folder and choose *Build › Build APK(s)*.

## Using it
1. Install **Shizuku** from the Play Store and start it through *Wireless debugging* (Shizuku's own guide walks you through this). On non-rooted phones, you need to start it again after each reboot.
2. Install the Cache Cleaner APK. You may need to allow "Install unknown apps" for your browser or file manager.
3. Open Cache Cleaner:
   - Tap **Grant** and turn on *Usage access* for Cache Cleaner. This lets it show cache sizes.
   - Tap **Allow** to give it Shizuku permission.
4. Tap **Clean all caches**.

## How it works
- Cache sizes come from `StorageStatsManager`, which needs usage access.
- Cleaning runs `pm trim-caches` in a Shizuku *user service* with shell privileges. This clears the cache of every app it can, in one pass. Caches that an app is using at that moment may grow back right away.
- Nothing except cache is touched: no app data, logins or files.

## Project layout
```
app/src/main/
  aidl/.../IUserService.aidl   binder interface for the privileged service
  java/.../UserService.kt      runs `pm trim-caches` as shell (inside Shizuku)
  java/.../Core.kt             permission checks, cache scanner, Shizuku binding
  java/.../MainActivity.kt     Compose UI and ViewModel
```
