package com.room.checker;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 101;
    private static final int REQ_IMAGE = 102;
    private EditText input;
    private TextView status;
    private TextRecognizer recognizer;
    private int pending = 0;
    private List<String> collected = new ArrayList<>();

    static class Item {
        int top;
        String s;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        int p = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("Room Checker");
        title.setTextSize(22);
        root.addView(title);

        input = new EditText(this);
        input.setHint("Registered naam yahan aayenge (har line mein 1 naam)");
        input.setMinLines(10);
        input.setGravity(Gravity.TOP | Gravity.START);
        SharedPreferences sp = getSharedPreferences("rc", MODE_PRIVATE);
        input.setText(sp.getString("list", ""));
        root.addView(input);

        status = new TextView(this);
        status.setPadding(0, p / 2, 0, p / 2);
        root.addView(status);

        Button img = new Button(this);
        img.setText("Screenshots se list banao (4-5 ek saath)");
        img.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, REQ_IMAGE);
        });
        root.addView(img);

        Button save = new Button(this);
        save.setText("List Save Karo");
        save.setOnClickListener(v -> saveList());
        root.addView(save);

        Button start = new Button(this);
        start.setText("Start Overlay");
        start.setOnClickListener(v -> startOverlay());
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(v -> stopService(new Intent(this, CheckerService.class)));
        root.addView(stop);

        Button clear = new Button(this);
        clear.setText("List Khali Karo");
        clear.setOnClickListener(v -> {
            input.setText("");
            saveList();
        });
        root.addView(clear);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
        updateStatus();
    }

    private int countNames() {
        int c = 0;
        for (String line : input.getText().toString().split("\n")) {
            if (!line.trim().isEmpty()) c++;
        }
        return c;
    }

    private void updateStatus() {
        status.setText("List mein players: " + countNames());
    }

    private void saveList() {
        getSharedPreferences("rc", MODE_PRIVATE).edit()
                .putString("list", input.getText().toString()).apply();
        updateStatus();
        Toast.makeText(this, "List save: " + countNames() + " players", Toast.LENGTH_SHORT).show();
    }

    private void readImages(List<Uri> uris) {
        pending = uris.size();
        collected = new ArrayList<>();
        Toast.makeText(this, uris.size() + " screenshot padh raha hoon...", Toast.LENGTH_SHORT).show();
        for (Uri u : uris) {
            try {
                InputImage image = InputImage.fromFilePath(this, u);
                final int h = image.getHeight();
                recognizer.process(image)
                        .addOnSuccessListener(text -> {
                            collectNames(text, h);
                            oneDone();
                        })
                        .addOnFailureListener(e -> oneDone());
            } catch (Exception e) {
                oneDone();
            }
        }
    }

    private void oneDone() {
        pending--;
        if (pending == 0) finishImport();
    }

    private String clean(String raw) {
        String s = raw.trim();
        String low = s.toLowerCase();
        if (low.matches(".*\\d{1,2}:\\d{2}.*")) return null;
        if (low.contains("match details") || low.contains("entry fee") || low.startsWith("join")) return null;
        s = s.replaceFirst("^\\d{1,2}\\s*[.)\\-:]\\s*", "");
        s = s.replaceFirst("^\\d{1,2}\\s+(?=[^\\d\\s])", "");
        s = s.trim();
        if (s.matches("^\\d{1,2}$")) return null;
        int cnt = 0;
        for (char ch : s.toCharArray()) {
            if (Character.isLetterOrDigit(ch)) cnt++;
        }
        if (cnt < 2) return null;
        return s;
    }

    private void collectNames(Text text, int h) {
        List<Item> items = new ArrayList<>();
        for (Text.TextBlock blk : text.getTextBlocks()) {
            for (Text.Line l : blk.getLines()) {
                Rect r = l.getBoundingBox();
                if (r == null) continue;
                if (r.top < h * 0.095 || r.bottom > h * 0.875) continue;
                String s = clean(l.getText());
                if (s == null) continue;
                Item it = new Item();
                it.top = r.top;
                it.s = s;
                items.add(it);
            }
        }
        Collections.sort(items, (x, y) -> Integer.compare(x.top, y.top));
        for (Item it : items) collected.add(it.s);
    }

    private void finishImport() {
        List<String> keys = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (String s : collected) {
            String k = CheckerService.norm(s);
            String key = k.length() > 0 ? k : "raw:" + s;
            if (keys.contains(key)) continue;
            keys.add(key);
            sb.append(s).append('\n');
        }
        input.setText(sb.toString());
        saveList();
    }

    private void startOverlay() {
        saveList();
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Permission do, phir wapas aake Start dabao", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK && data != null) {
            List<Uri> uris = new ArrayList<>();
            ClipData cd = data.getClipData();
            if (cd != null) {
                for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (!uris.isEmpty()) readImages(uris);
            return;
        }
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent s = new Intent(this, CheckerService.class);
            s.putExtra("code", resultCode);
            s.putExtra("data", data);
            startForegroundService(s);
            Toast.makeText(this, "Overlay chalu! Ab Free Fire kholo", Toast.LENGTH_LONG).show();
            moveTaskToBack(true);
        }
    }
}
