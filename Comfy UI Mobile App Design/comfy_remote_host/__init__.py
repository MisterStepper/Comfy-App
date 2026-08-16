"""Comfy Remote — serves the mobile app from ComfyUI itself.

Mounts this folder's web/ directory at http://<host>:8188/comfy-remote/ using
explicit file handlers (aiohttp's static index handler can return responses that
Chrome rejects with ERR_INVALID_RESPONSE).

Install: copy this whole folder into  <base>/custom_nodes/comfy_remote_host
Restart ComfyUI, then open http://<host>:8188/comfy-remote/ on the phone.

Because the app is served from the ComfyUI origin it can call /prompt, /ws,
/history, /view, /upload/image and /object_info with no CORS configuration.

Diagnostics: http://<host>:8188/comfy-remote/ping  -> prints the served path.
"""

import os
import mimetypes

HERE = os.path.dirname(os.path.realpath(__file__))
WEB_DIR = os.path.join(HERE, "web")

mimetypes.add_type("application/manifest+json", ".webmanifest")
mimetypes.add_type("text/javascript", ".js")

TYPES = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".webmanifest": "application/manifest+json; charset=utf-8",
    ".png": "image/png",
    ".json": "application/json; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".svg": "image/svg+xml",
}


def _register():
    from aiohttp import web
    from server import PromptServer

    routes = PromptServer.instance.routes

    def _send(rel):
        safe = os.path.normpath(rel).replace("\\", "/").lstrip("/")
        if safe.startswith(".."):
            return web.Response(status=403, text="forbidden")
        path = os.path.join(WEB_DIR, safe)
        if not os.path.isfile(path):
            return web.Response(status=404, text="not found: %s" % safe)
        ext = os.path.splitext(path)[1].lower()
        ctype = TYPES.get(ext) or mimetypes.guess_type(path)[0] or "application/octet-stream"
        headers = {"Cache-Control": "no-cache"}
        if ext == ".js":
            headers["Service-Worker-Allowed"] = "/comfy-remote/"
        return web.FileResponse(path, headers=headers, chunk_size=256 * 1024)

    @routes.get("/comfy-remote/ping")
    async def _ping(request):
        listing = ", ".join(sorted(os.listdir(WEB_DIR))) if os.path.isdir(WEB_DIR) else "(web/ missing)"
        return web.Response(text="ok %s\nfiles: %s" % (WEB_DIR, listing), content_type="text/plain")

    @routes.get("/comfy-remote")
    async def _root_redirect(request):
        raise web.HTTPFound("/comfy-remote/")

    @routes.get("/comfy-remote/")
    async def _index(request):
        return _send("index.html")

    @routes.get("/comfy-remote/{tail:.*}")
    async def _asset(request):
        return _send(request.match_info.get("tail") or "index.html")

    print("[comfy-remote] serving %s at /comfy-remote/" % WEB_DIR)


try:
    _register()
except Exception as exc:  # never break startup over a static route
    print("[comfy-remote] could not mount route: %r" % (exc,))

NODE_CLASS_MAPPINGS = {}
NODE_DISPLAY_NAME_MAPPINGS = {}
__all__ = ["NODE_CLASS_MAPPINGS", "NODE_DISPLAY_NAME_MAPPINGS"]
