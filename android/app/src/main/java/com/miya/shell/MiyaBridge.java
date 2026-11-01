package com.miya.shell;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * 暴露给网页的 JS 桥：window.MiyaNative
 * 只在主框架生效（UsesOnloadInBackground 之外的普通调用即可）。
 */
public class MiyaBridge {

    public static final String NAME = "MiyaNative";
    public static final int REQ_FILE = 4001;
    public static final int REQ_NOTIFICATION = MiyaNotifier.REQ_NOTIFICATION;

    private final Activity activity;
    private final WebView webView;
    private final MiyaDownloader downloader = new MiyaDownloader();
    private volatile int keyboardHeight = 0;

    public MiyaBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    /* ---------- 原生 → 网页 ---------- */

    void onKeyboardHeightChanged(int px) {
        keyboardHeight = px;
        emit("miya-keyboard", "{\"height\":" + px + "}");
    }

    void onNotificationPermissionResult(boolean granted) {
        emit("miya-notification-permission", "{\"granted\":" + granted + "}");
    }

    private void emit(String eventName, String detailJson) {
        if (webView == null) return;
        final String js = "window.dispatchEvent(new CustomEvent(" + JSONObject.quote(eventName)
                + ",{detail:" + detailJson + "}));";
        webView.post(() -> {
            try {
                webView.evaluateJavascript(js, null);
            } catch (Exception ignored) {
            }
        });
    }

    /* ---------- 信息 ---------- */

    @JavascriptInterface
    public String platform() {
        return "android";
    }

    @JavascriptInterface
    public String version() {
        return "1.0.0";
    }

    @JavascriptInterface
    public int keyboardHeight() {
        return keyboardHeight;
    }

    /* ---------- 通知 ---------- */

    @JavascriptInterface
    public String notificationPermission() {
        return MiyaNotifier.isEnabled(activity) ? "granted" : "default";
    }

    @JavascriptInterface
    public void requestNotifications() {
        activity.runOnUiThread(() -> MiyaNotifier.requestPermissionIfNeeded(activity));
    }

    @JavascriptInterface
    public void notify(String json) {
        try {
            JSONObject o = new JSONObject(json == null || json.isEmpty() ? "{}" : json);
            MiyaNotifier.show(activity,
                    o.optString("title", "Miya"),
                    o.optString("body", ""),
                    o.optString("tag", ""),
                    o.optString("chatId", ""));
        } catch (Exception ignored) {
        }
    }

    /* ---------- 文件导出（分块写盘） ---------- */

    @JavascriptInterface
    public boolean downloadBegin(String name, String mime) {
        return downloader.begin(activity, name, mime);
    }

    @JavascriptInterface
    public boolean downloadChunk(String base64) {
        try {
            byte[] data = Base64.decode(base64 == null ? "" : base64, Base64.DEFAULT);
            return downloader.write(data);
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public String downloadFinish() {
        return downloader.finish();
    }

    @JavascriptInterface
    public void downloadAbort() {
        downloader.abort();
    }

    /* ---------- 杂项 ---------- */

    @JavascriptInterface
    public void toast(String msg) {
        final String text = msg == null ? "" : msg;
        activity.runOnUiThread(() ->
                Toast.makeText(activity, text, Toast.LENGTH_SHORT).show());
    }

    @JavascriptInterface
    public void openExternal(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void exitApp() {
        activity.runOnUiThread(() -> activity.moveTaskToBack(true));
    }
}
