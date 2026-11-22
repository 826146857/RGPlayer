package com.rg.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 只读取用户自己放在视频同目录的封面文件，绝不用播放器截帧生成缩略图。
 *
 * 支持的文件名（大小写敏感，放在视频所在目录）：
 *   1. 与视频同名，扩展名为 .jpg / .jpeg / .png / .webp
 *      例：Movie.mkv → Movie.jpg
 *   2. 与视频同名 + 后缀：Movie-poster.jpg / Movie.poster.png / Movie-cover.jpg
 *   3. 目录级封面：poster.jpg / folder.jpg / cover.jpg（该目录所有视频共用）
 */
public final class CoverLoader {

    private static final String[] EXTS = {".jpg", ".jpeg", ".png", ".webp"};
    private static final String[] FOLDER_NAMES = {"poster"};

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(32 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };

    private CoverLoader() {
    }

    public static void clearCache() {
        CACHE.evictAll();
    }

    public static Bitmap load(android.content.Context context, VideoItem item, int reqW, int reqH) {
        String key = (item.path != null ? item.path : item.uri.toString()) + "|" + reqW + "x" + reqH;
        Bitmap cached = CACHE.get(key);
        if (cached != null) return cached;

        File cover = findCoverFile(item);
        Bitmap bmp = cover != null ? decode(cover, reqW, reqH) : null;
        if (bmp == null && context != null) {
            bmp = firstFrame(context, item, reqW, reqH);
        }
        if (bmp != null) CACHE.put(key, bmp);
        return bmp;
    }

    /** 没有封面文件时取第一帧，只在内存里用，不写任何缓存文件。 */
    private static Bitmap firstFrame(android.content.Context context, VideoItem item, int reqW, int reqH) {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, item.uri);
            long durationMs = 0;
            try {
                String d = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) durationMs = Long.parseLong(d);
            } catch (Throwable ignored) {
            }
            // 片头常常是纯黑 / 纯白 / 纯色卡；按“画面复杂度”挑一帧，仍然只在内存里
            long base = durationMs > 0 ? durationMs * 1000L : 0L;
            long[] timesUs = {
                    base > 0 ? Math.min(base * 15 / 100, 180_000_000L) : 15_000_000L,
                    base > 0 ? Math.min(base / 4, 240_000_000L) : 30_000_000L,
                    base > 0 ? Math.min(base / 3, 300_000_000L) : 45_000_000L,
                    10_000_000L,
                    20_000_000L,
                    5_000_000L,
                    0L
            };
            Bitmap best = null;
            for (long t : timesUs) {
                Bitmap b = retriever.getFrameAtTime(t,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (b == null) continue;
                if (best == null) {
                    best = b;
                } else if (!isFlat(b) && !isPattern(b)) {
                    best.recycle();
                    best = b;
                } else {
                    b.recycle();
                }
                if (!isFlat(best) && !isPattern(best)) break;
            }
            if (best == null) return null;
            return scale(best, reqW, reqH);
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 纯黑、纯白、纯色卡都算“平”，按亮度方差 + 与主色的差异比例判断。 */
    private static boolean isFlat(Bitmap bmp) {
        if (bmp == null) return true;
        final int N = 32;
        double[] lum = new double[N * N];
        int idx = 0;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        for (int gy = 0; gy < N; gy++) {
            int y = Math.min(h - 1, gy * h / N);
            for (int gx = 0; gx < N; gx++) {
                int x = Math.min(w - 1, gx * w / N);
                int c = bmp.getPixel(x, y);
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                lum[idx++] = 0.299 * r + 0.587 * g + 0.114 * b;
            }
        }
        double mean = 0;
        for (double v : lum) mean += v;
        mean /= lum.length;
        double var = 0;
        for (double v : lum) var += (v - mean) * (v - mean);
        double std = Math.sqrt(var / lum.length);

        double[] sorted = lum.clone();
        java.util.Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        int different = 0;
        for (double v : lum) {
            if (Math.abs(v - median) > 25) different++;
        }
        double contentRatio = (double) different / lum.length;

        if (mean > 246 || mean < 8) return true;       // 纯白 / 纯黑
        if (std < 9) return true;                       // 纯色 / 几乎无变化
        return contentRatio < 0.04;                     // 只有极少量内容（小 logo 白底之类）
    }

    /** 色条/测试卡检测：高饱和像素过多就跳过。 */
    private static boolean isPattern(Bitmap bmp) {
        if (bmp == null) return false;
        final int N = 16;
        int colored = 0;
        int total = 0;
        for (int gy = 0; gy < N; gy++) {
            int y = Math.min(bmp.getHeight() - 1, gy * bmp.getHeight() / N);
            for (int gx = 0; gx < N; gx++) {
                int x = Math.min(bmp.getWidth() - 1, gx * bmp.getWidth() / N);
                int c = bmp.getPixel(x, y);
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                if (max > 60 && (max - min) > max * 0.9) colored++;
                total++;
            }
        }
        return total > 0 && colored / (float) total > 0.5f;
    }

    private static Bitmap scale(Bitmap src, int reqW, int reqH) {
        if (src.getWidth() <= reqW && src.getHeight() <= reqH) return src;
        float ratio = Math.min((float) reqW / src.getWidth(), (float) reqH / src.getHeight());
        int w = Math.max(1, Math.round(src.getWidth() * ratio));
        int h = Math.max(1, Math.round(src.getHeight() * ratio));
        Bitmap out = Bitmap.createScaledBitmap(src, w, h, true);
        if (out != src) src.recycle();
        return out;
    }

    public static File findCoverFile(VideoItem item) {
        String path = item.path;
        String name = item.name;
        if (path == null) return null;
        File file = new File(path);
        File dir = file.getParentFile();
        if (dir == null) return null;

        String base = name;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);

        List<String> names = new ArrayList<>();
        for (String ext : EXTS) {
            names.add(base + ext);
        }
        for (String n : names) {
            File f = new File(dir, n);
            if (f.isFile()) return f;
        }
        for (String n : FOLDER_NAMES) {
            for (String ext : EXTS) {
                File f = new File(dir, n + ext);
                if (f.isFile()) return f;
            }
        }
        return null;
    }

    private static Bitmap decode(File file, int reqW, int reqH) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Throwable t) {
            return null;
        }
    }
}
