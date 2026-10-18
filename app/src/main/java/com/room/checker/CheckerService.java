package com.room.checker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

import java.util.List;

class ResultView extends View {
    final List<Rect> boxes;
    final List<Integer> cls;
    final List<String> labels;
    final String summary;
    final Paint fill = new Paint();
    final Paint stroke = new Paint();
    final Paint txt = new Paint();
    final Paint small = new Paint();
    final Paint bg = new Paint();

    ResultView(Context c, List<Rect> b, List<Integer> k, List<String> lb, String s) {
        super(c);
        boxes = b;
        cls = k;
        labels = lb;
        summary = s;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(5f);
        txt.setColor(Color.WHITE);
        txt.setTextSize(42f);
        txt.setFakeBoldText(true);
        small.setColor(Color.WHITE);
        small.setTextSize(30f);
        bg.setColor(0xCC000000);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        for (int i = 0; i < boxes.size(); i++) {
            int c = cls.get(i);
            Rect box = boxes.get(i);
            int col = c == 0 ? 0xFF00C853 : (c == 1 ? 0xFFFFC107 : 0xFFFF1744);
            fill.setColor(col);
            fill.setAlpha(c == 2 ? 90 : 35);
            stroke.setColor(col);
            canvas.drawRect(box, fill);
            canvas.drawRect(box, stroke);
            if (c != 0) {
                String lab = labels.get(i);
                float tw = small.measureText(lab);
                float x = box.left;
                float y = Math.max(box.top - 8, 110);
                canvas.drawRect(x, y - 30, x + tw + 10, y + 8, bg);
                canvas.drawText(lab, x + 5, y, small);
            }
        }
        canvas.drawRect(0, 0, txt.measureText(summary) + 40, 70, bg);
        canvas.drawText(summary, 20, 52, txt);
    }
}
