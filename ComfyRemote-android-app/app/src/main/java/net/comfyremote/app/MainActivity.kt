package net.comfyremote.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LabeledIntent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import org.json.JSONObject

/**
 * WebView shell around the Comfy Remote web app.
 *
 * The UI is served by the comfy_remote_host custom node at
 * http://<host>:8188/comfy-remote/ so it is same-origin with the ComfyUI API:
 * no CORS, and websockets to /ws work normally.
 *
 * Handles: file chooser (reference images — the Android picker reaches network
 * folders your file manager maps), downloads of generated images into the
 * phone's Downloads folder, back-button navigation, and cleartext HTTP on LAN.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pageReady = false
    private var pendingShare: Uri? = null

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = filePathCallback ?: return@registerForActivityResult
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        cb.onReceiveValue(uris)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        web = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF161826.toInt())
        }
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true            // localStorage keeps host/port/prompt
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = false
            javaScriptCanOpenWindowsAutomatically = true
        }
        CookieManager.getInstance().setAcceptCookie(true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                pendingShare?.let { u -> pendingShare = null; deliverSharedUri(u) }
            }
        }

        web.addJavascriptInterface(NativeBridge(), "ComfyNative")

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    pickFiles.launch(buildBrowseChooser())
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }

        // Generated images: hand off to Android's DownloadManager -> /Download
        web.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val req = DownloadManager.Request(Uri.parse(url))
                req.setMimeType(mimeType)
                req.addRequestHeader("User-Agent", userAgent)
                val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            } catch (_: Exception) { }
        })

        // Explicit callback object rather than the activity-ktx lambda overload, so the
        // project needs no extra dependency.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState == null) {
            web.loadUrl(getString(R.string.app_url))
        } else {
            // Restore the previous page; if there was nothing to restore, load fresh.
            if (web.restoreState(savedInstanceState) == null) {
                web.loadUrl(getString(R.string.app_url))
            }
        }
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * Share-in: the universal route to ANY file app.
     *
     * File managers such as File Manager+ do not implement a DocumentsProvider and do
     * not register for ACTION_GET_CONTENT, so no picker or chooser we launch can ever
     * list them. But every one of them can SHARE a file. So the app also declares
     * itself an ACTION_SEND target: browse the network share in whatever app you like,
     * long-press the image, Share -> Comfy Remote, and it arrives here as a reference
     * image.
     */
    private fun handleShareIntent(i: Intent?) {
        if (i == null) return
        val uri: Uri = when (i.action) {
            Intent.ACTION_SEND -> i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            Intent.ACTION_SEND_MULTIPLE ->
                i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
            Intent.ACTION_VIEW -> i.data
            else -> null
        } ?: return
        if (pageReady) deliverSharedUri(uri) else pendingShare = uri
    }

    /** Read the shared file and hand it to the web app as a data URL. */
    private fun deliverSharedUri(uri: Uri) {
        Thread {
            try {
                val cr = contentResolver
                val mime = cr.getType(uri) ?: "image/*"
                var name = "shared"
                cr.query(uri, null, null, null, null)?.use { c ->
                    val ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ix >= 0 && c.moveToFirst()) name = c.getString(ix) ?: name
                }
                val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return@Thread
                // Base64 in a JS call string costs ~1.4x the file size; refuse absurd
                // inputs rather than risking an OOM in the WebView.
                if (bytes.size > MAX_SHARE_BYTES) {
                    runOnUiThread {
                        web.evaluateJavascript(
                            "window.__comfyShareFailed && window.__comfyShareFailed(" +
                                JSONObject().put("name", name)
                                    .put("reason", "file too large (" + (bytes.size / 1048576) + " MB)")
                                    .toString() + ")",
                            null
                        )
                    }
                    return@Thread
                }
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val payload = JSONObject()
                    .put("name", name)
                    .put("dataUrl", "data:$mime;base64,$b64")
                    .toString()
                runOnUiThread {
                    web.evaluateJavascript(
                        "window.__comfyShared && window.__comfyShared($payload)", null
                    )
                }
            } catch (_: Exception) { }
        }.start()
    }

    /** Callable from the web app: window.ComfyNative.* */
    inner class NativeBridge {

        /** True when running inside the APK, so the web UI can show native-only affordances. */
        @JavascriptInterface
        fun isNative(): Boolean = true

        /**
         * Launch an installed file app so the user can browse network shares in it and
         * share the file back. Returns immediately; the file arrives via ACTION_SEND.
         */
        @JavascriptInterface
        fun openFileApp() {
            runOnUiThread {
                try {
                    startActivity(buildLaunchFileAppChooser())
                } catch (_: Exception) { }
            }
        }

        /** JSON array of installed file-manager-ish apps: [{label, pkg}] */
        @JavascriptInterface
        fun listFileApps(): String {
            val arr = org.json.JSONArray()
            for ((label, pkg) in fileAppCandidates()) {
                arr.put(JSONObject().put("label", label).put("pkg", pkg))
            }
            return arr.toString()
        }

        /** Launch one by package name. */
        @JavascriptInterface
        fun launchFileApp(pkg: String) {
            runOnUiThread {
                try {
                    packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                } catch (_: Exception) { }
            }
        }
    }

    /**
     * Installed apps that browse files. Found by launcher intent + the <queries> block,
     * which is the only way to see apps that expose no picker at all.
     */
    private fun fileAppCandidates(): List<Pair<String, String>> {
        val pm = packageManager
        val known = listOf(
            "com.alphainventor.filemanager",      // File Manager+
            "com.android.documentsui",            // Files (AOSP)
            "com.google.android.documentsui",     // Files (Pixel)
            "com.google.android.apps.nbu.files",  // Files by Google
            "nextapp.fx",                         // FX File Explorer
            "com.mixplorer", "com.mixplorer.silver",
            "me.zhanghai.android.files",
            "com.estrongs.android.pop",           // ES
            "com.lonelycatgames.Xplore",          // X-plore
            "com.ghisler.android.TotalCommander",
            "pl.solidexplorer2"                   // Solid Explorer
        )
        val out = LinkedHashMap<String, String>()
        for (pkg in known) {
            try {
                val ai = pm.getApplicationInfo(pkg, 0)
                if (pm.getLaunchIntentForPackage(pkg) != null) {
                    out[pkg] = pm.getApplicationLabel(ai).toString()
                }
            } catch (_: Exception) { }
        }
        return out.map { (pkg, label) -> label to pkg }
    }

    private fun buildLaunchFileAppChooser(): Intent {
        val cands = fileAppCandidates()
        val first = cands.firstOrNull()
            ?: return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }
        val base = packageManager.getLaunchIntentForPackage(first.second)!!
        val extras = cands.drop(1).mapNotNull { (label, pkg) ->
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                LabeledIntent(it, pkg, label, 0)
            }
        }
        return Intent.createChooser(base, "Open file app").apply {
            if (extras.isNotEmpty()) putExtra(
                Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray()
            )
        }
    }

    /**
     * "Browse with" over EVERY installed app that can hand back a file — not just the
     * ones Android would pick by default.
     *
     * Chrome's own file chooser only lists apps registered for ACTION_GET_CONTENT with
     * the requested MIME type, which leaves out file managers (File Manager+ included)
     * that only implement a DocumentsProvider or ACTION_PICK. Here we query the package
     * manager for handlers of GET_CONTENT (image and any type), OPEN_DOCUMENT and PICK,
     * then add each one to the chooser as an explicit LabeledIntent.
     *
     * Requires the <queries> block in AndroidManifest.xml on Android 11+, otherwise the
     * package manager hides other apps.
     */
    private fun buildBrowseChooser(): Intent {
        val pm = packageManager
        val base = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, false)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "*/*"))
        }

        val probes = listOf(
            Intent(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" },
            Intent(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" },
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" },
            Intent(Intent.ACTION_PICK).apply { type = "image/*" }
        )

        val extras = ArrayList<LabeledIntent>()
        val seen = HashSet<String>()
        val self = packageName

        for (probe in probes) {
            val hits = pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            for (info in hits) {
                val act = info.activityInfo ?: continue
                if (act.packageName == self) continue
                val key = act.packageName + "/" + act.name
                if (!seen.add(key)) continue
                val explicit = Intent(probe).apply {
                    component = ComponentName(act.packageName, act.name)
                    putExtra(Intent.EXTRA_LOCAL_ONLY, false)
                }
                extras.add(
                    LabeledIntent(
                        explicit,
                        act.packageName,
                        info.loadLabel(pm),
                        act.icon
                    )
                )
            }
        }

        return Intent.createChooser(base, "Browse with").apply {
            if (extras.isNotEmpty()) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    companion object {
        private const val MAX_SHARE_BYTES = 32 * 1024 * 1024
    }
}
