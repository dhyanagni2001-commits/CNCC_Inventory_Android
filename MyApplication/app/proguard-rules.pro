# ML Kit (bundled barcode model) looks up its parts at run time. When R8 shrinks them, the scanner
# fails to start in release/preview builds only ("Camera unavailable"), while debug builds work.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
