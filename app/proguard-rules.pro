# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(); }
# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { **[] $VALUES; public *; }
# Gson 实体
-keep class com.seanming.player.bean.** { *; }
# Spider jar 反射入口必须保留签名
-keep public class com.github.catvod.spider.** { public *; }
-keepclassmembers class * { public <init>(...); }
# QuickJS
-keep class com.whl.quickjs.** { *; }
-dontwarn com.whl.quickjs.**
