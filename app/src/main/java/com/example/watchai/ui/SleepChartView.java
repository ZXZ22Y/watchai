package com.example.watchai.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import com.example.watchai.model.SleepRecord;

/** 睡眠分期横向堆叠条：深睡 / 浅睡 / REM / 清醒（圆角、渐变） */
public class SleepChartView extends View {

    private SleepRecord record;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SleepChartView(Context c) { super(c); }
    public SleepChartView(Context c, AttributeSet a) { super(c, a); }

    public void setRecord(SleepRecord r) { this.record = r; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (record == null || !record.hasData()) return;
        int w = getWidth(), h = getHeight();
        float y = h / 2f;
        float barH = h * 0.5f;
        int total = record.totalMinutes() + record.awakeMinutes;
        if (total <= 0) return;

        float x = 0;
        x = seg(c, x, w, y, barH, record.awakeMinutes * 1f / total,
                Color.rgb(255, 190, 70), Color.rgb(255, 150, 50));
        x = seg(c, x, w, y, barH, record.remMinutes * 1f / total,
                Color.rgb(190, 130, 255), Color.rgb(150, 90, 240));
        x = seg(c, x, w, y, barH, record.lightMinutes * 1f / total,
                Color.rgb(80, 190, 255), Color.rgb(50, 140, 240));
        x = seg(c, x, w, y, barH, record.deepMinutes * 1f / total,
                Color.rgb(70, 120, 245), Color.rgb(50, 80, 200));
    }

    private float seg(Canvas c, float x, float w, float y, float barH, float ratio, int c1, int c2) {
        if (ratio <= 0.001f) return x;
        float segW = w * ratio;
        RectF r = new RectF(x + 1.5f, y - barH / 2, x + segW - 1.5f, y + barH / 2);
        if (r.right <= r.left) return x + segW;
        paint.setShader(new LinearGradient(r.left, r.top, r.left, r.bottom, c1, c2, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, barH / 2, barH / 2, paint);
        paint.setShader(null);
        return x + segW;
    }
}
