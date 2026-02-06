package com.animedetector;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.providers.NNAPIFlags;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

public class OptimizedAnimeDetector {
    private static final String TAG = "AnimeDetector";
    private static final String MODEL_NAME = "anime_detector.onnx";
    private static final int INPUT_SIZE = 640;
    private static final float CONF_THRESHOLD = 0.25f;
    private static final float IOU_THRESHOLD = 0.45f;
    private static final int MAX_DETECTIONS = 100;
    
    private final ByteBuffer directBuffer;
    private final FloatBuffer floatView;
    private final Object bufferLock = new Object();
    
    private final float[] precomputedAreas;
    private final boolean[] suppressedFlags;
    
    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    
    private volatile float adaptiveConfThreshold = CONF_THRESHOLD;
    
    public static class Detection {
        public final float x1, y1, x2, y2;
        public final float width, height;
        public final float confidence;
        public final int classId;
        public final float centerX, centerY;
        public final float area;
        
        public Detection(float x1, float y1, float x2, float y2, float conf, int cls) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.width = x2 - x1;
            this.height = y2 - y1;
            this.confidence = conf;
            this.classId = cls;
            this.centerX = (x1 + x2) * 0.5f;
            this.centerY = (y1 + y2) * 0.5f;
            this.area = width * height;
        }
    }
    
    public static class DetectionResult {
        public final List<Detection> detections;
        public final float avgConfidence;
        public final int imageWidth;
        public final int imageHeight;
        
        public DetectionResult(List<Detection> detections, int width, int height) {
            this.detections = detections;
            this.imageWidth = width;
            this.imageHeight = height;
            this.avgConfidence = calculateAvgConfidence(detections);
        }
        
        private float calculateAvgConfidence(List<Detection> detections) {
            if (detections.isEmpty()) return 0f;
            float sum = 0f;
            for (Detection d : detections) sum += d.confidence;
            return sum / detections.size();
        }
    }
    
    public OptimizedAnimeDetector(Context context) {
        try {
            int cores = Runtime.getRuntime().availableProcessors();
            int onnxThreads = Math.max(1, cores - 2);
            
            env = OrtEnvironment.getEnvironment();
            
            InputStream modelStream = context.getAssets().open(MODEL_NAME);
            byte[] modelBytes = new byte[modelStream.available()];
            modelStream.read(modelBytes);
            modelStream.close();
            
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            
            try {
                options.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16));
                Log.i(TAG, "NNAPI enabled");
            } catch (Exception e) {
                Log.w(TAG, "NNAPI not available");
            }
            
            options.setIntraOpNumThreads(onnxThreads);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            
            if (cores >= 6) {
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.PARALLEL);
            } else {
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            }
            
            session = env.createSession(modelBytes, options);
            inputName = session.getInputNames().iterator().next();
            
            int bufferSize = 3 * INPUT_SIZE * INPUT_SIZE * Float.BYTES;
            directBuffer = ByteBuffer.allocateDirect(bufferSize)
                .order(ByteOrder.nativeOrder());
            floatView = directBuffer.asFloatBuffer();
            
            precomputedAreas = new float[MAX_DETECTIONS];
            suppressedFlags = new boolean[MAX_DETECTIONS];
            
            Log.i(TAG, "Detector initialized");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize", e);
            throw new RuntimeException("Cannot load model", e);
        }
    }
    
    public DetectionResult detect(Bitmap bitmap) {
        try {
            preprocessBitmap(bitmap);
            
            float[][][] output;
            synchronized (bufferLock) {
                floatView.position(0);
                
                long[] shape = {1, 3, INPUT_SIZE, INPUT_SIZE};
                OnnxTensor inputTensor = OnnxTensor.createTensor(env, floatView, shape);
                
                OrtSession.Result result = session.run(
                    Collections.singletonMap(inputName, inputTensor)
                );
                
                output = (float[][][]) result.get(0).getValue();
                
                result.close();
                inputTensor.close();
            }
            
            List<Detection> detections = postprocess(output, bitmap.getWidth(), bitmap.getHeight());
            
            updateAdaptiveThreshold(detections);
            
            return new DetectionResult(detections, bitmap.getWidth(), bitmap.getHeight());
            
        } catch (Exception e) {
            Log.e(TAG, "Detection error", e);
            return new DetectionResult(new ArrayList<>(), bitmap.getWidth(), bitmap.getHeight());
        }
    }
    
    private void preprocessBitmap(Bitmap bitmap) {
        synchronized (bufferLock) {
            floatView.clear();
            
            Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);
            
            int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
            resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
            
            final float inv255 = 1.0f / 255.0f;
            
            for (int c = 0; c < 3; c++) {
                for (int h = 0; h < INPUT_SIZE; h++) {
                    for (int w = 0; w < INPUT_SIZE; w++) {
                        int pixel = pixels[h * INPUT_SIZE + w];
                        float value;
                        
                        if (c == 0) value = ((pixel >> 16) & 0xFF) * inv255;
                        else if (c == 1) value = ((pixel >> 8) & 0xFF) * inv255;
                        else value = (pixel & 0xFF) * inv255;
                        
                        floatView.put(value);
                    }
                }
            }
            
            floatView.flip();
            resized.recycle();
        }
    }
    
    private List<Detection> postprocess(float[][][] output, int originalWidth, int originalHeight) {
        int numPredictions = output[0][0].length;
        
        List<Detection> allDetections = new ArrayList<>();
        
        float scaleX = (float) originalWidth / INPUT_SIZE;
        float scaleY = (float) originalHeight / INPUT_SIZE;
        
        for (int i = 0; i < numPredictions && allDetections.size() < MAX_DETECTIONS; i++) {
            float centerX = output[0][0][i];
            float centerY = output[0][1][i];
            float width = output[0][2][i];
            float height = output[0][3][i];
            
            float conf = output[0][4][i];
            
            if (conf <= adaptiveConfThreshold) continue;
            
            float x1 = (centerX - width * 0.5f) * scaleX;
            float y1 = (centerY - height * 0.5f) * scaleY;
            float x2 = (centerX + width * 0.5f) * scaleX;
            float y2 = (centerY + height * 0.5f) * scaleY;
            
            if (x2 <= x1 || y2 <= y1 || x1 < 0 || y1 < 0) continue;
            
            allDetections.add(new Detection(x1, y1, x2, y2, conf, 0));
        }
        
        return applyNMS(allDetections);
    }
    
    private List<Detection> applyNMS(List<Detection> detections) {
        if (detections.isEmpty()) return detections;
        
        Collections.sort(detections, (a, b) -> Float.compare(b.confidence, a.confidence));
        
        for (int i = 0; i < detections.size(); i++) {
            suppressedFlags[i] = false;
        }
        
        List<Detection> result = new ArrayList<>();
        
        for (int i = 0; i < detections.size(); i++) {
            if (suppressedFlags[i]) continue;
            
            Detection current = detections.get(i);
            result.add(current);
            
            float areaA = current.area;
            
            for (int j = i + 1; j < detections.size(); j++) {
                if (suppressedFlags[j]) continue;
                
                Detection other = detections.get(j);
                
                if (current.x2 < other.x1 || other.x2 < current.x1 ||
                    current.y2 < other.y1 || other.y2 < current.y1) {
                    continue;
                }
                
                float interX1 = Math.max(current.x1, other.x1);
                float interY1 = Math.max(current.y1, other.y1);
                float interX2 = Math.min(current.x2, other.x2);
                float interY2 = Math.min(current.y2, other.y2);
                
                float interArea = (interX2 - interX1) * (interY2 - interY1);
                
                if (interArea <= 0) continue;
                
                float unionArea = areaA + other.area - interArea;
                float iou = interArea / unionArea;
                
                if (iou > IOU_THRESHOLD) {
                    suppressedFlags[j] = true;
                }
            }
        }
        
        return result;
    }
    
    private void updateAdaptiveThreshold(List<Detection> detections) {
        int count = detections.size();
        if (count > 20) {
            adaptiveConfThreshold = Math.min(0.4f, adaptiveConfThreshold + 0.01f);
        } else if (count < 5) {
            adaptiveConfThreshold = Math.max(CONF_THRESHOLD, adaptiveConfThreshold - 0.01f);
        }
    }
    
    public void close() {
        try {
            if (session != null) session.close();
            if (env != null) env.close();
        } catch (Exception e) {
            Log.e(TAG, "Error closing", e);
        }
    }
}



