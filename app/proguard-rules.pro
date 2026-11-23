-keepattributes SourceFile,LineNumberTable,JavascriptInterface
-renamesourcefileattribute SourceFile
# the page calls these through window.Android
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
