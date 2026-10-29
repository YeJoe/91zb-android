# --- Media3 / RTMP ---
# media3-datasource-rtmp 通过 JNI 调 librtmp，native 方法所在的类不能被改名/裁剪
-keep class androidx.media3.datasource.rtmp.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# 反射读取的 Renderer / Codec 相关（导出/序列化场景）
-keepattributes *Annotation*, Signature, Exception, InnerClasses

# 我们自己的模型只在进程内用，org.json 手工解析，没有反射依赖，无需 keep 整个包
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
