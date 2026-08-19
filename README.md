# Comfy Remote

Android companion app for a self-hosted ComfyUI instance. Fires saved workflows
(Chat to Image / Flux2, Moody Z-Image, Wan 2.2 Image-to-Video), edits prompts,
LoRA strengths and batch size, browses shared drives for reference images, and
watches the queue and outputs - without opening ComfyUI's own node graph.

## Repo layout

| Folder | What it is |
| --- | --- |
| `ComfyRemote-server-node/` | The web app, served from ComfyUI itself (`web/comfy-remote/`). This is the actual app - a PWA: installable, own icon, works offline for the shell. |
| `ComfyRemote-android-app/` | Android Studio project - a thin WebView shell around the same app, adding OS integration the browser can't: a native "share to" target so file managers with no picker (e.g. File Manager+) can still hand it images, and an installed-file-app launcher. |
| `comfy-graphs.js` | Workflow registry: each workflow's API-format graph plus the field map the app uses to find its prompt, sampler, seed, size and LoRA nodes. |
| `workflows/` | The source ComfyUI exports (API format) that `comfy-graphs.js` is built from - kept alongside the registry so a workflow can be re-exported and re-inferred after editing it in ComfyUI. |

## Requirements

- ComfyUI running and reachable on your LAN (tested against `--listen`).
- Workflows exported in **API format** (ComfyUI menu → *Workflow → Export (API)* -
  not the default "Export" which saves the UI graph and will not import).
- rgthree-comfy installed if using the Power Lora Loader-based workflows (Moody, Wan).

## Install (web / PWA - do this first)

1. Copy `ComfyRemote-server-node/web/` into your ComfyUI install:

       ComfyUI/web/comfy-remote/

2. On the phone, open `http://<comfyui-host>:8188/comfy-remote/`.
3. Chrome → ⋮ → **Install app**. Launches fullscreen, own icon, no address bar.

Serving it from ComfyUI's own web root keeps it same-origin with the API - no
CORS flag needed. If you serve it elsewhere, start ComfyUI with
`--enable-cors-header "*"`.

### Updating

Replace `web/index.html` (and `comfy-graphs.js` if the workflow registry
changed), then bump `CACHE` in `web/sw.js` so installed copies fetch the new
shell instead of serving their cached one.

## Install (Android app)

The app shell adds native file-app integration the web build cannot do from
inside a browser sandbox. Build it yourself in Android Studio:

1. Open `ComfyRemote-android-app/` (the folder containing `settings.gradle.kts`).
   Avoid a path with spaces.
2. Edit `app/src/main/res/values/strings.xml` → `app_url` to your host's
   `http://<host>:8188/comfy-remote/`.
3. **Build → Build Bundle(s)/APK(s) → Build APK(s)**, or `gradlew assembleDebug`.
   Output: `app/build/outputs/apk/debug/app-debug.apk`.
4. Install via **Run ▶** over Wi-Fi debugging / USB (recommended — skips the
   Chrome http-download block), or sideload the APK directly.

Full steps and troubleshooting in `ComfyRemote-android-app/README.md`.

## Adding or updating a workflow

The app reads any ComfyUI graph structurally - it doesn't need a workflow to
match a hardcoded shape. Two ways to add one:

- **In the app** - Generate tab → workflow picker → *Import* → load or paste an
  API-format export. The app finds the prompt, sampler, seed, size and every
  LoRA node itself and adds it to the picker, stored on-device.
- **Built into the registry** - add an entry to `comfy-graphs.js`'s
  `COMFY_WORKFLOWS` array (see the existing three for the shape), or hand it a
  raw graph and call `COMFY_INFER(graph, name)` to generate the field map
  automatically.

LoRA nodes (chained `LoraLoader` or rgthree's `Power Lora Loader`) are
discovered the same way — add, remove or reorder LoRAs in the workflow itself
and the app's LoRA list follows, with no code change.

## Model / LoRA family filtering

The app tags checkpoints, CLIP models and LoRAs with a "family" (flux2, wan,
zimage, sdxl, …) inferred from folder name and filename, and filters pickers to
the family the loaded workflow expects - a LoRA trained for one model does
nothing (or actively misapplies) loaded against another. When a file's name
gives no usable signal (e.g. a custom-renamed checkpoint), set a manual
override in **Settings → Model family rules** - either per-folder or per-workflow.

## Notes / activators per LoRA

Each LoRA gets a persistent text note (trigger words, working strength range,
etc.), keyed by filename so it survives moving the file to a different
subfolder. Edited from the LoRA stack sheet; "append to prompt" pastes the
saved activators straight in.

## Configuration (Tweaks)

The root component exposes these as editable props (defaults shown):

| Prop | Default | What it does |
| --- | --- | --- |
| `defaultHost` / `defaultPort` | `192.168.1.212` / `8188` | ComfyUI address |
| `comfyUser` | `jon` | `comfy-user` header, for `--multi-user` |
| `savePrefix` | `3-images` | default `filename_prefix` |
| `maxRefSlots` | `2` | reference-image slots shown (workflow's own max still applies) |
| `loraSlotCount` | `4` | default visible LoRA slots before "add slot" |
| `strengthMax` | `10` | LoRA strength slider ceiling |
| `stepsMax` | `20` | sampler steps slider ceiling |
| `galleryColumns` | `2` | output grid columns |
| `latentPreview` | `true` | show live step previews while a job runs |
| `pollSeconds` | `3` | queue/stats poll interval |
| `accent` | `#9184d9` | accent color |

## Known limits

- SMB share browsing works through the OS file-app layer (Android's Storage
  Access Framework / share intents), not a built-in SMB client - a file
  manager with SMB support (e.g. File Manager+) has to be installed separately.
- Chained `LoraLoader` workflows (Chat to Image) can't gain new LoRA slots from
  the app - that needs rewiring which node feeds the sampler, so add nodes in
  ComfyUI and re-import. rgthree Power Lora Loader workflows (Moody, Wan) can
  add slots directly from the app.
