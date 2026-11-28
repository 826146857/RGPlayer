package com.rg.player;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

/** 详情弹窗用：单击任意位置关闭，拖动仍然可以滚动。 */
public class TapDismissScrollView extends ScrollView {

    private final Runnable onDismiss;
    private float downX, downY;
    private boolean moved;

    public TapDismissScrollView(Context c, Runnable onDismiss) {
        super(c);
        this.onDismiss = onDismiss;
        setScrollBarStyle(SCROLLBARS_INSIDE_INSET);
        setScrollbarFadingEnabled(true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                moved = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(e.getX() - downX, e.getY() - downY)
                        > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    moved = true;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (!moved && onDismiss != null) onDismiss.run();
                break;
            default:
                break;
        }
        return super.onTouchEvent(e);
    }
}
