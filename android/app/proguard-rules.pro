# WebView 壳自身无混淆需求；保留 JS 接口相关类名
-keepclassmembers class com.miya.shell.MiyaBridge {
    @android.webkit.JavascriptInterface <methods>;
}
