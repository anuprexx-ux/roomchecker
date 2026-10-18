package com.room.checker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;

public class CheckerService extends Service {

    private WindowManager wm;
    private TextView fab;
    private WindowManager.LayoutParams fabParams;
    private ResultView resultView;
    private MediaProjection projection;
    private TextRecognizer recognizer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean busy = false;

    static String norm(String s) {
        return NameMatch.norm(s);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("rc", "Room Checker", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "rc")
                .setContentTitle("Room Checker chal raha hai")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (fab != null) return START_NOT_STICKY;

        int code = intent.getIntExtra("code", 0);
        Intent data = intent.getParcelableExtra("data");
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                handler.post(() -> stopSelf());
            }
        }, handler);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        makeButton();
        return START_NOT_STICKY;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void makeButton() {
        fab = new TextView(this);
        fab.setText("CHECK");
        fab.setTextColor(Color.WHITE);
        fab.setTextSize(11);
        fab.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xDD1565C0);
        fab.setBackground(bg);

        int size = dp(60);
        fabParams = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        fabParams.gravity = Gravity.TOP | Gravity.START;
        fabParams.x = dp(8);
        fabParams.y = dp(100);

        fab.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY;
            float touchX, touchY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = fabParams.x;
                        startY = fabParams.y;
                        touchX = e.getRawX();
                        touchY = e.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - touchX;
                        float dy = e.getRawY() - touchY;
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true;
                        if (moved) {
                            fabParams.x = startX + (int) dx;
                            fabParams.y = startY + (int) dy;
                            wm.updateViewLayout(fab, fabParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) onTap();
                        return true;
                }
                return false;
            }
        });
        wm.addView(fab, fabParams);
    }

    private void onTap() {
        if (resultView != null) {
            clearResult();
            return;
        }
        if (busy) return;
        busy = true;
        fab.setText("...");
        fab.setVisibility(View.INVISIBLE);
        handler.postDelayed(this::grab, 250);
    }

    private void clearResult() {
        if (resultView != null) {
            try {
                wm.removeView(resultView);
            } catch (Exception ignored) {
            }
            resultView = null;
        }
        fab.setText("CHECK");
    }

    private void finishScan(String msg) {
        busy = false;
        if (fab != null) {
            fab.setVisibility(View.VISIBLE);
            if (resultView == null) fab.setText("CHECK");
        }
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void grab() {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            final int w = dm.widthPixels;
            final int h = dm.heightPixels;
            final ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            final VirtualDisplay vd = projection.createVirtualDisplay("rc", w, h, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
            final boolean[] done = {false};
            reader.setOnImageAvailableListener(r -> {
                if (done[0]) return;
                Image img = r.acquireLatestImage();
                if (img == null) return;
                done[0] = true;
                Bitmap bmp = null;
                try {
                    bmp = toBitmap(img, w, h);
                } catch (Exception ignored) {
                }
                img.close();
                vd.release();
                r.close();
                fab.setVisibility(View.VISIBLE);
                if (bmp == null) {
                    finishScan("Screen capture fail");
                    return;
                }
                analyze(bmp, w, h);
            }, handler);
            handler.postDelayed(() -> {
                if (done[0]) return;
                done[0] = true;
                vd.release();
                reader.close();
                finishScan("Screen capture nahi hua, dobara try karo");
            }, 4000);
        } catch (Exception ex) {
            finishScan("Capture error");
        }
    }

    private Bitmap toBitmap(Image img, int w, int h) {
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer buf = p.getBuffer();
        int pixelStride = p.getPixelStride();
        int rowStride = p.getRowStride();
        int rowPadding = rowStride - pixelStride * w;
        Bitmap b = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888);
        b.copyPixelsFromBuffer(buf);
        return Bitmap.createBitmap(b, 0, 0, w, h);
    }

    private void analyze(Bitmap bmp, int w, int h) {
        try {
            final int rx = (int) (w * 0.46f);
            final int ry = (int) (h * 0.17f);
            final int rw = (int) (w * 0.50f);
            final int rh = (int) (h * 0.53f);
            final float scale = 2f;
            Bitmap crop = Bitmap.createBitmap(bmp, rx, ry, rw, rh);
            Bitmap big = Bitmap.createScaledBitmap(crop, (int) (rw * scale), (int) (rh * scale), true);
            recognizer.process(InputImage.fromBitmap(big, 0))
                    .addOnSuccessListener(text -> {
                        showResult(text, w, h, rx, ry, scale);
                        busy = false;
                    })
                    .addOnFailureListener(e -> finishScan("Text padh nahi paya"));
        } catch (Exception ex) {
            finishScan("Analyze error");
        }
    }

    private void showResult(Text text, int w, int h, int rx, int ry, float scale) {
        String listText = getSharedPreferences("rc", MODE_PRIVATE).getString("list", "");
        NameMatch.Result res = NameMatch.build(text, listText, rx, ry, scale);

        clearResult();
        resultView = new ResultView(this, res.boxes, res.cls, res.labels, res.summary);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        wm.addView(resultView, lp);

        fab.setText("CLEAR");
        wm.removeView(fab);
        wm.addView(fab, fabParams);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (resultView != null) wm.removeView(resultView);
        } catch (Exception ignored) {
        }
        try {
            if (fab != null) wm.removeView(fab);
        } catch (Exception ignored) {
        }
        if (projection != null) projection.stop();
        if (recognizer != null) recognizer.close();
    }
}
