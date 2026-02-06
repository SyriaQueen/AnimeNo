package com.animedetector;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final int SCREEN_CAPTURE_REQUEST_CODE = 100;
    
    private Button btnStart;
    private Button btnStop;
    private TextView tvStatus;
    
    private ActivityResultLauncher<Intent> screenCaptureLauncher;
    private ActivityResultLauncher<Intent> overlayPermissionLauncher;
    
    private boolean hasOverlayPermission = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        tvStatus = findViewById(R.id.tvStatus);
        
        // تهيئة launchers
        initLaunchers();
        
        // أزرار التحكم
        btnStart.setOnClickListener(v -> checkPermissionsAndStart());
        btnStop.setOnClickListener(v -> stopService());
        
        updateUI();
    }
    
    private void initLaunchers() {
        // Screen capture permission
        screenCaptureLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Intent data = result.getData();
                    startOverlayService(data);
                } else {
                    Toast.makeText(this, "يجب منح صلاحية التقاط الشاشة", 
                        Toast.LENGTH_LONG).show();
                }
            }
        );
        
        // Overlay permission
        overlayPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (Settings.canDrawOverlays(this)) {
                    hasOverlayPermission = true;
                    requestScreenCapture();
                } else {
                    Toast.makeText(this, "يجب منح صلاحية العرض فوق التطبيقات", 
                        Toast.LENGTH_LONG).show();
                }
            }
        );
    }
    
    private void checkPermissionsAndStart() {
        // فحص صلاحية Overlay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission();
                return;
            }
        }
        
        hasOverlayPermission = true;
        requestScreenCapture();
    }
    
    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            overlayPermissionLauncher.launch(intent);
        }
    }
    
    private void requestScreenCapture() {
        MediaProjectionManager projectionManager = 
            (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        screenCaptureLauncher.launch(captureIntent);
    }
    
    private void startOverlayService(Intent data) {
        Intent serviceIntent = new Intent(this, OverlayService.class);
        serviceIntent.putExtra("resultCode", Activity.RESULT_OK);
        serviceIntent.putExtra("data", data);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        
        updateUI();
        Toast.makeText(this, "✅ بدأ كشف الأنمي على الشاشة", Toast.LENGTH_SHORT).show();
    }
    
    private void stopService() {
        Intent serviceIntent = new Intent(this, OverlayService.class);
        stopService(serviceIntent);
        updateUI();
        Toast.makeText(this, "⏸️ تم إيقاف الكشف", Toast.LENGTH_SHORT).show();
    }
    
    private void updateUI() {
        boolean isRunning = OverlayService.isRunning();
        btnStart.setEnabled(!isRunning);
        btnStop.setEnabled(isRunning);
        tvStatus.setText(isRunning ? "🟢 يعمل - يكشف الأنمي" : "⚪ متوقف");
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }
}


