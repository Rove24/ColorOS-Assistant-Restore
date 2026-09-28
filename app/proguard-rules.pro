# Keep the module entry point discoverable through META-INF/xposed/java_init.list.
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep class com.github.rove24.assistrestore.** { *; }

# The framework API is compileOnly and only ever resolved at runtime.
-dontwarn io.github.libxposed.api.**

# The framework binds to service's provider and talks to the helper over the binder boundary, so
# the provider, the generated stubs and the parcelables have to keep their names and members.
-keep class io.github.libxposed.service.** { *; }
