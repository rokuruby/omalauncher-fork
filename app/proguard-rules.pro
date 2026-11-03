# LiteRT-LM (Gemma on the phone): its native code calls back into these classes by name, and the AAR ships no rules.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep interface com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
