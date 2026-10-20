# 保留 JavaScriptInterface 方法不被混淆
-keepclassmembers class com.carcontrol.BleBridge {
    @android.webkit.JavascriptInterface <methods>;
}
