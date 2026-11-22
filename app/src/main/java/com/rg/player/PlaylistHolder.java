package com.rg.player;

import java.util.ArrayList;
import java.util.List;

/** 当前播放列表（跨 Activity 传递用，避免大对象走 Intent）。 */
public class PlaylistHolder {
    private static List<VideoItem> items = new ArrayList<>();
    private static int index = 0;

    public static void set(List<VideoItem> list, int i) {
        items = list == null ? new ArrayList<>() : list;
        index = Math.max(0, Math.min(i, items.size() - 1));
    }

    public static List<VideoItem> items() {
        return items;
    }

    public static int index() {
        return index;
    }

    public static void setIndex(int i) {
        index = Math.max(0, Math.min(i, items.size() - 1));
    }
}
