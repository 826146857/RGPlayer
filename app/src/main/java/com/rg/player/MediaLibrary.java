package com.rg.player;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

public class MediaLibrary {

    private static final String PREFS = "rgplayer";
    private static final String KEY_POS = "pos:";
    private static final String KEY_PTIME = "postime:";

    public static List<VideoItem> loadVideos(Context context) {
        return loadVideos(context, true);
    }

    public static List<VideoItem> loadVideos(Context context, int sort) {
        return loadVideos(context, true);
    }

    public static List<VideoItem> loadVideos(Context context, int sort, boolean filterNoMedia) {
        return loadVideos(context, filterNoMedia);
    }

    public static List<VideoItem> loadVideos(Context context, boolean filterNoMedia) {
        List<VideoItem> out = new ArrayList<>();
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

        List<String> cols = new ArrayList<>();
        cols.add(MediaStore.Video.Media._ID);
        cols.add(MediaStore.Video.Media.DISPLAY_NAME);
        cols.add(MediaStore.Video.Media.DURATION);
        cols.add(MediaStore.Video.Media.SIZE);
        cols.add(MediaStore.Video.Media.DATE_ADDED);
        cols.add(MediaStore.Video.Media.DATA);
        if (Build.VERSION.SDK_INT >= 29) {
            cols.add(MediaStore.Video.Media.RELATIVE_PATH);
        }

        try (Cursor c = context.getContentResolver().query(
                collection,
                cols.toArray(new String[0]),
                MediaStore.Video.Media.SIZE + ">0",
                null,
                MediaStore.Video.Media.DISPLAY_NAME + " ASC")) {
            if (c == null) return out;
            int idCol = c.getColumnIndex(MediaStore.Video.Media._ID);
            int nameCol = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
            int durCol = c.getColumnIndex(MediaStore.Video.Media.DURATION);
            int sizeCol = c.getColumnIndex(MediaStore.Video.Media.SIZE);
            int dateCol = c.getColumnIndex(MediaStore.Video.Media.DATE_ADDED);
            int dataCol = c.getColumnIndex(MediaStore.Video.Media.DATA);
            int relCol = Build.VERSION.SDK_INT >= 29
                    ? c.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH) : -1;

            while (c.moveToNext()) {
                long id = c.getLong(idCol);
                Uri uri = Uri.withAppendedPath(collection, String.valueOf(id));
                String path = dataCol >= 0 ? c.getString(dataCol) : null;
                String rel = relCol >= 0 ? c.getString(relCol) : null;
                String folder = folderOf(path, rel);
                String relPath = rel != null ? rel : deriveRel(path);
                out.add(new VideoItem(id, uri, c.getString(nameCol),
                        c.getLong(durCol), c.getLong(sizeCol), folder, path, relPath, c.getLong(dateCol), volumeOf(path)));
            }
        } catch (Exception ignored) {
        }
        if (filterNoMedia) out = filterNoMedia(out);
        out = filterByDir(context, out);
        return out;
    }

    private static String volumeOf(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/');
        if (p.startsWith("/sdcard") || p.startsWith("/storage/emulated/")) return "primary";
        if (p.startsWith("/storage/")) {
            String rest = p.substring("/storage/".length());
            int i = rest.indexOf('/');
            return i > 0 ? rest.substring(0, i) : rest;
        }
        return "";
    }

    private static List<VideoItem> filterByDir(Context context, List<VideoItem> in) {
        String rel = Settings.getMediaDir(context);
        if (rel == null || rel.isEmpty()) return in;
        String vol = Settings.getMediaVolume(context);
        String relNorm = rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel;
        List<VideoItem> out = new ArrayList<>();
        for (VideoItem v : in) {
            if (!vol.isEmpty() && !vol.equals(v.volumeId)) continue;
            String r = v.relPath == null ? "" : v.relPath;
            while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
            if (r.equals(relNorm) || r.startsWith(relNorm + "/")) out.add(v);
        }
        return out;
    }

    /** 过滤掉位于含 .nomedia 的目录（含任意上级）里的视频，不依赖 MediaStore 行为。 */
    private static List<VideoItem> filterNoMedia(List<VideoItem> in) {
        List<VideoItem> out = new ArrayList<>();
        Map<String, Boolean> own = new HashMap<>();
        for (VideoItem v : in) {
            if (!isHiddenByNoMedia(v.path, own)) out.add(v);
        }
        return out;
    }

    private static boolean isHiddenByNoMedia(String filePath, Map<String, Boolean> own) {
        if (filePath == null) return false;
        File dir = new File(filePath).getParentFile();
        for (int depth = 0; dir != null && depth < 12; depth++) {
            String p = dir.getAbsolutePath();
            if ("/".equals(p) || "/storage".equals(p) || "/storage/emulated".equals(p)) break;
            Boolean b = own.get(p);
            if (b == null) {
                b = new File(dir, ".nomedia").exists();
                own.put(p, b);
            }
            if (b) return true;
            dir = dir.getParentFile();
        }
        return false;
    }


    private static String deriveRel(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/');
        for (String prefix : new String[]{"/storage/emulated/0/", "/sdcard/"}) {
            if (p.startsWith(prefix)) p = p.substring(prefix.length());
        }
        int i = p.lastIndexOf('/');
        if (i >= 0) p = p.substring(0, i);
        int j = p.indexOf('/');
        return j >= 0 ? p.substring(j + 1) : "";
    }

    public static String folderOf(String path, String relativePath) {
        String base = relativePath != null ? relativePath : path;
        if (base == null) return "视频";
        base = base.replace('\\', '/');
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        int i = base.lastIndexOf('/');
        String seg = i >= 0 ? base.substring(i + 1) : base;
        return seg.isEmpty() ? "视频" : seg;
    }

    private static final String KEY_SUB = "sub:";

    public static String subtitle(Context context, Uri videoUri) {
        return prefs(context).getString(KEY_SUB + videoUri.toString(), null);
    }

    public static void saveSubtitle(Context context, Uri videoUri, String subUri) {
        prefs(context).edit().putString(KEY_SUB + videoUri.toString(), subUri).apply();
    }

    public static long position(Context context, Uri uri) {
        return prefs(context).getLong(KEY_POS + uri.toString(), 0L);
    }

    public static void savePosition(Context context, Uri uri, long posMs, long durationMs) {
        SharedPreferences p = prefs(context);
        if (durationMs <= 0) return;
        if (posMs >= durationMs - 3000 || posMs < 5000) {
            p.edit().remove(KEY_POS + uri.toString())
                    .remove(KEY_PTIME + uri.toString()).apply();
        } else {
            p.edit().putLong(KEY_POS + uri.toString(), posMs)
                    .putLong(KEY_PTIME + uri.toString(), System.currentTimeMillis()).apply();
        }
    }

    public static long positionTime(Context context, Uri uri) {
        return prefs(context).getLong(KEY_PTIME + uri.toString(), 0L);
    }

    /** 有观看进度、按最近观看时间倒序的视频。 */

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
