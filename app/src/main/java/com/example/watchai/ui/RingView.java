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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** Apple Watch 风格三环：活动 / 锻炼 / 站立（带渐变、圆头、发光） */
public class RingView extends View {

    private float move, exercise, stand;
    private int moveVal, exVal, standVal;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pMove = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pEx = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pStand = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RingView(Context c) { super(c); init(); }
    public RingView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        bg.setStyle(Paint.Style.STROKE);
        bg.setColor(Color.argb(38, 255, 255, 255));
        for (Paint p : new Paint[]{pMove, pEx, pStand}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setData(float move, float exercise, float stand, int moveVal, int exVal, int standVal) {
        this.move = clamp(move);
        this.exercise = clamp(exercise);
        this.stand = clamp(stand);
        this.moveVal = moveVal;
        this.exVal = exVal;
        this.standVal = standVal;
        invalidate();
    }

    private float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float radius = Math.min(w, h) / 2f - 14f;
        float stroke = radius * 0.14f;
        float gap = stroke * 0.32f;

        drawRing(c, cx, cy, radius, stroke, bg, 1f, null);
        drawRing(c, cx, cy, radius, stroke, pMove, move,
                grad(cx, radius, Color.rgb(255, 45, 85), Color.rgb(255, 120, 80)));
        drawRing(c, cx, cy, radius - stroke - gap, stroke, bg, 1f, null);
        drawRing(c, cx, cy, radius - stroke - gap, stroke, pEx,
                exercise, grad(cx, radius - stroke - gap, Color.rgb(120, 255, 70), Color.rgb(30, 220, 150)));
        drawRing(c, cx, cy, radius - 2*(stroke + gap), stroke, bg, 1f, null);
        drawRing(c, cx, cy, radius - 2*(stroke + gap), stroke, pStand,
                stand, grad(cx, radius - 2*(stroke + gap), Color.rgb(70, 200, 255), Color.rgb(80, 120, 255)));
    }

    private Shader grad(float cx, float r, int c1, int c2) {
        return new LinearGradient(cx - r, cx - r, cx + r, cx + r, c1, c2, Shader.TileMode.CLAMP);
    }

    private void drawRing(Canvas c, float cx, float cy, float radius, float stroke,
                          Paint p, float progress, Shader shader) {
        p.setStrokeWidth(stroke);
        if (shader != null) p.setShader(shader);
        RectF rect = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        if (progress > 0.004f) c.drawArc(rect, -90, 360 * progress, false, p);
        if (shader != null) p.setShader(null);
    }
}
