# Comfy Remote — Android app (APK)

A WebView shell around the Comfy Remote UI. The UI itself is served by the
`comfy_remote_host` custom node at `http://192.168.1.212:8188/comfy-remote/`,
so the app is same-origin with the ComfyUI API — no CORS, websockets work.

## Build the APK

1. Install the `ComfyRemote-server-node` HOST node first and confirm
   `http://192.168.1.212:8188/comfy-remote/` loads in a desktop browser.
2. Open **Android Studio** → *Open* → pick this `ComfyRemote-android-app` folder
   (the one containing `settings.gradle.kts`). Let Gradle sync.
3. **Build → Build Bundle(s)/APK(s) → Build APK(s)** — this is the item that
   writes a shareable APK. "Make Project" (Ctrl+F9) only compiles, and **Run ▶**
   packages to a different folder.

### Where the APK lands

| How you built | Path |
| --- | --- |
| Build APK(s) / `gradlew assembleDebug` | `app\build\outputs\apk\debug\app-debug.apk` |
| Run ▶ to a device or emulator | `app\build\intermediates\apk\debug\app-debug.apk` |
| `gradlew assembleRelease` | `app\build\outputs\apk\release\app-release.apk` |

Both debug paths give an installable, debug-signed APK. If the Project panel
shows only `outputs\logs`, do **File → Reload All from Disk** — the tree caches.

Command line, from this folder:

    gradlew assembleDebug        # Windows
    ./gradlew assembleDebug      # macOS/Linux

Then copy the APK to the phone and tap it; allow "install unknown apps" for your
file manager once. It appears as **Comfy Remote**.

## Changing the host

The URL is a resource, not hardcoded in Kotlin — edit one line in
`app/build.gradle.kts`:

    resValue("string", "app_url", "http://192.168.1.212:8188/comfy-remote/")

Rebuild. (The in-app Settings screen also lets you point the API at a different
host/port at runtime; `app_url` is only where the UI is loaded from.)

## What the shell adds over the PWA

- File chooser → picking reference images uses Android's document picker, which
  reaches the network folders your file manager already maps.
- Downloads → generated images go to `/Download` via DownloadManager, with a
  completion notification.
- Cleartext HTTP allowed for LAN addresses (`network_security_config.xml`),
  which Chrome increasingly resists.
- Real launcher icon, no browser chrome, back button navigates the app.

## Browse with — all installed apps

Chrome's file chooser only lists apps registered for the exact intent, so file
managers that expose a DocumentsProvider (File Manager+) never appear. This
shell instead queries the package manager for every handler of GET_CONTENT,
OPEN_DOCUMENT and PICK and adds each as an explicit entry in the chooser.

That requires the `<queries>` block in `AndroidManifest.xml` — Android 11+
hides other packages without it. If an app still doesn't show, it registers none
of those actions; reach it through the Files picker's drawer instead.

## Files

    app/src/main/java/net/comfyremote/app/MainActivity.kt   the whole shell
    app/src/main/AndroidManifest.xml                        permissions, activity
    app/src/main/res/xml/network_security_config.xml         LAN HTTP allowance
    app/build.gradle.kts                                     app_url + SDK levels

## Troubleshooting

- **Blank screen** — the node isn't serving. Check
  `http://192.168.1.212:8188/comfy-remote/ping` from the phone's browser.
- **Works on Wi-Fi only** — expected; the host is a LAN address. Use Tailscale
  and set `app_url` (and Settings → Host) to the tailnet name for remote use.
- **Gradle sync fails offline** — the first build needs internet for
  dependencies; after that it builds offline.
