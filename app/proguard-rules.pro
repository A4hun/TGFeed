# ============ TDLib（JNI 保活，必留，否则 R8 后必崩） ============
# JNI 经反射回调 org.drinkless.tdlib 下的全部类（Client/Json/TdApi 全树）
-keep class org.drinkless.tdlib.** { *; }
-keep class io.github.tdlibandroid.** { *; }
-dontwarn org.drinkless.tdlib.**
# 一切 native 方法声明不得混淆/删除
-keepclasseswithmembernames class * { native <methods>; }

# ============ kotlinx-coroutines ============
# ServiceLoader 发现 kotlinx 内部模块（MainDispatcher 等）
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
# 协程调试代理用反射，release 直接禁掉
-assumenosideeffects class kotlinx.coroutines.DebugProbesKt { *; }

# ============ Compose 兜底（compose 自身带 consumer 规则，这里只补保守项） ============
-dontwarn androidx.compose.**

# ============ 本 App ============
# 数据类经 org.json 手工序列化不走反射；ViewModel 保守保活（compose/导航反射兜底）
-keep class dev.tgfeed.core.feed.FeedViewModel { *; }
-keep class dev.tgfeed.core.feed.FeedViewModelFactory { *; }
# LocalStore 的整类 keep 已随 R123 反射桥拆除而移除：该 keep 唯一服务对象是
# LinUCBHolder 反射读私有 dir 字段（release 下字段改名会静默退化的地雷）；
# 现改走编译期强引用的 storageDir 访问口，R8 混淆对其零影响，不再钉死整类。

# 持久化模型类（VideoItem/WatchEvent/HistoryEntry/PlaylistMeta 等）全部经
# org.json 手写 put/optString 序列化，键名是字符串字面量，不依赖类名/字段名反射，
# R8 混淆对其零影响——刻意不整包 keep，避免无收益地扩大混淆白名单。

# 日志规则：release 里静默 Log.d/v（排障保留 w/e）
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
