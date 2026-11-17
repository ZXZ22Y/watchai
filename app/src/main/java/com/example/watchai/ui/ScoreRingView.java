/*
 * ============================================================
 * 腕上智析 WatchAI  —  原创作品，请勿去除本声明
 * 原作者：酷安 @想吃薯片zz
 * 开源地址：https://github.com/ZXZ22Y/watchai
 * 本文件为原作者编写，二次修改/分发请保留出处与署名。
 * 溯源标识：WATCHAI-ORIGIN-ByCoolapk-XiangChiShuaiPianZZ-2026
 * ============================================================
 */
package com.example.watchai.ui;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** 睡眠评分环：渐变 + 发光 + 中心大数字 */
public class ScoreRingView extends View {

    private int score = 0;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grade = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ScoreRingView(Context c) { super(c); init(); }
    public ScoreRingView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        bg.setStyle(Paint.Style.STROKE);
        bg.setColor(Color.argb(30, 255, 255, 255));
        fg.setStyle(Paint.Style.STROKE);
        fg.setStrokeCap(Paint.Cap.ROUND);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeCap(Paint.Cap.ROUND);
        num.setColor(Color.WHITE);
        num.setTextAlign(Paint.Align.CENTER);
        num.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setColor(Color.argb(170, 255, 255, 255));
        label.setTextAlign(Paint.Align.CENTER);
        grade.setTextAlign(Paint.Align.CENTER);
        grade.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    }

    public void setScore(int s, String level) {
        this.score = Math.max(0, Math.min(100, s));
        this.level = level;
        invalidate();
    }
    private String level = "";

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float radius = Math.min(w, h) / 2f - 16f;
        float stroke = radius * 0.11f;

        int c1, c2;
        if (score >= 90)      { c1 = Color.rgb(100, 240, 180); c2 = Color.rgb(30, 200, 140); }
        else if (score >= 80) { c1 = Color.rgb(120, 220, 255); c2 = Color.rgb(70, 140, 255); }
        else if (score >= 70) { c1 = Color.rgb(255, 215, 100); c2 = Color.rgb(255, 160, 60); }
        else                  { c1 = Color.rgb(255, 120, 130); c2 = Color.rgb(255, 70, 90); }

        Shader sh = new LinearGradient(cx - radius, cy - radius, cx + radius, cy + radius, c1, c2, Shader.TileMode.CLAMP);
        RectF rect = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);

        bg.setStrokeWidth(stroke);
        c.drawArc(rect, -90, 360, false, bg);

        if (score > 0) {
            halo.setStrokeWidth(stroke);
            halo.setShader(sh);
            halo.setAlpha(70);
            halo.setMaskFilter(new BlurMaskFilter(stroke * 0.7f, BlurMaskFilter.Blur.NORMAL));
            c.drawArc(rect, -90, 360f * score / 100f, false, halo);
            halo.setMaskFilter(null);

            fg.setStrokeWidth(stroke);
            fg.setShader(sh);
            c.drawArc(rect, -90, 360f * score / 100f, false, fg);
            fg.setShader(null);
        }

        num.setTextSize(radius * 0.85f);
        c.drawText(String.valueOf(score), cx, cy + radius * 0.22f, num);
        label.setTextSize(radius * 0.2f);
        c.drawText("睡眠评分", cx, cy - radius * 0.42f, label);
        grade.setColor(c1);
        grade.setTextSize(radius * 0.24f);
        c.drawText(level, cx, cy + radius * 0.62f, grade);
    }
}
