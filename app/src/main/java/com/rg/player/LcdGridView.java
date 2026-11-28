package com.rg.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** GBA LCD 网格：屏幕空间扫描线，不参与视频缩放/旋转。 */
public class LcdGridView extends View {
    private final Paint paint = new Paint();
    private int videoW, videoH;
    private int style;

    public LcdGridView(Context c, AttributeSet a) {
        super(c, a);
        paint.setColor(0x66000000);
        paint.setStrokeWidth(1f);
        setWillNotDraw(false);
    }

    public void setStyle(int style) {
        this.style = style;
        invalidate();
    }

    public void setVideoSize(int w, int h) {
        videoW = w;
        videoH = h;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int vw = getWidth(), vh = getHeight();
        int left = 0, top = 0, right = vw, bottom = vh;
        if (videoW > 0 && videoH > 0) {
            float scale = Math.min(vw / (float) videoW, vh / (float) videoH);
            int w = Math.round(videoW * scale), h = Math.round(videoH * scale);
            left = (vw - w) / 2;
            top = (vh - h) / 2;
            right = left + w;
            bottom = top + h;
        }
        float d = getResources().getDisplayMetrics().density;
        int step;
        boolean vertical = true;
        if (style == 2) {
            step = Math.max(3, Math.round(d * 4));
            paint.setColor(0x99000000);
        } else if (style == 3) {
            step = Math.max(2, Math.round(d * 2));
            vertical = false;
            paint.setColor(0x77000000);
        } else {
            step = Math.max(2, Math.round(d * 2));
            paint.setColor(0x66000000);
        }
        paint.setStrokeWidth(style == 2 ? 2f : 1f);
        for (int y = top; y <= bottom; y += step) canvas.drawLine(left, y, right, y, paint);
        if (vertical) {
            for (int x = left; x <= right; x += step) canvas.drawLine(x, top, x, bottom, paint);
        }
    }
}
