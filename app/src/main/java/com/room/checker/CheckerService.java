package com.room.checker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

public class CheckerService extends Service {

    private WindowManager wm;
    private TextView fab;
    private WindowManager.LayoutParams fabParams;
    private ResultView resultView;
    private MediaProjection projection;
    private TextRecognizer recognizer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean busy = false;

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
        recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(text -> {
                    showResult(text, w, h);
                    busy = false;
                })
                .addOnFailureListener(e -> finishScan("Text padh nahi paya"));
    }

    private void showResult(Text text, int w, int h) {
        SharedPreferences sp = getSharedPreferences("rc", MODE_PRIVATE);
        List<String> reg = new ArrayList<>();
        for (String line : sp.getString("list", "").split("\n")) {
            String name = line.replaceFirst("^\\s*\\d+\\s*[.)\\-:]\\s*", "");
            String n = norm(name);
            if (n.length() >= 2) reg.add(n);
        }

        List<Rect> boxes = new ArrayList<>();
        List<Integer> cls = new ArrayList<>();
        List<Integer> best = new ArrayList<>();

        for (Text.TextBlock b : text.getTextBlocks()) {
            for (Text.Line l : b.getLines()) {
                Rect box = l.getBoundingBox();
                if (box == null) continue;
                String raw = l.getText().replace("...", "").replace("\u2026", "");
                String n = norm(raw);
                if (n.length() < 3) continue;
                double top = 0;
                int idx = -1;
                for (int i = 0; i < reg.size(); i++) {
                    double s = sim(n, reg.get(i));
                    if (s > top) {
                        top = s;
                        idx = i;
                    }
                }
                int c = top >= 0.72 ? 0 : (top >= 0.55 ? 1 : 2);
                boxes.add(box);
                cls.add(c);
                best.add(c == 0 ? idx : -1);
            }
        }

        for (int i = 0; i < best.size(); i++) {
            if (best.get(i) < 0) continue;
            for (int j = i + 1; j < best.size(); j++) {
                if (best.get(j).equals(best.get(i))) {
                    cls.set(i, 1);
                    cls.set(j, 1);
                }
            }
        }

        int g = 0, y = 0, r = 0;
        for (int c : cls) {
            if (c == 0) g++;
            else if (c == 1) y++;
            else r++;
        }
        String summary = "Unregistered: " + r + "   Check: " + y + "   OK: " + g;

        clearResult();
        resultView = new ResultView(this, boxes, cls, summary);
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

    static String norm(String s) {
        s = s.replaceAll("\\[[^\\]]*\\]", "");
        s = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '0': ch = 'o'; break;
                case '1': case 'i': case '\u0456': ch = 'l'; break;
                case '5': ch = 's'; break;
                case '8': ch = 'b'; break;
                case '2': ch = 'z'; break;
                case '6': case '9': ch = 'g'; break;
                case '4': case '\u0430': ch = 'a'; break;
                case '3': case '\u0435': ch = 'e'; break;
                case '7': ch = 't'; break;
                case '\u043e': ch = 'o'; break;
                case '\u0440': ch = 'p'; break;
                case '\u0441': ch = 'c'; break;
                case '\u0445': ch = 'x'; break;
                case '\u0443': ch = 'y'; break;
            }
            if (ch >= 'a' && ch <= 'z') sb.append(ch);
        }
        return sb.toString();
    }

    static double sim(String a, String b) {
        int la = a.length(), lb = b.length();
        int mn = Math.min(la, lb);
        if (mn >= 3 && (a.contains(b) || b.contains(a))) return 1.0;
        int[] prev = new int[lb + 1];
        int[] cur = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            cur[0] = i;
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return 1.0 - (double) prev[lb] / Math.max(la, lb);
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

    static class ResultView extends View {
        final List<Rect> boxes;
        final List<Integer> cls;
        final String summary;
        final Paint fill = new Paint();
        final Paint stroke = new Paint();
        final Paint txt = new Paint();
        final Paint bg = new Paint();

        ResultView(Context c, List<Rect> b, List<Integer> k, String s) {
            super(c);
            boxes = b;
            cls = k;
            summary = s;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(5f);
            txt.setColor(Color.WHITE);
            txt.setTextSize(42f);
            txt.setFakeBoldText(true);
            bg.setColor(0xCC000000);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            for (int i = 0; i < boxes.size(); i++) {
                int c = cls.get(i);
                int col = c == 0 ? 0xFF00C853 : (c == 1 ? 0xFFFFC107 : 0xFFFF1744);
                fill.setColor(col);
                fill.setAlpha(c == 2 ? 90 : 35);
                stroke.setColor(col);
                canvas.drawRect(boxes.get(i), fill);
                canvas.drawRect(boxes.get(i), stroke);
            }
            canvas.drawRect(0, 0, txt.measureText(summary) + 40, 70, bg);
            canvas.drawText(summary, 20, 52, txt);
        }
    }
}
