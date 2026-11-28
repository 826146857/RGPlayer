package com.rg.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

/** 把 GBA 240x160 画面按比例缩放绘制到方屏。 */
public class GbaView extends View {

    private Bitmap bitmap;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();
    private int srcW, srcH;
    private int insetL, insetT, insetR, insetB;

    public GbaView(Context c) {
        super(c);
    }

    public GbaView(Context c, AttributeSet a) {
        super(c, a);
    }

    public void setContentInset(int left, int top, int right, int bottom) {
        insetL = Math.max(0, left);
        insetT = Math.max(0, top);
        insetR = Math.max(0, right);
        insetB = Math.max(0, bottom);
        invalidate();
    }

    public void setFrame(int width, int height, int[] pixels) {
        if (width <= 0 || height <= 0) return;
        if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            srcW = width;
            srcH = height;
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xFF000000);
        if (bitmap == null) return;
        int vw = getWidth(), vh = getHeight();
        int availW = Math.max(1, vw - insetL - insetR);
        int availH = Math.max(1, vh - insetT - insetB);
        float scale = Math.min(availW / (float) srcW, availH / (float) srcH);
        int w = Math.round(srcW * scale), h = Math.round(srcH * scale);
        int left = insetL + (availW - w) / 2;
        int top = insetT + (availH - h) / 2;
        dst.set(left, top, left + w, top + h);
        canvas.drawBitmap(bitmap, null, dst, paint);
    }
}
