# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Detection classes
-keep class com.animedetector.OptimizedAnimeDetector$Detection { *; }
-keep class com.animedetector.OptimizedAnimeDetector$DetectionResult { *; }

-optimizationpasses 5
-dontusemixedcaseclassnames


