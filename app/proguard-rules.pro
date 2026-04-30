# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }

# Kotlin
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }

# XmlPullParser — android.content.res.XmlResourceParser is a library class that
# implements the org.xmlpull.v1.XmlPullParser interface bundled by xpp3 (via sardine-android→simple-xml).
# xpp3 jar is excluded from the build; Android provides this natively.
-dontwarn org.xmlpull.v1.**

# GSSAPI / Kerberos — optional SMBj/SPNEGO classes not available on Android
-dontwarn org.ietf.jgss.**

# sun.security — optional EdDSA engine class not available on Android
-dontwarn sun.security.**

# javax.el — optional EL (Expression Language) classes used by mbassy, not available on Android
-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper

# Brotli — optional decompressor in commons-compress, not bundled
-dontwarn org.brotli.dec.BrotliInputStream
