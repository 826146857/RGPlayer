package com.rg.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VH> {

    public interface OnClick {
        void onClick(int position);
    }

    private final Context context;
    private final List<VideoItem> items;
    private final OnClick onClick;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int itemWidthDp;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };

    public VideoAdapter(Context context, List<VideoItem> items, OnClick onClick) {
        this(context, items, onClick, 0);
    }

    public VideoAdapter(Context context, List<VideoItem> items, OnClick onClick, int itemWidthDp) {
        this.context = context;
        this.items = items;
        this.onClick = onClick;
        this.itemWidthDp = itemWidthDp;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_video, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        VideoItem item = items.get(position);
        if (itemWidthDp > 0) {
            ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
            lp.width = (int) (itemWidthDp * context.getResources().getDisplayMetrics().density);
            h.itemView.setLayoutParams(lp);
        }
        h.name.setText(item.name);
        h.duration.setText(formatDuration(item.durationMs));
        h.thumb.setTag(item.uri.toString());
        h.thumb.setImageDrawable(null);
        h.thumb.setBackgroundColor(0xFF12141A);

        long pos = MediaLibrary.position(context, item.uri);
        if (pos > 0 && item.durationMs > 0) {
            int pct = (int) Math.min(1000, pos * 1000 / item.durationMs);
            h.progress.setVisibility(View.VISIBLE);
            h.progress.setProgress(pct);
        } else {
            h.progress.setVisibility(View.GONE);
        }

        String key = item.uri.toString();
        Bitmap cached = cache.get(key);
        if (cached != null) {
            h.thumb.setImageBitmap(cached);
        } else {
            final String tag = key;
            pool.execute(() -> {
                Bitmap bmp = CoverLoader.load(context, item, 360, 204);
                if (bmp == null) return;
                cache.put(key, bmp);
                main.post(() -> {
                    if (tag.equals(h.thumb.getTag())) {
                        h.thumb.setImageBitmap(bmp);
                    }
                });
            });
        }

        h.itemView.setOnClickListener(v -> onClick.onClick(h.getAdapterPosition()));
    }


    public static String formatDuration(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", m, s);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView name;
        final TextView duration;
        final ProgressBar progress;

        VH(@NonNull View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.thumb);
            name = itemView.findViewById(R.id.name);
            duration = itemView.findViewById(R.id.duration);
            progress = itemView.findViewById(R.id.progress);
        }
    }
}
