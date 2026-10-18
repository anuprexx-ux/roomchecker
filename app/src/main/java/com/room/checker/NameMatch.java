package com.room.checker;

import android.graphics.Rect;

import com.google.mlkit.vision.text.Text;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

public class NameMatch {

    static class Result {
        List<Rect> boxes = new ArrayList<>();
        List<Integer> cls = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        String summary = "";
    }

    static boolean hasLetter(String s) {
        for (char ch : s.toCharArray()) {
            if (Character.isLetter(ch)) return true;
        }
        return false;
    }

    static boolean hasDigit(String s) {
        for (char ch : s.toCharArray()) {
            if (Character.isDigit(ch)) return true;
        }
        return false;
    }

    static Result build(Text text, String listText, int rx, int ry, float scale) {
        List<String> reg = new ArrayList<>();
        for (String line : listText.split("\n")) {
            String name = line.replaceFirst("^\\s*\\d+\\s*[.)\\-:]\\s*", "");
            String n = norm(name);
            if (n.length() >= 2) reg.add(n);
        }

        Result res = new Result();
        List<Integer> best = new ArrayList<>();

        for (Text.TextBlock b : text.getTextBlocks()) {
            for (Text.Line l : b.getLines()) {
                Rect b0 = l.getBoundingBox();
                if (b0 == null) continue;
                Rect box = new Rect(
                        rx + (int) (b0.left / scale),
                        ry + (int) (b0.top / scale),
                        rx + (int) (b0.right / scale),
                        ry + (int) (b0.bottom / scale));
                String raw = l.getText().replace("...", "").replace("\u2026", "").trim();
                if (!hasLetter(raw) && hasDigit(raw)) continue;
                String n = norm(raw);
                if (n.length() < 3) {
                    if (n.length() == 0 && raw.length() >= 2 && !hasDigit(raw)) {
                        res.boxes.add(box);
                        res.cls.add(1);
                        res.labels.add(raw);
                        best.add(-1);
                    }
                    continue;
                }
                double top = 0;
                int idx = -1;
                for (int i = 0; i < reg.size(); i++) {
                    double s = sim(n, reg.get(i));
                    if (s > top) {
                        top = s;
                        idx = i;
                    }
                }
                int c = top >= 0.72 ? 0 : (top >= 0.40 ? 1 : 2);
                res.boxes.add(box);
                res.cls.add(c);
                res.labels.add(raw);
                best.add(c == 0 ? idx : -1);
            }
        }

        for (int i = 0; i < best.size(); i++) {
            if (best.get(i) < 0) continue;
            for (int j = i + 1; j < best.size(); j++) {
                if (best.get(j).equals(best.get(i))) {
                    res.cls.set(i, 1);
                    res.cls.set(j, 1);
                }
            }
        }

        int g = 0, y = 0, r = 0;
        for (int c : res.cls) {
            if (c == 0) g++;
            else if (c == 1) y++;
            else r++;
        }
        res.summary = "Unreg: " + r + "   Check: " + y + "   OK: " + g + "   Total: " + res.cls.size();
        return res;
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
        int mx = Math.max(la, lb);
        if (a.equals(b)) return 1.0;
        if (mn >= 4 && (a.contains(b) || b.contains(a))) {
            return mn * 2 >= mx ? 1.0 : 0.7;
        }
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
        return 1.0 - (double) prev[lb] / mx;
    }
}
