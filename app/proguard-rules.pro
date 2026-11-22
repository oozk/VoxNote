# JNI entry points and callbacks invoked from native code
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keep class com.offlinetranscriber.engine.WhisperNative { *; }
-keep interface com.offlinetranscriber.engine.NativeSegmentListener { *; }
-keep class * implements com.offlinetranscriber.engine.NativeSegmentListener { *; }
