package com.rg.player;

import android.content.Context;

import java.io.File;
import java.io.RandomAccessFile;

/** 粗略判断 .gba 是 M3 电影 ROM 还是普通游戏 ROM，结果按路径+大小+修改时间缓存。 */
final class GbaRomDetector {

    private static final String[] SIGNATURES = {
            "GBA Media Player", "gbam3.ausar", "FilmPlay", "M3 Movie", "M3Movie",
            "GBA Video", "DC STUDIOS", "Video Sequencing",
            "Logo Majesco.mmstr", "background_splash", "background_pressstart"
    };

    private GbaRomDetector() {
    }

    static boolean isVideo(Context c, File f) {
        String key = "gv2:" + f.getAbsolutePath() + ":" + f.length() + ":" + f.lastModified();
        try {
            String cached = c.getSharedPreferences("rgsettings", Context.MODE_PRIVATE)
                    .getString(key, null);
            if (cached != null) return "1".equals(cached);
        } catch (Throwable ignored) {
        }
        boolean video = detect(f);
        try {
            c.getSharedPreferences("rgsettings", Context.MODE_PRIVATE)
                    .edit().putString(key, video ? "1" : "0").apply();
        } catch (Throwable ignored) {
        }
        return video;
    }

    private static boolean scanRegion(RandomAccessFile raf, long start, long length) throws java.io.IOException {
        raf.seek(start);
        long read = 0;
        int carry = 0;
        byte[] buf = new byte[64 * 1024];
        while (read < length) {
            int n = raf.read(buf, carry, buf.length - carry);
            if (n <= 0) break;
            read += n;
            int len = carry + n;
            String chunk = new String(buf, 0, len,
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            for (String sig : SIGNATURES) {
                if (chunk.contains(sig)) return true;
            }
            int keep = Math.min(64, len);
            System.arraycopy(buf, len - keep, buf, 0, keep);
            carry = keep;
        }
        return false;
    }

    private static boolean detect(File f) {
        String lower = f.getName().toLowerCase(java.util.Locale.US);
        if (lower.contains("gba video") || lower.contains("game boy advance video")
                || lower.contains("gba电影") || lower.contains("gba视频")) {
            return true;
        }
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] header = new byte[0xC0];
            raf.readFully(header);
            String title = new String(header, 0xA0, 12,
                    java.nio.charset.StandardCharsets.ISO_8859_1).trim();
            if (title.toUpperCase(java.util.Locale.US).contains("GBAVID")) return true;
            boolean blankHeader = true;
            for (int i = 0xA0; i <= 0xBC; i++) {
                int b = header[i] & 0xFF;
                if (b != 0 && b != 0x20) {
                    blankHeader = false;
                    break;
                }
            }
            // 扫描前 4MB + 末尾 8MB（部分欧版 M3 视频的引擎字符串在 27MB 之后）
            if (scanRegion(raf, 0, Math.min(f.length(), 4L * 1024 * 1024))) return true;
            long tailStart = Math.max(0, f.length() - 8L * 1024 * 1024);
            if (tailStart > 4L * 1024 * 1024 && scanRegion(raf, tailStart, f.length() - tailStart)) {
                return true;
            }
            if (blankHeader && f.length() >= 8L * 1024 * 1024) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }
}
