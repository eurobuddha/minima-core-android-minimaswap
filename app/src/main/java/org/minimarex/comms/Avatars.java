package org.minimarex.comms;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Colour-initial avatars: a deterministic disc colour from the key + initials from the name/key. */
public final class Avatars {

    public static FrameLayout view(Context c, String publicId, String name, int sizeDp) {
        float density = c.getResources().getDisplayMetrics().density;
        int px = Math.round(sizeDp * density);
        FrameLayout f = new FrameLayout(c);
        f.setLayoutParams(new FrameLayout.LayoutParams(px, px));
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(colorFor(publicId));
        f.setBackground(d);
        TextView t = new TextView(c);
        t.setText(initials(name, publicId));
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(sizeDp * 0.38f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        f.addView(t, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return f;
    }

    public static int colorFor(String publicId) {
        int h = publicId == null ? 0 : publicId.hashCode();
        float hue = ((h % 360) + 360) % 360;
        return Color.HSVToColor(new float[]{hue, 0.55f, 0.72f});
    }

    public static String initials(String name, String publicId) {
        if (name != null && !name.trim().isEmpty()) {
            String[] parts = name.trim().split("\\s+");
            StringBuilder s = new StringBuilder(parts[0].substring(0, 1));
            if (parts.length > 1) s.append(parts[parts.length - 1].charAt(0));
            return s.toString().toUpperCase();
        }
        String h = publicId == null ? "" : (publicId.startsWith("0x") ? publicId.substring(2) : publicId);
        return h.length() >= 2 ? h.substring(0, 2).toUpperCase() : "•";
    }

    private Avatars() {}
}
