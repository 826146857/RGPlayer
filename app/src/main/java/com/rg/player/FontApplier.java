package com.rg.player;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

/** 应用字体：系统字体 / 内置字体（打包的 400.ttf）。 */
public final class FontApplier {

    private static Typeface builtin;

    private FontApplier() {
    }

    private static Typeface builtin(Context c) {
        if (builtin == null) {
            try {
                builtin = ResourcesCompat.getFont(c, R.font.builtin);
            } catch (Throwable ignored) {
            }
        }
        return builtin;
    }

    public static void apply(Context c, View root) {
        if (root == null) return;
        boolean useBuiltin = Settings.getFontMode(c) == 1;
        Typeface tf = useBuiltin ? builtin(c) : Typeface.DEFAULT;
        if (tf == null) return;
        walk(root, tf, useBuiltin);
    }

    private static void walk(View v, Typeface tf, boolean useBuiltin) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            Typeface old = tv.getTypeface();
            int style = old == null ? Typeface.NORMAL : old.getStyle();
            if (style != Typeface.NORMAL) {
                tv.setTypeface(Typeface.create(tf, style));
            } else {
                tv.setTypeface(tf);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), tf, useBuiltin);
            }
        }
    }
}
