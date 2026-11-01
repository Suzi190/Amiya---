package com.miya.shell;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Miya 小手机 WebView 外壳。
 *
 * 1. 真·全屏：隐藏状态栏 + 导航栏，内容延伸到刘海区，状态栏区域透明（不是黑条）。
 * 2. 键盘不遮挡：把 IME 高度转成 WebView 的 bottomMargin，等价 adjustResize，
 *    页面 100dvh / visualViewport 自然缩小，输入框永远在可见区域内。
 * 3. 返回：拦截系统返回（含左右边缘手势），先交给网页按层关闭，再退历史，最后后台化。
 * 4. 导入：onShowFileChooser；导出：MiyaBridge 分块写入 Downloads/Miya。
 * 5. 通知：Android 13+ 运行时权限 + 通知渠道。
 */
public class MainActivity extends AppCompatActivity {

    private static final String START_URL = "https://localhost/index.html";

    /** 覆盖安装后清一次 Service Worker 缓存的标记（按 versionCode，见 clearStaleWebCacheOnce） */
    private static final String KEY_CACHE_VERSION = "cache_cleared_version";
    private static final String CLEAR_CACHE_JS =
            "(function(){try{"
          + "if(!(window.caches&&caches.keys))return;"
          + "caches.keys().then(function(ks){"
          + "if(!ks.length)return;"
          + "Promise.all(ks.map(function(k){return caches.delete(k);})).then(function(){"
          + "var done=false;function reload(){if(done)return;done=true;location.reload();}"
          + "var sw=navigator.serviceWorker;"
          + "if(sw&&sw.getRegistrations){sw.getRegistrations().then(function(rs){"
          + "rs.forEach(function(r){try{r.unregister();}catch(e){}});reload();});}else{reload();}"
          + "});});"
          + "}catch(e){}})();";

    private FrameLayout root;
    private WebView webView;
    private MiyaBridge bridge;
    private ValueCallback<Uri[]> pendingFileCallback;
    private int imeBottomPx = 0;
    private boolean cacheCheckDone = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setBackgroundDrawable(new ColorDrawable(Color.parseColor("#F4F1EC")));
        enterImmersiveMode();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#F4F1EC"));

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        configureWebView();
        root.addView(webView);
        setContentView(root);

        bridge = new MiyaBridge(this, webView);
        webView.addJavascriptInterface(bridge, MiyaBridge.NAME);

