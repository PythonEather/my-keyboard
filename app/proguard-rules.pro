# Сервис клавиатуры и экран настроек вызываются системой по имени
-keep class com.expressive.keyboard.ComposeKeyboardService { *; }
-keep class com.expressive.keyboard.MainActivity { *; }

# OkHttp (через Coil) ссылается на необязательные TLS-провайдеры
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**

# Rhino (JavaScript модов): классы ищутся по имени через рефлексию
-keep class !org.mozilla.javascript.tools.**, org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-keep class com.expressive.keyboard.QuickJsPluginRuntime {
    native <methods>;
    byte[] dispatchHost(int, byte[]);
}
# The native Lua runtime calls dispatchHost by name from C (JNI): without this rule R8 removes it as unused
-keep class com.expressive.keyboard.LuaPluginRuntime {
    native <methods>;
    java.lang.Object dispatchHost(int, java.lang.Object[]);
}
# plugin_lua.c builds and reads these classes by name (constructors and fields): keep them exactly as they are
-keep class com.expressive.keyboard.LuaMap { *; }
-keep class com.expressive.keyboard.LuaHostRef { *; }
-keep class com.expressive.keyboard.LuaFnRef { *; }
-keep class com.expressive.keyboard.LuaJsonText { *; }
-keep class com.expressive.keyboard.LuaHostError { *; }
-dontwarn java.beans.**
-dontwarn javax.lang.model.**
-dontwarn jdk.dynalink.**
-dontwarn java.awt.**
-dontwarn javax.swing.**

# SDK Anthropic (исправление текста Claude): Jackson сериализует модели запросов через рефлексию —
# без этих правил R8 переименовывает классы и запрос не собирается («JsonMissing cannot be serialized»)
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.Metadata { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.hc.**
-dontwarn com.anthropic.**
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
