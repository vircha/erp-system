# ============================================================================
# R8 / ProGuard 规则
#
# 🔴 v5.7.2（2026-10-22）：以前minifyEnabled=false，这份文件形同虚设。
#    开了 R8 之后，下面每一条都是「不写就线上白屏」的级别。
#
# 核心风险：本App 的 Models.kt 全靠 Gson 反射解析，字段名 / 类名被改或被删，
#    接口返回就全是 null —— 页面上表现为「表格空白 / 金额显示 0」，但HTTP 200，
#    排查起来极难。所以下面 keep 规则**不许删**。
# ============================================================================

# ---------- 保留注解与签名（R8 基础要求）----------
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes SourceFile,LineNumberTable
# 🔴 Kotlin 协程 + Compose 依赖反射拿 metadata
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# ============================================================================
# ① Gson —— 🔴 最高优先级，不写就数据全null
# ============================================================================
# Gson 用 Class.getDeclaredFields() 反射读字段；R8 看不到这些调用，
# 会认为字段未被使用而删除（即使有 @SerializedName 也一样）。
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# 🔴 本项目所有网络模型所在的包：整个保留
-keep class com.heping.erpnative.data.** { *; }
# @SerializedName 标注的字段
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# 泛型擦除：List<T> / Map<K,V> 的T 会被擦成 Object，Gson 靠 Signature还原
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
    <init>(...);
}
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ---------- Gson 内部用到的 sun.misc.Unsafe（Android 侧）----------
-dontwarn sun.misc.**
-keep class sun.misc.Unsafe { *; }

# ============================================================================
# ② Retrofit / OkHttp —— 接口签名靠反射
# ============================================================================
# Retrofit 读接口方法的**参数注解**和**泛型返回值**来定请求格式；
# 混淆后方法名变了没关系（注解在），但泛型 Signature 丢了就解析不出 List<T>。
-keepattributes Signature,InnerClasses,EnclosingMethod
-keep,allowobfuscation interface com.heping.erpnative.data.ErpApi
-keep,allowobfuscation class retrofit2.** { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
# Retrofit 的 R8 官方推荐规则
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-keep,allowobfuscation class kotlin.coroutines.Continuation

# ============================================================================
# ③ kotlinx.serialization / Compose（若间接用到）
# ============================================================================
-keepclassmembers class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.**

# ============================================================================
# ④ ML Kit（中文 OCR + 条码扫描）—— 大量 JNI 反射调用
# ============================================================================
#🔴 MLKit 的 Java↔native 桥接全靠反射，混淆后 on-device 扫描直接崩。
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
# ML Kit 依赖的 TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ============================================================================
# ⑤ AndroidMLKit 模型的 native 加载（so 里按类名找 JNI 方法）
# ============================================================================
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepclassmembers class * {
    native <methods>;
}
# DataStore（protobuf）
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ============================================================================
# ⑥ Compose
# ============================================================================
# Compose 编译器已生成代码，但运行时仍会反射拿 Composable 的 key/group 信息
-dontwarn androidx.compose.**
-keepclassmembers class androidx.compose.runtime.** { *; }

# ============================================================================
# ⑦ AndroidSVG（打印小票转图片）
# ============================================================================
-dontwarn com.caverock.**

# ============================================================================
# ⑧ Kotlin 协程
# ============================================================================
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn java.lang.invoke.**

# ============================================================================
# ⑨ 保留行号，方便线上崩溃栈还原（-g 之外的保险）
# ============================================================================
-keepattributes SourceFile,LineNumberTable