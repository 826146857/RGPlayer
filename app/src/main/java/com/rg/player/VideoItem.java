package com.rg.player;

import android.net.Uri;

public class VideoItem {
    public final long id;
    public final Uri uri;
    public final String name;
    public final long durationMs;
    public final long size;
    public final String folder;
    public final String path;
    public final String relPath;
    public final long dateAdded;
    public final String volumeId;

    public VideoItem(long id, Uri uri, String name, long durationMs, long size,
                     String folder, String path, String relPath, long dateAdded, String volumeId) {
        this.id = id;
        this.uri = uri;
        this.name = name;
        this.durationMs = durationMs;
        this.size = size;
        this.folder = folder;
        this.path = path;
        this.relPath = relPath;
        this.dateAdded = dateAdded;
        this.volumeId = volumeId;
    }
}
