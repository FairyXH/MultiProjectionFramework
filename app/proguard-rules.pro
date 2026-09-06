# Vector and LSPatch 1.2 resolve this entry class by string from META-INF/xposed/java_init.list.
-keep class com.template.lsposed.ModuleEntry { *; }

# RegisterNatives binds these method names as strings in JNI_OnLoad (template_native.cpp).
# If you rename any of them, update the native method table too.
-keep class com.template.lsposed.NativeBridge { *; }
-keep class com.template.lsposed.NativeUtils { *; }

# Keep the feature-flag key constants readable in case a downstream tool inspects them.
-keepclassmembers class com.template.lsposed.FeatureRegistry {
    public static final java.lang.String KEY_*;
}

# libxposed API is compileOnly: it is supplied by Vector/LSPatch, not packaged or kept by R8.
-dontwarn io.github.libxposed.api.**
