# whisper.cpp JNI exports use this class name.
-keep class com.hellfire.ass.media.WhisperNative { *; }
# JavaMail loads protocol and MIME providers by class name.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
