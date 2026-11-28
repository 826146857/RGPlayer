package com.rg.player;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** 宽高保持 1:1 的容器，用于封面。 */
public class SquareFrameLayout extends FrameLayout {
    public SquareFrameLayout(Context c) { super(c); }
    public SquareFrameLayout(Context c, AttributeSet a) { super(c, a); }
    public SquareFrameLayout(Context c, AttributeSet a, int s) { super(c, a, s); }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        int w = getMeasuredWidth();
        setMeasuredDimension(w, w);
    }
}
