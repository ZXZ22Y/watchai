package com.example.watchai.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

/** 迷你趋势曲线（带渐变填充），用于近 7 天睡眠评分/步数 */
public class TrendView extends View {

    private List<Float> values;
    private int lineColor = Color.rgb(150, 110, 255);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TrendView(Context c) { super(c); init(); }
    public TrendView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(5);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        dot.setStyle(Paint.Style.FILL);
    }

    public void setData(List<Float> values, int color) {
        this.values = values;
        this.lineColor = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (values == null || values.size() < 2) return;
        int w = getWidth(), h = getHeight();
        float pad = 12;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : values) { min = Math.min(min, v); max = Math.max(max, v); }
        float span = Math.max(0.001f, max - min);
        int n = values.size();
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = pad + (w - 2 * pad) * i / (n - 1);
            ys[i] = h - pad - (h - 2 * pad) * (values.get(i) - min) / span;
        }

        Path p = new Path();
        p.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) p.lineTo(xs[i], ys[i]);

        Path fp = new Path(p);
        fp.lineTo(xs[n - 1], h);
        fp.lineTo(xs[0], h);
        fp.close();
        fill.setShader(new LinearGradient(0, 0, 0, h,
                Color.argb(70, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor)),
                Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawPath(fp, fill);
        line.setColor(lineColor);
        c.drawPath(p, line);

        dot.setColor(Color.WHITE);
        for (int i = 0; i < n; i++) c.drawCircle(xs[i], ys[i], i == n - 1 ? 8 : 4, dot);
        dot.setColor(lineColor);
        c.drawCircle(xs[n - 1], ys[n - 1], 5, dot);
    }
}