        // 键盘：IME 高度逐帧压缩 WebView 可视高度（跟随系统键盘动画，不硬跳变）
        setupImeHandling();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackPressed();
            }
        });

        MiyaNotifier.ensureChannel(this);
        // 稍等页面出现再弹通知授权，避免冷启动时被系统弹窗打断
        root.postDelayed(() -> MiyaNotifier.requestPermissionIfNeeded(this), 1800);

        webView.loadUrl(START_URL);
        handleDeepLink(getIntent());
    }

    /* ---------------- 全屏 ---------------- */

    private void enterImmersiveMode() {
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        // API 30+ 窗口不再因 IME 自动缩放，由 insets 手动压缩 WebView；
        // 更老的系统仍用传统的 adjustResize（旧系统上 IME insets 不可靠）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        } else {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        hideSystemBars();
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        // 从上 / 下滑动只临时浮出，不驻留；保证完整全屏且状态栏不是黑色色块
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
    }

    /* ---------------- 键盘 ---------------- */

    /**
     * API 30+ 用 WindowInsetsAnimationCompat 跟随系统键盘动画逐帧压缩 WebView，
     * 而不是等动画结束再一次性跳变（后者就是"键盘弹出时画面一顿"的来源）。
     * 旧的 insets 监听保留：动画开始/结束的最终值仍以它为准。
     */
    private void setupImeHandling() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            applyImeLayout(ime);
            scheduleKeyboardNotify();
            return insets;
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ViewCompat.setWindowInsetsAnimationCallback(root,
                    new WindowInsetsAnimationCompat.Callback(
                            WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                        @Override
                        public WindowInsetsCompat onProgress(WindowInsetsCompat insets,
                                                             List<WindowInsetsAnimationCompat> running) {
                            for (int i = 0; i < running.size(); i++) {
                                if ((running.get(i).getTypeMask() & WindowInsetsCompat.Type.ime()) != 0) {
                                    applyImeLayout(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
                                    break;
                                }
                            }
                            return insets;
                        }

                        @Override
                        public void onEnd(WindowInsetsAnimationCompat animation) {
                            if ((animation.getTypeMask() & WindowInsetsCompat.Type.ime()) == 0) return;
                            scheduleKeyboardNotify();
                        }
                    });
        }
    }

    /** 只改布局，不回调网页：动画期间每帧都会走这里，回调网页会排队卡顿。 */
    private void applyImeLayout(int bottomPx) {
        // 旧系统由窗口 resize 处理（adjustResize），这里不重复补偿
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        if (webView == null) return;
        int px = Math.max(0, bottomPx);
        if (px == imeBottomPx) return;
        imeBottomPx = px;
        ViewGroup.LayoutParams lp = webView.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) lp).bottomMargin = px;
            webView.setLayoutParams(lp);
        }
    }

    /** 键盘高度稳定后再通知网页（合并连续变化，避免 evaluateJavascript 排队）。 */
    private void scheduleKeyboardNotify() {
        if (webView == null) return;
        webView.removeCallbacks(imeNotifyTask);
        webView.postDelayed(imeNotifyTask, 110);
    }

    private final Runnable imeNotifyTask = new Runnable() {
        @Override
        public void run() {
            if (webView == null) return;
            if (bridge != null) bridge.onKeyboardHeightChanged(imeBottomPx);
            if (imeBottomPx > 0) revealFocusedInput();
        }
    };

    /** 可视区被键盘压缩后，把正在输入的框滚回可见处；已在视野内则不动（nearest）。 */
    private void revealFocusedInput() {
        if (webView == null) return;
        webView.evaluateJavascript(
                "(function(){try{var a=document.activeElement;"
                        + "if(a&&a!==document.body&&a.scrollIntoView){"
                        + "a.scrollIntoView({block:'nearest',inline:'nearest'});}}catch(e){}})();",
                null);
    }

    /* ---------------- WebView ---------------- */

    /**
     * 覆盖安装后 Service Worker 的 Cache Storage 里还留着上一版网页资源，
     * 新打进 APK 的 JS/CSS 就不会生效（改了代码却看不到效果）。每个 versionCode 只清一次：
     * 删掉 Cache Storage、注销 Service Worker，然后刷新一次页面。
     * 只动缓存，localStorage / IndexedDB / OPFS 里的聊天记录都不受影响。
     */
    @SuppressWarnings("deprecation")
    private void clearStaleWebCacheOnce() {
        if (cacheCheckDone) return;
        cacheCheckDone = true;
        int version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        SharedPreferences sp = getSharedPreferences("miya_shell", MODE_PRIVATE);
        if (sp.getInt(KEY_CACHE_VERSION, 0) >= version) return;
        sp.edit().putInt(KEY_CACHE_VERSION, version).apply();
        webView.evaluateJavascript(CLEAR_CACHE_JS, value -> { });
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            s.setForceDark(WebSettings.FORCE_DARK_OFF);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            s.setAlgorithmicDarkeningAllowed(false);
        }

        webView.setBackgroundColor(Color.parseColor("#F4F1EC"));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                // 默认域名是 appassets.androidplatform.net，页面用的是 https://localhost，
                // 必须显式设置，否则所有请求都拦截不到（表现为白屏）。
                .setDomain("localhost")
                .addPathHandler("/", this::openAsset)
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                clearStaleWebCacheOnce();
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (url != null && "localhost".equals(url.getHost())) {
                    WebResourceResponse res = assetLoader.shouldInterceptRequest(url);
                    if (res != null) return res;
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (url == null) return false;
                String scheme = url.getScheme() == null ? "" : url.getScheme();
                String host = url.getHost() == null ? "" : url.getHost();
                if ("localhost".equals(host)) return false;
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    openExternal(url);
                    return true;
                }
                if (!"about".equals(scheme) && !"javascript".equals(scheme)
                        && !"data".equals(scheme) && !"blob".equals(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, url));
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                    pendingFileCallback = null;
                }
                pendingFileCallback = callback;
                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    // 用 */* 打开系统文件选择器：项目同时接受 .png 角色卡与 .json，
                    // 部分机型按扩展名过滤 MIME 会选不到文件。
                    intent.setType("*/*");
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), MiyaBridge.REQ_FILE);
                    return true;
                } catch (Exception e) {
                    pendingFileCallback = null;
                    Toast.makeText(MainActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    try {
                        request.grant(request.getResources());
                    } catch (Exception ignored) {
                    }
                });
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            // blob: 导出走 MiyaBridge 分块写盘，这里只兜底 http(s) 直链
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))
                    && !url.contains("localhost")) {
                openExternal(Uri.parse(url));
            }
        });
    }

    private WebResourceResponse openAsset(String path) {
        try {
            String clean = path == null ? "" : path;
            while (clean.startsWith("/")) clean = clean.substring(1);
            int q = clean.indexOf('?');
            if (q >= 0) clean = clean.substring(0, q);
            if (clean.isEmpty()) clean = "index.html";
            if (clean.contains("..")) return null;

            InputStream is = getAssets().open("public/" + clean);
            String mime = guessMime(clean);
            boolean text = mime.startsWith("text/") || mime.contains("javascript")
                    || mime.contains("json") || mime.contains("svg") || mime.contains("xml");
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-cache");
            headers.put("Access-Control-Allow-Origin", "*");
            WebResourceResponse res = new WebResourceResponse(mime, text ? "utf-8" : null, is);
            res.setResponseHeaders(headers);
            return res;
        } catch (Exception e) {
            return null;
        }
    }

    private static String guessMime(String p) {
        String name = p.toLowerCase(Locale.US);
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html";
        if (name.endsWith(".js") || name.endsWith(".mjs")) return "application/javascript";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".json") || name.endsWith(".webmanifest") || name.endsWith(".map")) {
            return "application/json";
        }
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".ico")) return "image/x-icon";
        if (name.endsWith(".bmp")) return "image/bmp";
        if (name.endsWith(".woff")) return "font/woff";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".ttf")) return "font/ttf";
        if (name.endsWith(".otf")) return "font/otf";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".m4a")) return "audio/mp4";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".ogg")) return "audio/ogg";
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".txt") || name.endsWith(".md")) return "text/plain";
        if (name.endsWith(".wasm")) return "application/wasm";
        return "application/octet-stream";
    }

    /* ---------------- 返回 ---------------- */

    private void handleBackPressed() {
        if (webView == null) {
            finish();
            return;
        }
        webView.evaluateJavascript(
                "(function(){try{return !!(window.__miyaHandleBack&&window.__miyaHandleBack());}"
                        + "catch(e){return false;}})()",
                value -> {
                    if ("true".equals(value)) return; // 网页已关闭上一层
                    if (webView != null && webView.canGoBack()) {
                        webView.goBack();
                        return;
                    }
                    // 已在桌面层：退到后台，不销毁进程、不丢数据
                    moveTaskToBack(true);
                });
    }

    /* ---------------- 文件选择 / 权限回调 ---------------- */

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == MiyaBridge.REQ_FILE) {
            if (pendingFileCallback != null) {
                Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                pendingFileCallback.onReceiveValue(results);
                pendingFileCallback = null;
            } else {
                super.onActivityResult(requestCode, resultCode, data);
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MiyaBridge.REQ_NOTIFICATION && bridge != null) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            bridge.onNotificationPermissionResult(granted);
        }
    }

    /* ---------------- 通知深链 ---------------- */

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeepLink(intent);
    }

    private void handleDeepLink(Intent intent) {
        if (intent == null || webView == null) return;
        String link = intent.getStringExtra(MiyaNotifier.EXTRA_LINK);
        if (link == null || link.isEmpty()) return;
        intent.removeExtra(MiyaNotifier.EXTRA_LINK);
        final String script = "window.__miyaDeepLink&&window.__miyaDeepLink("
                + JSONObject.quote(link) + ");";
        webView.postDelayed(() -> {
            if (webView != null) webView.evaluateJavascript(script, null);
        }, 1600);
    }

    private void openExternal(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = null;
        }
        if (webView != null) {
            root.removeView(webView);
            webView.removeJavascriptInterface(MiyaBridge.NAME);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
