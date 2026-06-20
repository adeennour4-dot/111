# ZeroCopy lib consumer ProGuard rules
-keep public class com.gguf.zerocopy.lib.** { *; }
-keep public interface com.gguf.zerocopy.lib.** { *; }
-keep public enum com.gguf.zerocopy.lib.** { *; }
-keepclasseswithmembers class * { native <methods>; }
-keep @interface * { *; }