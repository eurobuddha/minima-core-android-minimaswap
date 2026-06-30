package org.minimarex.minimaswap;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import org.minimarex.minimaswap.swap.SwapDb;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;

/** A tiny dependency-free price chart: executed trade prices (y) over block/time (x), drawn on a Canvas. */
public class MarketChartView extends View {

    private List<SwapDb.MarketTrade> data = Collections.emptyList();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MarketChartView(Context c) {
        super(c);
        line.setColor(Design.ACCENT); line.setStrokeWidth(dp(2)); line.setStyle(Paint.Style.STROKE);
        dot.setColor(Design.ACCENT);
        axis.setColor(Design.DIM2); axis.setStrokeWidth(1);
        label.setColor(Design.DIM); label.setTextSize(sp(10));
    }

    public void setData(List<SwapDb.MarketTrade> d) {
        data = d == null ? Collections.<SwapDb.MarketTrade>emptyList() : d;
        invalidate();
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        int padL = dp(46), padR = dp(8), padT = dp(8), padB = dp(16);

        if (data.size() < 2) {
            label.setTextSize(sp(12));
            cv.drawText(data.isEmpty() ? "Collecting market data…" : "1 trade so far — need 2+ to chart",
                    padL, h / 2f, label);
            label.setTextSize(sp(10));
            return;
        }

        double minP = Double.MAX_VALUE, maxP = -Double.MAX_VALUE;
        long minB = Long.MAX_VALUE, maxB = Long.MIN_VALUE;
        for (SwapDb.MarketTrade t : data) {
            minP = Math.min(minP, t.price); maxP = Math.max(maxP, t.price);
            minB = Math.min(minB, t.createdBlock); maxB = Math.max(maxB, t.createdBlock);
        }
        if (maxP <= minP) maxP = minP + Math.max(minP * 0.001, 1e-9);
        if (maxB <= minB) maxB = minB + 1;

        float plotW = w - padL - padR, plotH = h - padT - padB;
        cv.drawLine(padL, padT, padL, h - padB, axis);          // y axis
        cv.drawLine(padL, h - padB, w - padR, h - padB, axis);  // x axis
        cv.drawText(fmt(maxP), dp(2), padT + sp(9), label);     // top price
        cv.drawText(fmt(minP), dp(2), h - padB, label);         // bottom price

        Path p = new Path();
        for (int i = 0; i < data.size(); i++) {
            float x = px(data.get(i).createdBlock, minB, maxB, padL, plotW);
            float y = py(data.get(i).price, minP, maxP, padT, plotH);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        cv.drawPath(p, line);
        for (SwapDb.MarketTrade t : data) {
            cv.drawCircle(px(t.createdBlock, minB, maxB, padL, plotW),
                    py(t.price, minP, maxP, padT, plotH), dp(2.5f), dot);
        }
    }

    private static float px(long b, long min, long max, int padL, float plotW) {
        return padL + (float) ((b - min) / (double) (max - min)) * plotW;
    }
    private static float py(double v, double min, double max, int padT, float plotH) {
        return padT + (float) (1 - (v - min) / (max - min)) * plotH;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private float sp(float v) { return v * getResources().getDisplayMetrics().scaledDensity; }

    private static String fmt(double v) {
        return BigDecimal.valueOf(v).setScale(v < 1 ? 5 : 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
