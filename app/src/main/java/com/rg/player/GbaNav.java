package com.rg.player;

import android.os.SystemClock;

/** F10 按键防抖：一次按键固件可能连发两次广播（按下/抬起），避免列表刚打开又被关掉。 */
final class GbaNav {

    private static volatile long suppressUntil;

    private GbaNav() {
    }

    static boolean blocked() {
        return SystemClock.elapsedRealtime() < suppressUntil;
    }

    static void lock(long ms) {
        suppressUntil = SystemClock.elapsedRealtime() + ms;
    }
}
