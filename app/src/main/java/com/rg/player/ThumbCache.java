package com.rg.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileOutputStream;


/**
 * 进度条预览用的低清关键帧条：每个视频有若干等间隔小图，存在应用缓存目录里。
 * 拖动时先读缓存，读不到再现场解码，避免老机器卡顿。
 */
public final class ThumbCache {

    private static final java.util.concurrent.ExecutorService POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    
    private static final int W = 256;
    private static final int H = 144;

    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> DONE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Float> PROG =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> SCANNING =
            new java.util.concurrent.ConcurrentHashMap<>();

    private ThumbCache() {
    }

    public static float progress(android.content.Context c, android.net.Uri uri) {
        String key = id(uri);
        Float p = PROG.get(key);
        if (p != null) return p;
        if (SCANNING.putIfAbsent(key, Boolean.TRUE) == null) {
            POOL.execute(() -> {
                try {
                    int total = metaCount(c, uri, 0);
                    int n = 0;
                    for (int i = 0; i < total; i++) {
                        if (file(c, uri, i).exists()) n++;
                    }
                    PROG.put(key, Math.min(1f, n / (float) Math.max(1, total)));
                } finally {
                    SCANNING.remove(key);
                }
            });
        }
        return 0f;
    }

    public static int prune(android.content.Context c, java.util.Set<String> aliveUris) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (String u : aliveUris) ids.add(id(android.net.Uri.parse(u)));
        java.io.File[] files = dir(c).listFiles();
        if (files == null) return 0;
        int removed = 0;
        for (java.io.File f : files) {
            String n = f.getName();
            boolean keep = false;
            for (String id : ids) {
                if (n.startsWith(id + "_") || n.startsWith(id + ".")) {
                    keep = true;
                    break;
                }
            }
            if (!keep && f.delete()) removed++;
        }
        DONE.keySet().removeIf(k -> !ids.contains(k));
        PROG.keySet().removeIf(k -> !ids.contains(k));
        SCANNING.keySet().removeIf(k -> !ids.contains(k));
        return removed;
    }

    public static void reset() {
        DONE.clear();
        PROG.clear();
        SCANNING.clear();
    }

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "strip");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String id(android.net.Uri uri) {
        return String.valueOf(Math.abs(uri.toString().hashCode()));
    }

    /** 长视频多取几帧：至少 12 帧，按时长增加，最多 120 帧（1 分钟 1 帧左右）。 */
    public static int countFor(long durationMs) {
        if (durationMs <= 0) return 12;
        long minutes = durationMs / 60000;
        int n = (int) (12 + minutes * 2);
        return Math.max(12, Math.min(120, n));
    }

    private static java.io.File metaFile(Context c, android.net.Uri uri) {
        return new java.io.File(dir(c), id(uri) + ".n");
    }

    private static int metaCount(Context c, android.net.Uri uri, long durationMs) {
        try {
            java.io.File f = metaFile(c, uri);
            if (f.exists()) {
                String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").trim();
                return Integer.parseInt(s);
            }
        } catch (Throwable ignored) {
        }
        return countFor(durationMs);
    }

    public static File file(Context c, android.net.Uri uri, int index) {
        return new File(dir(c), id(uri) + "_" + index + ".jpg");
    }

    public static Bitmap load(Context c, android.net.Uri uri, int index) {
        try {
            File f = file(c, uri, index);
            if (!f.exists()) return null;
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 后台补齐这个视频的关键帧条；已经齐了就立刻返回。 */
    public static void ensure(Context c, VideoItem item) {
        if (item == null || item.durationMs <= 0) return;
        POOL.execute(() -> build(c, item));
    }

    private static void build(Context c, VideoItem item) {
        int total = metaCount(c, item.uri, item.durationMs);
        if (progress(c, item.uri) >= 0.999f) return;
        try {
            java.io.FileOutputStream mf = new java.io.FileOutputStream(metaFile(c, item.uri));
            mf.write(String.valueOf(total).getBytes("UTF-8"));
            mf.close();
        } catch (Throwable ignored) {
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(c, item.uri);
            for (int i = 0; i < total; i++) {
                File out = file(c, item.uri, i);
                if (out.exists()) continue;
                long tUs = (long) (item.durationMs * 1000L * (i + 0.5) / total);
                Bitmap bmp = r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (bmp == null) continue;
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, W, H, true);
                if (scaled != bmp) bmp.recycle();
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(out);
                    scaled.compress(Bitmap.CompressFormat.JPEG, 72, fos);
                    DONE.put(id(item.uri), i + 1);
                    PROG.put(id(item.uri), Math.min(1f, (i + 1) / (float) Math.max(1, total)));
                } catch (Throwable ignored) {
                } finally {
                    if (fos != null) try { fos.close(); } catch (Throwable ignored) {}
                }
                scaled.recycle();
            }
        } catch (Throwable ignored) {
        } finally {
            try { r.release(); } catch (Throwable ignored) {}
            Integer d = DONE.get(id(item.uri));
            if (d != null) PROG.put(id(item.uri), Math.min(1f, d / (float) Math.max(1, total)));
        }
    }

    /** 根据进度选最近的缓存帧（找不到返回 null）。 */
    public static Bitmap nearest(Context c, android.net.Uri uri, long ms, long durMs) {
        if (durMs <= 0) return null;
        int total = metaCount(c, uri, durMs);
        int idx = (int) Math.max(0, Math.min(total - 1,
                Math.floor(ms / (double) durMs * total)));
        Bitmap b = load(c, uri, idx);
        if (b != null) return b;
        for (int off = 1; off < total; off++) {
            b = load(c, uri, Math.max(0, Math.min(total - 1, idx - off)));
            if (b != null) return b;
            b = load(c, uri, Math.max(0, Math.min(total - 1, idx + off)));
            if (b != null) return b;
        }
        return null;
    }
}
