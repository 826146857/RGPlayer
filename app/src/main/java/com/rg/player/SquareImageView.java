package com.rg.player;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/** 按宽度 1:1 测量的封面图。 */
public class SquareImageView extends ImageView {
    public SquareImageView(Context c) { super(c); }
    public SquareImageView(Context c, AttributeSet a) { super(c, a); }
    public SquareImageView(Context c, AttributeSet a, int s) { super(c, a, s); }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        int w = getMeasuredWidth();
        setMeasuredDimension(w, w);
    }
}
