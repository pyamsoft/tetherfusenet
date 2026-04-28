# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in create.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-dontwarn com.pyamsoft.tetherfi.**

# We are open source, we don't need obfuscation.
# We will still use optimizations though
-dontobfuscate

# Don't obfuscate causes the gradle build to fail after the optimization step
# The addition of !code/allocation/variable is needed to prevent this
-optimizations !code/allocation/variable

# Avoids this line from Retrofit network requests
# java.lang.ClassCastException: java.lang.Class cannot be cast to java.lang.reflect.ParameterizedTyp
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Keep line numbers for Stack Traces
-keepattributes LineNumberTable,SourceFile
-renamesourcefileattribute SourceFile

# https://issuetracker.google.com/issues/336842920
# https://issuetracker.google.com/issues/346808608
-keep class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt { *; }

# Netty dynamically includes Log4J
-dontwarn jdk.jfr.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**

# Keep all Netty classes
-keep class io.netty.** { *; }

# Keep constructors explicitly
-keepclassmembers class io.netty.** {
    public <init>(...);
}

# Netty uses reflection heavily
-keepattributes *Annotation*

# Avoid warnings (optional)
-dontwarn io.netty.**