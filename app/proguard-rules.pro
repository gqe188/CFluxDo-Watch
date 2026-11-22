# Keep JNI bridge methods
-keep class com.cfluxdo.watch.net.DohProxyNative { *; }

# Keep OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep Jsoup
-dontwarn org.jsoup.**
