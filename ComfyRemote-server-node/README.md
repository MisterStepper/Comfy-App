# Comfy Remote — live version

The app now talks to your instance. Nothing is mocked.

## Install (ComfyUI Desktop)

1. Copy this folder to `D:\Image\ComfyUI\custom_nodes\comfy_remote_host`
   (must contain `__init__.py` and the `web` folder).
2. Restart ComfyUI Desktop. Log shows `[comfy-remote] serving … at /comfy-remote/`.
3. Phone: `http://192.168.1.212:8188/comfy-remote/` → Chrome ⋮ → Install app.

Already installed an older copy? Replace `web/index.html`; the cache version was
bumped to `comfy-remote-v2-live` so installed copies pull the new build.

## What it does for real

| Screen | Calls |
| --- | --- |
| Generate → Queue prompt | `POST /prompt` with your graph patched, `client_id` set |
| progress bar, node name | `ws://<host>:8188/ws?clientId=…` (`progress`, `executing`, `execution_error`) |
| live latent preview | binary websocket frames, toggleable in Advanced |
| Latest output / Output tab | `GET /history?max_items=40` → `GET /view?filename=…` |
| LoRA picker list | `GET /object_info/LoraLoader` → `lora_name` enum |
| Sampler list | `GET /object_info/KSamplerSelect` |
| VRAM readout, test connection | `GET /system_stats` |
| Queue tab | `GET /queue`, `POST /interrupt`, `POST /queue {clear:true}` |
| Files → pick image | `POST /upload/image` → sets `LoadImage.image` |
| Save output | direct `/view` download |

Every request carries `comfy-user: jon` (editable in Settings).

## What gets patched into the graph

Graphs are embedded from your three API exports and chosen by how many
reference images are attached — 0 → `Chat to Image`, 1 → `@1`, 2 → `@2`. An
empty `LoadImage` can never happen.

    node 8    text            prompt
    node 99-102  lora_name / strength_model / strength_clip   (bypass = 0 / 0)
    node 39   batch_size, width, height
    node 40   steps, width, height
    node 41   sampler_name
    node 42   cfg
    node 43   noise_seed      (randomised per run unless "keep seed fixed")
    node 18   filename_prefix

Host, port, user, prompt, params and LoRA slots persist in localStorage.

## Not wired

- **A 3-reference variant** — you mentioned one, but only the 0/1/2-ref exports
  were sent. Export `@3 Image Reference` (Workflow → Export API), send it, and
  the third slot lights up.
- **Direct SMB browsing.** A browser can't mount a share. Uploads go through
  Android's document picker (which does reach your mapped folders) and downloads
  land in `/Download`. The APK shell in `android/` is the version with proper
  file-system reach.

## Server control (Settings → Server control)

- **Unstick** — `POST /interrupt`, `POST /queue {clear:true}`, `POST /free`, then
  reopens the websocket. First thing to try when a run wedges.
- **Free VRAM** — `POST /free {unload_models,free_memory}`.
- **Restart server** — tries `/api/manager/reboot`, then `/manager/reboot`
  (ComfyUI-Manager), then `POST /comfy-remote/restart` from this node, which
  relaunches the process with the same interpreter and argv even when Manager
  isn't loaded. The app then polls `/system_stats` every 2s for 90s and
  reconnects by itself.

If HTTP stops answering entirely, no remote path exists — that one needs the machine.

## Errors

Failures surface in the banner verbatim — `POST /prompt 400 · <message> · node
NN` for validation problems (missing LoRA file, bad value), `Failed to fetch`
when the host is unreachable. The ⟳ button re-runs stats, queue, history and
reopens the socket.
