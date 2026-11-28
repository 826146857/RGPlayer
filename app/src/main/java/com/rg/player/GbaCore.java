package com.rg.player;

/** mGBA libretro 核心的 JNI 封装。 */
public final class GbaCore {

    static {
        System.loadLibrary("gba_bridge");
    }

    public final long handle;

    private GbaCore(long handle) {
        this.handle = handle;
    }

    /** 加载核心与 ROM，失败返回 null。 */
    public static GbaCore create(String corePath, String romPath, String systemDir, String saveDir) {
        long h = nativeCreate(corePath, romPath, systemDir, saveDir);
        return h == 0 ? null : new GbaCore(h);
    }

    public void run() {
        nativeRun(handle);
    }

    public int width() {
        return nativeWidth(handle);
    }

    public int height() {
        return nativeHeight(handle);
    }

    public int video(int[] out) {
        return nativeVideo(handle, out);
    }

    public int audio(short[] out) {
        return nativeAudio(handle, out);
    }

    public void buttons(int mask) {
        nativeButtons(handle, mask);
    }

    public float sampleRate() {
        return nativeSampleRate(handle);
    }

    public boolean saveSram(String path) {
        return nativeSaveSram(handle, path);
    }

    public boolean loadSram(String path) {
        return nativeLoadSram(handle, path);
    }

    public void destroy() {
        nativeDestroy(handle);
    }

    private static native long nativeCreate(String corePath, String romPath, String systemDir, String saveDir);

    private static native void nativeRun(long h);

    private static native int nativeWidth(long h);

    private static native int nativeHeight(long h);

    private static native int nativeVideo(long h, int[] out);

    private static native int nativeAudio(long h, short[] out);

    private static native void nativeButtons(long h, int mask);

    private static native float nativeSampleRate(long h);

    private static native boolean nativeSaveSram(long h, String path);

    private static native boolean nativeLoadSram(long h, String path);

    private static native void nativeDestroy(long h);
}
