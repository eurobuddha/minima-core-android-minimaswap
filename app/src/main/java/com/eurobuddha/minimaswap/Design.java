package com.eurobuddha.minimaswap;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

/**
 * Design-token engine with a runtime light/dark toggle (persisted; applied by re-rendering, no recreate()).
 *
 *  - ONYX     — refined dark fintech: cool blue-black grounds, hairline-bordered cards, restrained Minima
 *               orange, mono tabular numerals. The default.
 *  - DAYLIGHT — clean light: cool paper, near-black ink, soft elevation, same orange accent.
 *
 * Every view reads colours/typefaces from here, so one toggle restyles the whole app. Numbers are set in
 * JetBrains Mono (tabular); text in Inter. Values are 1:1 with the approved mock-ups.
 */
public final class Design {

    public enum Mode { ONYX, DAYLIGHT }

    private static final String PREFS = "minimaswap_design";   // SEPARATE from the fund prefs ("minimaswap")
    private static final String KEY = "theme";

    private static Mode mode = Mode.ONYX;      // Onyx (dark) is the default
    private static Typeface sSans, sMono;      // Inter / JetBrains Mono (bundled variable TTFs)

    private Design() {}

    // ---- lifecycle ----

    /** Load persisted mode + bundled fonts. Call once as the first line of onCreate. */
    public static void load(Context c) {
        String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, Mode.ONYX.name());
        try { mode = Mode.valueOf(s); } catch (Exception e) { mode = Mode.ONYX; }
        if (sSans == null) { try { sSans = ResourcesCompat.getFont(c, R.font.inter); } catch (Exception ignore) {} }
        if (sMono == null) { try { sMono = ResourcesCompat.getFont(c, R.font.jetbrains_mono); } catch (Exception ignore) {} }
    }

    public static void set(Context c, Mode m) {
        mode = m;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, m.name()).apply();
    }

    /** Flip Onyx ↔ Daylight and persist. */
    public static void toggle(Context c) { set(c, mode == Mode.ONYX ? Mode.DAYLIGHT : Mode.ONYX); }

    public static Mode mode()   { return mode; }
    public static boolean isDark() { return mode == Mode.ONYX; }
    public static String label()   { return isDark() ? "Dark" : "Light"; }

    private static int pick(int onyx, int daylight) { return mode == Mode.ONYX ? onyx : daylight; }

    // ---- semantic colours (ARGB ints) — method form so the toggle restyles everything ----
    public static int BG()          { return pick(0xFF0B0D12, 0xFFF3F4F7); }   // app ground
    public static int SURFACE()     { return pick(0xFF12151C, 0xFFFFFFFF); }   // card
    public static int SURFACE2()    { return pick(0xFF1B1F28, 0xFFEDEEF2); }   // elevated / input / inactive pill
    public static int BORDER()      { return pick(0x12FFFFFF, 0xFFE6E8EE); }   // hairline stroke
    public static int TEXT()        { return pick(0xFFEEF0F4, 0xFF181B22); }   // primary ink
    public static int DIM()         { return pick(0xFF8B909C, 0xFF8B909C); }   // secondary
    public static int DIM2()        { return pick(0xFF6F7583, 0xFFA6ABB6); }   // tertiary / axes
    public static int ACCENT()      { return 0xFFF7931A; }                     // Minima orange (both modes)
    public static int ACCENT_SOFT() { return pick(0x33F7931A, 0x1FF7931A); }   // active-nav pill tint
    public static int GRAD_START()  { return 0xFFFFAB3D; }                     // CTA gradient top
    public static int ON_ACCENT()   { return pick(0xFF0B0D12, 0xFF201400); }   // dark ink on orange (both)
    public static int IN()          { return pick(0xFF3FD0A2, 0xFF12A97E); }   // received / bid / positive
    public static int POSITIVE()    { return IN(); }
    public static int OUT()         { return ACCENT(); }                       // sent
    public static int RED()         { return pick(0xFFFF5C5C, 0xFFE5484D); }   // error / ask / refund

    // ---- typography ----
    public static Typeface sans()     { return sSans != null ? sSans : Typeface.SANS_SERIF; }
    public static Typeface sansBold() { return Typeface.create(sans(), Typeface.BOLD); }
    public static Typeface mono()     { return sMono != null ? sMono : Typeface.MONOSPACE; }
    public static Typeface monoBold() { return Typeface.create(mono(), Typeface.BOLD); }

    // ---- dialog theme so AlertDialog chrome follows the in-app toggle ----
    public static int dialogTheme() { return isDark() ? R.style.SwapDialogDark : R.style.SwapDialogLight; }

    // ---- metrics ----
    public static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    // ---- drawables / view helpers ----

    /** A rounded chip. Text uses Inter. */
    public static TextView pill(Context c, String text, int bg, int fg) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(fg);
        t.setTextSize(11f);
        t.setTypeface(sans());
        t.setGravity(Gravity.CENTER);
        int h = dp(c, 6), w = dp(c, 10);
        t.setPadding(w, h, w, h);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(c, 14));
        t.setBackground(d);
        return t;
    }

    /** A rounded filled background (no border). */
    public static GradientDrawable roundBg(Context c, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    /** A card surface: filled SURFACE with a 1px hairline BORDER stroke. */
    public static GradientDrawable card(Context c, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(SURFACE());
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(Math.max(1, dp(c, 1)), BORDER());
        return d;
    }

    /** A stroked rounded rect over an arbitrary fill (rows/quotes). */
    public static GradientDrawable stroked(Context c, int fill, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(Math.max(1, dp(c, 1)), BORDER());
        return d;
    }

    /** Primary CTA background: vertical orange gradient. */
    public static GradientDrawable gradientCta(Context c) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{GRAD_START(), ACCENT()});
        d.setCornerRadius(dp(c, 14));
        return d;
    }

    /** Give a view a soft press animation (scale down on touch). Tasteful micro-motion. */
    public static void pressable(final View v) {
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start(); break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start(); break;
            }
            return false;   // never consume — the click listener still fires
        });
    }

    /** Wrap a drawable in a ripple keyed on the soft-accent colour (for the CTA). */
    public static RippleDrawable ripple(GradientDrawable base) {
        return new RippleDrawable(ColorStateList.valueOf(ACCENT_SOFT()), base, null);
    }

    /** A prominent "value just changed" pulse: a bounce (plays 3×) plus, for TextViews, a colour flash that
     *  reverts. Runs on the UI thread; no-op if the view is null. */
    public static void pulse(final View v, final int flashColor) {
        if (v == null) return;
        v.post(() -> {
            v.setPivotX(v.getWidth() / 2f);
            v.setPivotY(v.getHeight() / 2f);
            ObjectAnimator sx = ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.22f, 1f);
            ObjectAnimator sy = ObjectAnimator.ofFloat(v, "scaleY", 1f, 1.22f, 1f);
            sx.setRepeatCount(2); sy.setRepeatCount(2);
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
                flash.setRepeatCount(5);
                flash.addUpdateListener(a -> tv.setTextColor((int) a.getAnimatedValue()));
                flash.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) { tv.setTextColor(original); }
                });
                flash.start();
            }
        });
    }
}
