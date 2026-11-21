-dontwarn io.github.libxposed.annotation.**
-dontwarn androidx.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
# The framework talks to the module's app through this library (binder interfaces and the provider).
-keep class io.github.libxposed.service.** { *; }
