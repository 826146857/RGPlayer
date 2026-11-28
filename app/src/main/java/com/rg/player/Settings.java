package com.rg.player;

import android.content.Context;
import android.content.SharedPreferences;

/** 播放器设置（全部存 SharedPreferences）。 */
public class Settings {

    private static final String FILE = "rgsettings";
    private static final String K_RESIZE = "resize";
    private static final String K_GESTURE = "gesture";
    private static final String K_DOUBLE_TAP = "double_tap";

    private static final String K_AUTO_NEXT = "auto_next";
    private static final String K_RESUME = "resume";

    public final int resize;
    public final boolean gesture;
    public final boolean doubleTap;
    public final boolean autoNext;
    public final boolean resume;

    private Settings(SharedPreferences p) {
        resize = p.getInt(K_RESIZE, 0);
        gesture = p.getBoolean(K_GESTURE, false);
        doubleTap = p.getBoolean(K_DOUBLE_TAP, false);

        autoNext = p.getBoolean(K_AUTO_NEXT, true);
        resume = p.getBoolean(K_RESUME, true);
    }

    public static Settings load(Context c) {
        return new Settings(prefs(c));
    }

    public static int getResize(Context c) {
        return prefs(c).getInt(K_RESIZE, 0);
    }

    public static void setResize(Context c, int v) {
        prefs(c).edit().putInt(K_RESIZE, v).apply();
    }

    public static boolean getGesture(Context c) {
        return prefs(c).getBoolean(K_GESTURE, false);
    }

    public static void setGesture(Context c, boolean v) {
        prefs(c).edit().putBoolean(K_GESTURE, v).apply();
    }

    public static boolean getDoubleTap(Context c) {
        return prefs(c).getBoolean(K_DOUBLE_TAP, false);
    }

    public static void setDoubleTap(Context c, boolean v) {
        prefs(c).edit().putBoolean(K_DOUBLE_TAP, v).apply();
    }


    public static boolean getAutoNext(Context c) {
        return prefs(c).getBoolean(K_AUTO_NEXT, true);
    }

    public static void setAutoNext(Context c, boolean v) {
        prefs(c).edit().putBoolean(K_AUTO_NEXT, v).apply();
    }

    public static boolean getResume(Context c) {
        return prefs(c).getBoolean(K_RESUME, true);
    }

    public static void setResume(Context c, boolean v) {
        prefs(c).edit().putBoolean(K_RESUME, v).apply();
    }

    public static String getTrack(Context c, String kind, android.net.Uri uri) {
        return prefs(c).getString(kind + ":" + uri.toString(), null);
    }

    public static void putTrack(Context c, String kind, android.net.Uri uri, String key) {
        prefs(c).edit().putString(kind + ":" + uri.toString(), key).apply();
    }

    public static String getMediaDir(Context c) {
        return prefs(c).getString("media_dir", "");
    }

    public static String getMediaVolume(Context c) {
        return prefs(c).getString("media_vol", "");
    }

    public static void setMediaDir(Context c, String volume, String relPath) {
        prefs(c).edit().putString("media_vol", volume == null ? "" : volume)
                .putString("media_dir", relPath == null ? "" : relPath).apply();
    }

    public static boolean hasLoudGain(Context c, android.net.Uri uri) {
        return prefs(c).contains("lg2:" + uri.toString());
    }

    public static float getLoudGain(Context c, android.net.Uri uri) {
        return prefs(c).getFloat("lg2:" + uri.toString(), 0f);
    }

    public static void putLoudGain(Context c, android.net.Uri uri, float db) {
        prefs(c).edit().putFloat("lg2:" + uri.toString(), db).apply();
    }

    public static boolean getHideNoMedia(Context c) {
        return prefs(c).getBoolean("hide_nomedia", true);
    }

    public static void setHideNoMedia(Context c, boolean v) {
        prefs(c).edit().putBoolean("hide_nomedia", v).apply();
    }

    public static boolean getLoudness(Context c) {
        return prefs(c).getBoolean("loudness", false);
    }

    public static void setLoudness(Context c, boolean v) {
        prefs(c).edit().putBoolean("loudness", v).apply();
    }

    public static boolean getStereo(Context c) {
        return prefs(c).getBoolean("stereo", true);
    }

    public static void setStereo(Context c, boolean v) {
        prefs(c).edit().putBoolean("stereo", v).apply();
    }


    public static int getFontMode(Context c) {
        return prefs(c).getInt("font_mode", 1);
    }

    public static void setFontMode(Context c, int v) {
        prefs(c).edit().putInt("font_mode", v % 2).apply();
    }

    public static boolean getTextEnabled(Context c) {
        return prefs(c).getBoolean("text_on", true);
    }

    public static void setTextEnabled(Context c, boolean v) {
        prefs(c).edit().putBoolean("text_on", v).apply();
    }


    public static float getSpeed(Context c) {
        return prefs(c).getFloat("speed", 1f);
    }

    public static void setSpeed(Context c, float v) {
        prefs(c).edit().putFloat("speed", v).apply();
    }

    public static int getFilter(Context c) {
        return prefs(c).getInt("video_filter", 0);
    }

    public static void putFilter(Context c, int v) {
        prefs(c).edit().putInt("video_filter", v).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
