# QuickSend

Send/receive any files or whole folders (any size, any quantity, raw bytes) between Android phones
over **Wi‑Fi**, **Bluetooth**, or **both at the same time**.

## Build the APK with GitHub Actions
1. Create a GitHub repo and push this folder to it (`main` branch).
2. Open **Actions → Build Android APK** (runs on every push, or press *Run workflow*).
3. Download the `QuickSend-release-apk` (or debug) artifact, unzip, install `app-release.apk`.
4. Tag `v1.0` (`git tag v1.0 && git push --tags`) to also publish the APK on the Releases page.

Local build: install JDK 17 + Android SDK, then `gradle assembleDebug` (Gradle 8.9).

## Use
**Receiver:** Receive tab → pick Wi‑Fi and/or Bluetooth → *Start receiving*.
For Bluetooth tap *Make Bluetooth discoverable* (or pair the phones once in system settings).
Files land in `Downloads/QuickSend/` (folder structure preserved).

**Sender:** Send tab → *Add files* / *Add folder* → pick the Wi‑Fi receiver (auto-discovered, or type its IP)
and/or the Bluetooth device → *Send*. With both enabled, files are shared across both links.

## How it works
* One tiny protocol (see `Receiver.kt`) runs over TCP and Bluetooth RFCOMM sockets.
* Sender keeps one queue of files; each channel takes the next file, so the faster link carries more.
  If a link drops, its file is re-queued on the other link.
* Receiver streams straight to disk via MediaStore (no RAM limits, no storage permission).
* A foreground service keeps everything alive with the screen off.

## Limits
* Android 10+ (minSdk 29). Android only.
* One file travels over one link (not split in chunks), so a single huge file doesn't get the speed-up of both.
* Bluetooth Classic is slow (roughly 0.1–0.3 MB/s); Wi‑Fi does the heavy lifting.
* Android 11+ won't let you pick a storage root or the Downloads folder itself as a "folder" – pick a subfolder.
* Empty folders are not sent.
