package com.velnix.tv

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var savedSystemUi = 0

    companion object {
        private const val HOME_URL = "https://2iq2.github.io/VELNIX/"
        private const val SITE_HOST = "2iq2.github.io"
        private val APP_BG = Color.rgb(9, 12, 18)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = APP_BG
        window.navigationBarColor = APP_BG
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        root = FrameLayout(this).apply { setBackgroundColor(APP_BG) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progress, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 5, Gravity.TOP
        ))

        webView = WebView(this).apply {
            setBackgroundColor(APP_BG)
            // Prevent Android's native text-selection handles and copy/paste popup.
            isLongClickable = false
            setOnLongClickListener { true }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                disableTextSelection()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    progress.visibility = View.GONE
                    showLoadError()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase()
                if (scheme == "https" && uri.host == SITE_HOST) return false
                if (scheme == "http" || scheme == "https" || scheme == "mailto" || scheme == "tel") {
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, "تعذر فتح الرابط", Toast.LENGTH_SHORT).show()
                        true
                    }
                }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                savedSystemUi = window.decorView.systemUiVisibility
                webView.visibility = View.GONE
                root.addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }

        root.addView(webView, 0, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    hideCustomView()
                    return
                }
                webView.evaluateJavascript(
                    """(function(){
                        var modal=document.getElementById('playerModal');
                        if(modal && modal.classList.contains('show')){
                            var close=document.getElementById('closePlayer');
                            if(close){close.click();return 'handled';}
                        }
                        if(history.length>1){history.back();return 'handled';}
                        return 'exit';
                    })();"""
                ) { result ->
                    if (result == "\"exit\"") finish()
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(HOME_URL)
        }
    }

    private fun disableTextSelection() {
        webView.evaluateJavascript(
            """(function() {
                var css = '* { -webkit-user-select: none !important; user-select: none !important; -webkit-touch-callout: none !important; } #velnixInstallApp { display: none !important; }';
                var style = document.getElementById('velnix-no-text-selection');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'velnix-no-text-selection';
                    (document.head || document.documentElement).appendChild(style);
                }
                style.textContent = css;
                ['contextmenu', 'selectstart', 'copy', 'cut', 'dragstart'].forEach(function(name) {
                    document.addEventListener(name, function(event) {
                        event.preventDefault();
                    }, true);
                });
            })();""",
            null
        )
    }

    private fun showLoadError() {
        if (root.findViewWithTag<View>("load-error") != null) return
        val panel = TextView(this).apply {
            tag = "load-error"
            text = "تعذر الاتصال بـ VELNIX\nتحقق من الإنترنت ثم اضغط هنا لإعادة المحاولة"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(36, 36, 36, 36)
            setBackgroundColor(APP_BG)
            setOnClickListener {
                root.removeView(this)
                webView.reload()
            }
        }
        root.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun hideCustomView() {
        val view = customView ?: return
        root.removeView(view)
        customView = null
        webView.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = savedSystemUi
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }
}
