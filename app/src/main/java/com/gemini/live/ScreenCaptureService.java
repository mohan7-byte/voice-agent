package com.gemini.live;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {
    public static ScreenCaptureService instance = null;
    private MediaProjection mediaProjection;
    private int resultCode = 0;
    private Intent resultData = null;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startForegroundNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNotification();
        if (intent != null && intent.hasExtra("resultCode") && intent.hasExtra("data")) {
            this.resultCode = intent.getIntExtra("resultCode", 0);
            this.resultData = intent.getParcelableExtra("data");
            initProjection();
        }
        return START_STICKY;
    }

    private void initProjection() {
        try {
            if (mediaProjection == null && resultData != null) {
                MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                if (mpm != null) mediaProjection = mpm.getMediaProjection(resultCode, resultData);
            }
        } catch (Exception ignored) {}
    }

    public String captureScreenBase64() {
        if (mediaProjection == null) {
            initProjection();
            if (mediaProjection == null) return null;
        }

        VirtualDisplay virtualDisplay = null;
        ImageReader imageReader = null;
        Image image = null;
        Bitmap bitmap = null;

        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            if (wm != null) wm.getDefaultDisplay().getRealMetrics(dm);
            else dm = getResources().getDisplayMetrics();

            int width = dm.widthPixels;
            int height = dm.heightPixels;

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "VoiceShot", width, height, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null
            );

            for (int i = 0; i < 6; i++) {
                SystemClock.sleep(50);
                image = imageReader.acquireLatestImage();
                if (image != null) break;
            }

            if (image == null) return null;

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * width;

            bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);

            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            cropped.compress(Bitmap.CompressFormat.JPEG, 75, baos);
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

        } catch (Exception e) {
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
            if (image != null) image.close();
            if (virtualDisplay != null) virtualDisplay.release();
            if (imageReader != null) imageReader.close();
        }
    }

    private void startForegroundNotification() {
        String channelId = "voice_screen_capture";
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel chan = new NotificationChannel(channelId, "Voice Screen Capture", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(chan);
        }

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
            ? new Notification.Builder(this, channelId) 
            : new Notification.Builder(this);

        Notification notif = b.setContentTitle("Voice Vision")
            .setContentText("Screen engine ready")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1001, notif);
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (mediaProjection != null) mediaProjection.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
