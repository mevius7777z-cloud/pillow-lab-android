package xyz.pillowlab.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var lastBackPressTime = 0L

    private val targetUrl = "http://101.132.81.161:3000"

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            if (callback == null) return@registerForActivityResult

            if (result.resultCode != Activity.RESULT_OK || result.data == null) {
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }

            val data = result.data!!
            val uris = mutableListOf<Uri>()
            val clipData = data.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i).uri?.let { uris.add(it) }
                }
            } else {
                data.data?.let { uris.add(it) }
            }
            callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.addJavascriptInterface(DownloadBridge(), "AndroidDownload")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectBlobDownloadHook()
                injectAutoUnlock()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/json", "text/*"))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try {
                    fileChooserLauncher.launch(Intent.createChooser(intent, "选择图片或文件"))
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(this@MainActivity, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        webView.setDownloadListener { url, _, _, mimeType, _ ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                val safeUrl = url.replace("'", "\\'")
                val safeMime = (mimeType ?: "application/octet-stream").replace("'", "\\'")
                val js = """
                    (function() {
                        fetch('$safeUrl')
                            .then(r => r.blob())
                            .then(b => {
                                const reader = new FileReader();
                                reader.onloadend = () => {
                                    if (window.AndroidDownload) {
                                        window.AndroidDownload.saveBase64(reader.result, 'pillow_lab_export', '$safeMime');
                                    }
                                };
                                reader.readAsDataURL(b);
                            });
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            } else {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {}
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(function(){ if (typeof currentModuleView !== 'undefined' && currentModuleView !== 'home' && typeof goHomeHub === 'function') { goHomeHub(); return 'HUB'; } return 'HOME'; })()"
                ) { result ->
                    if (result != null && result.contains("HUB")) {
                        return@evaluateJavascript
                    }
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPressTime < 2000) {
                            finish()
                        } else {
                            lastBackPressTime = now
                            Toast.makeText(this@MainActivity, "再按一次退出 PILLOW LAB", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })

        webView.loadUrl(targetUrl)
    }

    private fun injectAutoUnlock() {
        val unlockJs = """
            (function() {
                if (window.__pillowAutoUnlocked) return;
                let tries = 0;
                const timer = setInterval(function() {
                    tries++;
                    if (tries > 20) { clearInterval(timer); return; }
                    // 1. Try keypad '8' button x6 if visible
                    const lockRoot = document.querySelector('[id*="lock" i], [id*="pass" i], [class*="lock" i], [class*="gate" i]');
                    if (lockRoot && getComputedStyle(lockRoot).display !== 'none') {
                        const btns = Array.from(lockRoot.querySelectorAll('button'));
                        const btn8 = btns.find(b => b.textContent && b.textContent.trim() === '8');
                        if (btn8) {
                            window.__pillowAutoUnlocked = true;
                            for (let i = 0; i < 6; i++) btn8.click();
                            clearInterval(timer);
                            return;
                        }
                        const pwdInput = lockRoot.querySelector('input[type="password"], input[type="tel"], input[type="text"]');
                        if (pwdInput) {
                            window.__pillowAutoUnlocked = true;
                            pwdInput.value = '888888';
                            pwdInput.dispatchEvent(new Event('input', { bubbles: true }));
                            pwdInput.dispatchEvent(new Event('change', { bubbles: true }));
                            const submitBtn = btns.find(b => /解锁|确认|进入|登录/.test(b.textContent || ''));
                            if (submitBtn) submitBtn.click();
                            clearInterval(timer);
                            return;
                        }
                    }
                }, 150);
            })();
        """.trimIndent()
        webView.evaluateJavascript(unlockJs, null)
    }

    private fun injectBlobDownloadHook() {
        val hookJs = """
            (function() {
                if (window.__pillowAndroidHooked) return;
                window.__pillowAndroidHooked = true;
                document.addEventListener('click', function(e) {
                    const a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
                    if (!a || !a.href) return;
                    if (a.href.startsWith('blob:') || a.href.startsWith('data:')) {
                        e.preventDefault();
                        const fname = a.getAttribute('download') || 'pillow_lab_file';
                        fetch(a.href)
                            .then(r => r.blob())
                            .then(b => {
                                const reader = new FileReader();
                                reader.onloadend = () => {
                                    if (window.AndroidDownload) {
                                        window.AndroidDownload.saveBase64(reader.result, fname, b.type || 'application/octet-stream');
                                    }
                                };
                                reader.readAsDataURL(b);
                            });
                    }
                }, true);
            })();
        """.trimIndent()
        webView.evaluateJavascript(hookJs, null)
    }

    inner class DownloadBridge {
        @JavascriptInterface
        fun saveBase64(dataUrl: String, fileName: String, mimeType: String) {
            runOnUiThread {
                try {
                    val base64Data = dataUrl.substringAfter(",", dataUrl)
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val finalName = if (fileName.contains(".")) fileName else {
                        when {
                            mimeType.contains("png") -> "$fileName.png"
                            mimeType.contains("zip") -> "$fileName.zip"
                            mimeType.contains("json") -> "$fileName.json"
                            else -> "$fileName.bin"
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                            put(MediaStore.Downloads.MIME_TYPE, mimeType)
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PILLOW_LAB")
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            Toast.makeText(this@MainActivity, "已保存到下载/PILLOW_LAB/$finalName", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PILLOW_LAB")
                        if (!dir.exists()) dir.mkdirs()
                        val file = File(dir, finalName)
                        FileOutputStream(file).use { it.write(bytes) }
                        Toast.makeText(this@MainActivity, "已保存到下载/PILLOW_LAB/$finalName", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
