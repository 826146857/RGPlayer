package com.rg.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

/**
 * 跑马灯标题：文字超出宽度时按固定速度滚动，首尾之间只留一个短间距（不整体跳回）。
 */
public class MarqueeTextView extends AppCompatTextView {

    private final float gapPx;
    private final float speedPxPerSec;
    private float offset;
    private long lastFrame;

    public MarqueeTextView(Context context) {
        this(context, null);
    }

    public MarqueeTextView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float d = context.getResources().getDisplayMetrics().density;
        gapPx = 64f * d;
        speedPxPerSec = 38f * d;
        setSingleLine(true);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(text, type);
        offset = 0;
        lastFrame = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        CharSequence text = getText();
        Paint paint = getPaint();
        paint.setColor(getCurrentTextColor());

        if (text == null || text.length() == 0) {
            super.onDraw(canvas);
            return;
        }

        float textWidth = paint.measureText(text.toString());
        float avail = getWidth() - getPaddingLeft() - getPaddingRight();
        if (textWidth <= avail + 2f) {
            super.onDraw(canvas);
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (lastFrame == 0) lastFrame = now;
        float dt = Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;

        float cycle = textWidth + gapPx;
        offset += speedPxPerSec * dt;
        if (offset >= cycle) offset -= cycle;

        canvas.save();
        canvas.clipRect(getPaddingLeft(), 0, getWidth() - getPaddingRight(), getHeight());
        canvas.drawText(text.toString(), getPaddingLeft() - offset, getBaseline(), paint);
        canvas.drawText(text.toString(), getPaddingLeft() - offset + cycle, getBaseline(), paint);
        canvas.restore();
        postInvalidateOnAnimation();
    }
}
