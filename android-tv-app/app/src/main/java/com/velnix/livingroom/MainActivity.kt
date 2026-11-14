package com.velnix.livingroom

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
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
    private var savedSystemUi: Int = 0

    companion object {
        private const val HOME_URL = "https://2iq2.github.io/VELNIX/"
        private const val SITE_HOST = "2iq2.github.io"
        private val APP_BG = Color.rgb(8, 13, 22)
        private val IMMERSIVE_FLAGS =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.statusBarColor = APP_BG
        window.navigationBarColor = APP_BG
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.decorView.systemUiVisibility = IMMERSIVE_FLAGS

        root = FrameLayout(this).apply {
            setBackgroundColor(APP_BG)
            isFocusable = true
            isFocusableInTouchMode = true
        }

        webView = WebView(this).apply {
            setBackgroundColor(APP_BG)
            isFocusable = true
            isFocusableInTouchMode = true
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
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = true
                }
                userAgentString = "$userAgentString VELNIXAndroidTVApp"
            }
        }

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(201, 169, 110))
            visibility = View.GONE
        }

        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            progress,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                4,
                Gravity.TOP
            )
        )
        setContentView(root)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                installRemoteFocusSupport()
                webView.requestFocus(View.FOCUS_DOWN)
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

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase()
                if (scheme == "https" && uri.host == SITE_HOST) return false

                if (scheme == "http" || scheme == "https" || scheme == "mailto" || scheme == "tel") {
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(
                            this@MainActivity,
                            "لا يوجد تطبيق مناسب لفتح هذا الرابط",
                            Toast.LENGTH_SHORT
                        ).show()
                        true
                    } catch (_: Exception) {
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
                progress.visibility = View.GONE
                root.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                window.decorView.systemUiVisibility = IMMERSIVE_FLAGS
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    hideCustomView()
                    return
                }
                webView.evaluateJavascript(
                    """(function(){
                        const player=document.getElementById('playerModal');
                        if(player && player.classList.contains('velnix-tv-fullscreen')){
                            player.classList.remove('velnix-tv-fullscreen');
                            const focus=document.getElementById('vcFullscreen');
                            if(focus)focus.focus();
                            return 'handled';
                        }
                        if(document.fullscreenElement){
                            document.exitFullscreen?.();
                            return 'handled';
                        }
                        if(player && player.classList.contains('show')){
                            const close=document.getElementById('closePlayer');
                            if(close){close.click();return 'handled';}
                        }
                        const modal=document.querySelector('.modal.show,[role="dialog"].show');
                        if(modal){
                            const close=modal.querySelector('.close,[data-close],button[aria-label*="close" i],button[aria-label*="إغلاق"]');
                            if(close){close.click();return 'handled';}
                            modal.classList.remove('show');
                            return 'handled';
                        }
                        if(window.scrollY>8){window.scrollTo({top:0,behavior:'auto'});return 'handled';}
                        if(history.length>1){history.back();return 'handled';}
                        return 'exit';
                    })();"""
                ) { result ->
                    if (result == "\"exit\"") finish()
                }
            }
        })

        if (savedInstanceState != null) {
            val restored = webView.restoreState(savedInstanceState)
            if (restored == null) webView.loadUrl(HOME_URL)
        } else {
            webView.loadUrl(HOME_URL)
        }

        webView.requestFocus(View.FOCUS_DOWN)
    }

    private fun installRemoteFocusSupport() {
        webView.evaluateJavascript(
            """(function(){
                if(!/VELNIXAndroidTVApp/.test(navigator.userAgent)) return;
                document.documentElement.classList.add('velnix-android-tv-app');
                const selector = '.channel,.folder-card,div[onclick],article[onclick],li[onclick]';
                const prepareOne = el => {
                    if(!(el instanceof HTMLElement) || !el.matches(selector)) return;
                    if(!el.hasAttribute('tabindex')) el.tabIndex = 0;
                    if(!el.hasAttribute('role')) el.setAttribute('role','button');
                };
                const scanAdded = node => {
                    if(!node || node.nodeType !== 1) return;
                    prepareOne(node);
                    if(node.querySelectorAll) node.querySelectorAll(selector).forEach(prepareOne);
                };
                document.querySelectorAll(selector).forEach(prepareOne);
                if(!window.__velnixTvFocusObserver){
                    window.__velnixTvFocusObserver = new MutationObserver(records => {
                        for(const record of records){
                            for(const node of record.addedNodes) scanAdded(node);
                        }
                    });
                    window.__velnixTvFocusObserver.observe(document.documentElement,{childList:true,subtree:true});
                }
                if(!window.__velnixTvEnterHandler){
                    window.__velnixTvEnterHandler = true;
                    document.addEventListener('keydown',function(event){
                        const active=document.activeElement;
                        if(!active || !active.matches('.channel,.folder-card')) return;
                        if(event.key==='Enter' || event.key===' ' || event.keyCode===13 || event.keyCode===23 || event.keyCode===66){
                            event.preventDefault();
                            event.stopPropagation();
                            active.click();
                        }
                    },true);
                }
                requestAnimationFrame(function(){
                    if(!document.activeElement || document.activeElement===document.body){
                        const first=document.querySelector('#exploreBtn,#loginBtn,a[href="#home"],.filter,.channel');
                        if(first) first.focus();
                    }
                });
            })();""",
            null
        )
    }

    private fun showLoadError() {
        if (root.findViewWithTag<View>("tv-load-error") != null) return
        val panel = TextView(this).apply {
            tag = "tv-load-error"
            text = "تعذر الاتصال بـ VELNIX\nتحقق من الإنترنت ثم اضغط OK لإعادة المحاولة"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(56, 48, 56, 48)
            setBackgroundColor(APP_BG)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                root.removeView(this)
                webView.reload()
            }
        }
        root.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        panel.requestFocus()
    }

    private fun hideCustomView() {
        val view = customView ?: return
        root.removeView(view)
        customView = null
        webView.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = savedSystemUi
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView.requestFocus(View.FOCUS_DOWN)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
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
