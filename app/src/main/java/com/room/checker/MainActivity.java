package com.room.checker;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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

public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 101;
    private EditText input;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int p = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("Room Checker");
        title.setTextSize(22);
        root.addView(title);

        input = new EditText(this);
        input.setHint("Registered naam yahan paste karo (har line mein 1 naam)");
        input.setMinLines(10);
        input.setGravity(Gravity.TOP | Gravity.START);
        SharedPreferences sp = getSharedPreferences("rc", MODE_PRIVATE);
        input.setText(sp.getString("list", ""));
        root.addView(input);

        status = new TextView(this);
        status.setPadding(0, p / 2, 0, p / 2);
        root.addView(status);

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
