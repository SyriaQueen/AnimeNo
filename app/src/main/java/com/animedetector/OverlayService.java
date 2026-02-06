package com.animedetector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
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

import androidx.core.app.NotificationCompat;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class OverlayService extends Service {
    private static final String TAG = "OverlayService";
    private static final String CHANNEL_ID = "AnimeDetectorChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int FRAME_SKIP = 3; // معالجة كل 4 إطارات
    
    private static final AtomicBoolean isServiceRunning = new AtomicBoolean(false);
    
    // Media Projection
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    
    // Overlay View
    private WindowManager windowManager;
    private View overlayView;
    private ImageView overlayImageView;
    
    // Detection
    private OptimizedAnimeDetector detector;
    private DetectionSmoother smoother;
    
    // Threading
    private HandlerThread captureThread;
    private Handler captureHandler;
    private HandlerThread detectionThread;
    private Handler detectionHandler;
    
    // Display metrics
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    
    // Control
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private int frameCounter = 0;
    
    // Overlay bitmap
    private Bitmap overlayBitmap;
    private final Object overlayLock = new Object();
    
    public static boolean isRunning() {
        return isServiceRunning.get();
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        Log.i(TAG, "Service created");
        
        // تهيئة المكونات
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        
        // الحصول على أبعاد الشاشة
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;
        
        // تهيئة threads
        captureThread = new HandlerThread("CaptureThread");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        
        detectionThread = new HandlerThread("DetectionThread");
        detectionThread.start();
        detectionHandler = new Handler(detectionThread.getLooper());
        
        // تهيئة الكاشف
        try {
            detector = new OptimizedAnimeDetector(this);
            smoother = new DetectionSmoother(5);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize detector", e);
            stopSelf();
            return;
        }
        
        isServiceRunning.set(true);
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        
        // بدء foreground service
        createNotificationChannel();
        Notification notification = createNotification();
        startForeground(NOTIFICATION_ID, notification);
        
        // الحصول على نتيجة screen capture
        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("data");
        
        if (resultCode == 0 || data == null) {
            Log.e(TAG, "Invalid screen capture data");
            stopSelf();
            return START_NOT_STICKY;
        }
        
        // بدء screen capture
        startScreenCapture(resultCode, data);
        
        // إنشاء overlay view
        createOverlayView();
        
        return START_STICKY;
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Anime Detector",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("كاشف الأنمي يعمل");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        );
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Anime Detector")
            .setContentText("🎯 يكشف الأنمي على الشاشة")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }
    
    private void startScreenCapture(int resultCode, Intent data) {
        // إنشاء ImageReader
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888, 2
        );
        
        imageReader.setOnImageAvailableListener(reader -> {
            // Frame skipping
            if (++frameCounter % (FRAME_SKIP + 1) != 0) {
                Image image = reader.acquireLatestImage();
                if (image != null) image.close();
                return;
            }
            
            Image image = reader.acquireLatestImage();
            if (image != null) {
                processImage(image);
            }
        }, captureHandler);
        
        // إنشاء MediaProjection
        mediaProjection = projectionManager.getMediaProjection(resultCode, data);
        
        // إنشاء VirtualDisplay
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "AnimeDetector",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(),
            null, captureHandler
        );
        
        Log.i(TAG, "Screen capture started");
    }
    
    private void createOverlayView() {
        LayoutInflater inflater = LayoutInflater.from(this);
        overlayView = inflater.inflate(R.layout.overlay_layout, null);
        overlayImageView = overlayView.findViewById(R.id.overlayImage);
        
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }
        
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
        
        Log.i(TAG, "Overlay view created");
    }
    
    private void processImage(Image image) {
        if (!isProcessing.compareAndSet(false, true)) {
            image.close();
            return;
        }
        
        detectionHandler.post(() -> {
            try {
                // تحويل Image إلى Bitmap
                Bitmap bitmap = imageToBitmap(image);
                
                if (bitmap != null) {
                    // الكشف
                    OptimizedAnimeDetector.DetectionResult result = 
                        detector.detect(bitmap);
                    
                    // التنعيم
                    if (smoother != null) {
                        result = smoother.smooth(result);
                    }
                    
                    // تحديث overlay
                    updateOverlay(result, bitmap.getWidth(), bitmap.getHeight());
                    
                    bitmap.recycle();
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error processing image", e);
            } finally {
                image.close();
                isProcessing.set(false);
            }
        });
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
        
        // قص الـ padding إذا وُجد
        if (rowPadding != 0) {
            Bitmap croppedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, screenWidth, screenHeight
            );
            bitmap.recycle();
            return croppedBitmap;
        }
        
        return bitmap;
    }
    
    private void updateOverlay(OptimizedAnimeDetector.DetectionResult result, 
                               int width, int height) {
        synchronized (overlayLock) {
            // إنشاء bitmap للـ overlay
            if (overlayBitmap == null || 
                overlayBitmap.getWidth() != width || 
                overlayBitmap.getHeight() != height ||
                overlayBitmap.isRecycled()) {
                
                if (overlayBitmap != null && !overlayBitmap.isRecycled()) {
                    overlayBitmap.recycle();
                }
                
                overlayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            }
            
            Canvas canvas = new Canvas(overlayBitmap);
            canvas.drawColor(Color.TRANSPARENT);
            
            if (!result.detections.isEmpty()) {
                Paint paint = new Paint();
                paint.setColor(Color.argb(220, 0, 0, 0));
                paint.setStyle(Paint.Style.FILL);
                
                for (OptimizedAnimeDetector.Detection det : result.detections) {
                    float margin = Math.min(det.width, det.height) * 0.05f;
                    canvas.drawRect(
                        det.x1 - margin, det.y1 - margin,
                        det.x2 + margin, det.y2 + margin,
                        paint
                    );
                }
            }
            
            // تحديث UI
            new Handler(getMainLooper()).post(() -> {
                if (overlayImageView != null) {
                    overlayImageView.setImageBitmap(overlayBitmap);
                }
            });
        }
    }
    
    @Override
    public void onDestroy() {
        isServiceRunning.set(false);
        
        // إيقاف screen capture
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
        
        if (mediaProjection != null) {
            mediaProjection.stop();
        }
        
        if (imageReader != null) {
            imageReader.close();
        }
        
        // إزالة overlay
        if (overlayView != null && windowManager != null) {
            windowManager.removeView(overlayView);
        }
        
        // تنظيف resources
        synchronized (overlayLock) {
            if (overlayBitmap != null && !overlayBitmap.isRecycled()) {
                overlayBitmap.recycle();
            }
        }
        
        if (detector != null) {
            detector.close();
        }
        
        if (smoother != null) {
            smoother.clear();
        }
        
        // إيقاف threads
        if (captureThread != null) {
            captureThread.quitSafely();
        }
        
        if (detectionThread != null) {
            detectionThread.quitSafely();
        }
        
        super.onDestroy();
        
        Log.i(TAG, "Service destroyed");
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}


