package com.animedetector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class OverlayService extends Service {
    private static final String TAG = "OverlayService";
    private static final String CHANNEL_ID = "AnimeDetectorChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int FRAME_SKIP = 2;
    
    // ✅ إضافة: timeout لإخفاء المربعات بعد عدم الكشف
    private static final long HIDE_TIMEOUT = 300; // 300ms بدون كشف = إخفاء
    
    private static final AtomicBoolean isServiceRunning = new AtomicBoolean(false);
    
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    
    private WindowManager windowManager;
    private View overlayView;
    private ImageView overlayImageView;
    private TextView statsText;
    
    private OptimizedAnimeDetector detector;
    private DetectionSmoother smoother;
    private PerformanceMonitor perfMonitor;
    
    private HandlerThread captureThread;
    private Handler captureHandler;
    private HandlerThread detectionThread;
    private Handler detectionHandler;
    private final Handler mainHandler = new Handler();
    
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final AtomicInteger frameCounter = new AtomicInteger(0);
    
    private volatile Bitmap overlayBitmap;
    private final Object overlayLock = new Object();
    
    // ✅ Paint محسّن مع نمط
    private final Paint censorPaint = new Paint();
    private Bitmap patternBitmap; // النمط المخصص
    
    // ✅ إضافة: تتبع آخر كشف
    private volatile long lastDetectionTime = 0;
    private final Runnable hideOverlayRunnable = this::hideOverlayIfNeeded;
    
    public static boolean isRunning() {
        return isServiceRunning.get();
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service created");
        
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;
        
        captureThread = new HandlerThread("CaptureThread");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        
        detectionThread = new HandlerThread("DetectionThread");
        detectionThread.start();
        detectionHandler = new Handler(detectionThread.getLooper());
        
        // ✅ إنشاء النمط المخصص
        createCensorPattern();
        
        try {
            detector = new OptimizedAnimeDetector(this);
            smoother = new DetectionSmoother(5);
            perfMonitor = new PerformanceMonitor();
        } catch (Exception e) {
            Log.e(TAG, "Failed to init detector", e);
            stopSelf();
            return;
        }
        
        isServiceRunning.set(true);
    }
    
    /**
     * ✅ إنشاء نمط حظر مخصص (أسود بالكامل مع texture)
     */
    private void createCensorPattern() {
        censorPaint.setStyle(Paint.Style.FILL);
        censorPaint.setAntiAlias(false);
        
        // إنشاء bitmap صغير للنمط
        int patternSize = 40;
        patternBitmap = Bitmap.createBitmap(patternSize, patternSize, Bitmap.Config.ARGB_8888);
        Canvas patternCanvas = new Canvas(patternBitmap);
        
        // خلفية سوداء كاملة
        patternCanvas.drawColor(Color.BLACK);
        
        // إضافة خطوط مائلة سوداء أغمق للنمط
        Paint linePaint = new Paint();
        linePaint.setColor(Color.argb(80, 0, 0, 0)); // أسود شبه شفاف
        linePaint.setStrokeWidth(3);
        linePaint.setAntiAlias(false);
        
        // رسم خطوط مائلة
        for (int i = -patternSize; i < patternSize * 2; i += 8) {
            patternCanvas.drawLine(i, 0, i + patternSize, patternSize, linePaint);
        }
        
        // استخدام النمط في Paint
        android.graphics.BitmapShader shader = new android.graphics.BitmapShader(
            patternBitmap,
            android.graphics.Shader.TileMode.REPEAT,
            android.graphics.Shader.TileMode.REPEAT
        );
        
        censorPaint.setShader(shader);
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        
        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("data");
        
        if (resultCode == 0 || data == null) {
            Log.e(TAG, "Invalid capture data");
            stopSelf();
            return START_NOT_STICKY;
        }
        
        startScreenCapture(resultCode, data);
        createOverlayView();
        
        return START_STICKY;
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Anime Detector", NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("كاشف الأنمي");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        );
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Anime Detector")
            .setContentText("🎯 يكشف الأنمي")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }
    
    private void startScreenCapture(int resultCode, Intent data) {
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
        );
        
        imageReader.setOnImageAvailableListener(reader -> {
            if (frameCounter.incrementAndGet() % (FRAME_SKIP + 1) != 0) {
                Image image = reader.acquireLatestImage();
                if (image != null) image.close();
                return;
            }
            
            Image image = reader.acquireLatestImage();
            if (image != null) {
                processImage(image);
            }
        }, captureHandler);
        
        mediaProjection = projectionManager.getMediaProjection(resultCode, data);
        
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "AnimeDetector", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(), null, captureHandler
        );
        
        Log.i(TAG, "Screen capture started");
    }
    
    private void createOverlayView() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null);
        overlayImageView = overlayView.findViewById(R.id.overlayImage);
        statsText = overlayView.findViewById(R.id.statsText);
        
        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        
        params.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(overlayView, params);
        
        Log.i(TAG, "Overlay created");
    }
    
    private void processImage(Image image) {
        if (!isProcessing.compareAndSet(false, true)) {
            image.close();
            return;
        }
        
        perfMonitor.frameStart();
        
        detectionHandler.post(() -> {
            try {
                long start = System.currentTimeMillis();
                
                Bitmap bitmap = imageToBitmap(image);
                
                if (bitmap != null) {
                    OptimizedAnimeDetector.DetectionResult result = detector.detect(bitmap);
                    result = smoother.smooth(result);
                    
                    long elapsed = System.currentTimeMillis() - start;
                    perfMonitor.frameEnd(elapsed);
                    
                    // ✅ تحديث وقت آخر كشف
                    if (!result.detections.isEmpty()) {
                        lastDetectionTime = System.currentTimeMillis();
                    }
                    
                    updateOverlay(result, bitmap.getWidth(), bitmap.getHeight());
                    updateStats(result, elapsed);
                    
                    // ✅ جدولة فحص الإخفاء
                    mainHandler.removeCallbacks(hideOverlayRunnable);
                    mainHandler.postDelayed(hideOverlayRunnable, HIDE_TIMEOUT);
                    
                    bitmap.recycle();
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Process error", e);
            } finally {
                image.close();
                isProcessing.set(false);
            }
        });
    }
    
    /**
     * ✅ إخفاء overlay إذا لم يكن هناك كشف لفترة
     */
    private void hideOverlayIfNeeded() {
        long timeSinceLastDetection = System.currentTimeMillis() - lastDetectionTime;
        
        if (timeSinceLastDetection > HIDE_TIMEOUT) {
            synchronized (overlayLock) {
                if (overlayBitmap != null && !overlayBitmap.isRecycled()) {
                    // مسح الـ overlay
                    Canvas canvas = new Canvas(overlayBitmap);
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                    
                    mainHandler.post(() -> {
                        if (overlayImageView != null) {
                            overlayImageView.setImageBitmap(overlayBitmap);
                        }
                    });
                }
            }
        }
    }
    
    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * screenWidth;
        
        Bitmap bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        );
        
        bitmap.copyPixelsFromBuffer(buffer);
        
        if (rowPadding != 0) {
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);
            bitmap.recycle();
            return cropped;
        }
        
        return bitmap;
    }
    
    /**
     * ✅ تحديث overlay مع النمط المخصص
     */
    private void updateOverlay(OptimizedAnimeDetector.DetectionResult result, int w, int h) {
        synchronized (overlayLock) {
            if (overlayBitmap == null || overlayBitmap.getWidth() != w || 
                overlayBitmap.getHeight() != h || overlayBitmap.isRecycled()) {
                
                if (overlayBitmap != null && !overlayBitmap.isRecycled()) {
                    overlayBitmap.recycle();
                }
                overlayBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            
            Canvas canvas = new Canvas(overlayBitmap);
            
            // ✅ مسح الـ canvas بالكامل أولاً
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            
            // ✅ رسم المربعات بالنمط المخصص
            if (!result.detections.isEmpty()) {
                for (OptimizedAnimeDetector.Detection det : result.detections) {
                    float margin = Math.min(det.width, det.height) * 0.05f;
                    
                    // رسم بالنمط المخصص (أسود كامل مع texture)
                    canvas.drawRect(
                        det.x1 - margin, det.y1 - margin,
                        det.x2 + margin, det.y2 + margin,
                        censorPaint
                    );
                }
            }
            
            mainHandler.post(() -> {
                if (overlayImageView != null) {
                    overlayImageView.setImageBitmap(overlayBitmap);
                }
            });
        }
    }
    
    private void updateStats(OptimizedAnimeDetector.DetectionResult result, long elapsed) {
        mainHandler.post(() -> {
            if (statsText != null) {
                String stats = String.format(
                    "🎯 %d | ⚡%dms | 📊%.0f%% | FPS:%.1f",
                    result.detections.size(),
                    elapsed,
                    result.avgConfidence * 100,
                    perfMonitor.getCurrentFPS()
                );
                statsText.setText(stats);
            }
        });
    }
    
    @Override
    public void onDestroy() {
        isServiceRunning.set(false);
        
        // ✅ إزالة callbacks
        mainHandler.removeCallbacks(hideOverlayRunnable);
        
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
        if (imageReader != null) imageReader.close();
        
        if (overlayView != null && windowManager != null) {
            windowManager.removeView(overlayView);
        }
        
        synchronized (overlayLock) {
            if (overlayBitmap != null && !overlayBitmap.isRecycled()) {
                overlayBitmap.recycle();
            }
        }
        
        // ✅ تنظيف النمط
        if (patternBitmap != null && !patternBitmap.isRecycled()) {
            patternBitmap.recycle();
        }
        
        if (detector != null) detector.close();
        if (smoother != null) smoother.clear();
        
        if (captureThread != null) captureThread.quitSafely();
        if (detectionThread != null) detectionThread.quitSafely();
        
        super.onDestroy();
        Log.i(TAG, "Service destroyed");
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
