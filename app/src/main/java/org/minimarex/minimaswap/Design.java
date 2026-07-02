package org.minimarex.minimaswap;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

/** Dark + orange palette and a few view helpers, matching the minimaCore app family. */
public final class Design {

    public static final int BG       = 0xFF0A0A0F;
    public static final int SURFACE  = 0xFF15151F;
    public static final int SURFACE2 = 0xFF1F1F2B;
    public static final int ACCENT   = 0xFFF7931A;   // Minima orange
    public static final int ON_ACCENT = 0xFF000000;
    public static final int TEXT     = 0xFFFFFFFF;
    public static final int DIM      = 0xFF9A9AA8;
    public static final int DIM2     = 0xFF6A6A78;
    public static final int IN       = 0xFF2ECC71;   // received (green)
    public static final int OUT      = 0xFFF7931A;   // sent (orange)
    public static final int RED      = 0xFFE74C3C;

    public static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** A rounded chip-style TextView. */
    public static TextView pill(Context c, String text, int bg, int fg) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(fg);
        t.setTextSize(11f);
        t.setGravity(Gravity.CENTER);
        int h = dp(c, 6), w = dp(c, 10);
        t.setPadding(w, h, w, h);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(c, 14));
        t.setBackground(d);
        return t;
    }

    /** A rounded filled background drawable (for cards / inputs). */
    public static GradientDrawable roundBg(Context c, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    /** A prominent "value just changed" pulse: a bounce (scale, plays 3×) plus, for TextViews, a colour
     *  flash that reverts to the original. Runs on the UI thread; no-op if the view is null. Posted so it
     *  runs after layout (needs a real width/height to pivot on the centre). */
    public static void pulse(final View v, final int flashColor) {
        if (v == null) return;
        v.post(() -> {
            v.setPivotX(v.getWidth() / 2f);
            v.setPivotY(v.getHeight() / 2f);
            ObjectAnimator sx = ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.22f, 1f);
            ObjectAnimator sy = ObjectAnimator.ofFloat(v, "scaleY", 1f, 1.22f, 1f);
            sx.setRepeatCount(2); sy.setRepeatCount(2);   // 1 + 2 repeats = 3 bounces
            AnimatorSet set = new AnimatorSet();
            set.playTogether(sx, sy);
            set.setDuration(420);
            set.setInterpolator(new OvershootInterpolator());
            set.start();

            if (v instanceof TextView) {
                final TextView tv = (TextView) v;
                final int original = tv.getCurrentTextColor();
                ValueAnimator flash = ValueAnimator.ofArgb(original, flashColor);
                flash.setDuration(220);
                flash.setRepeatMode(ValueAnimator.REVERSE);
                flash.setRepeatCount(5);                  // ~3 flashes
                flash.addUpdateListener(a -> tv.setTextColor((int) a.getAnimatedValue()));
                flash.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) { tv.setTextColor(original); }
                });
                flash.start();
            }
        });
    }

    private Design() {}
}
