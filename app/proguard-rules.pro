# Vector and LSPatch 1.2 resolve this entry class by string from META-INF/xposed/java_init.list.
-keep class com.fairyxh.multiprojection.ModuleEntry { *; }

# Keep the feature-flag key constants readable in case a downstream tool inspects them.
-keepclassmembers class com.fairyxh.multiprojection.FeatureRegistry {
    public static final java.lang.String KEY_*;
}

# libxposed API is compileOnly: it is supplied by Vector/LSPatch, not packaged or kept by R8.
-dontwarn io.github.libxposed.api.**