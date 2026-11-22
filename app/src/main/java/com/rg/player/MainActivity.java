package com.rg.player;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 首页：文件夹列表 + 三列视频网格。 */
public class MainActivity extends AppCompatActivity {

    private RecyclerView list;
    private TextView title;
    private TextView subtitle;
    private TextView btnBack;
    private TextView btnSettings;
    private View empty;
    private View progress;
    private ProgressBar pullIndicator;
    private TextView pullText;
    private View pullZone;
    private TextView pullArrow;

    private DirNode root;
    private String currentPath = null;
    private final List<Object> entries = new ArrayList<>();
    private final List<VideoItem> currentVideos = new ArrayList<>();
    private final List<VideoItem> allVideos = new ArrayList<>();
    private BrowserAdapter adapter;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private float pullStartY;
    private boolean pulling;
    private boolean pullRefreshing;
    private boolean pullArmed;

    private final Runnable cacheTicker = new Runnable() {
        @Override
        public void run() {
            boolean pending = false;
            for (VideoItem v : allVideos) {
                if (ThumbCache.progress(MainActivity.this, v.uri) < 0.999f) {
                    pending = true;
                    break;
                }
            }
            if (adapter != null) adapter.notifyDataSetChanged();
            if (pending) main.postDelayed(this, 900);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        FontApplier.apply(this, findViewById(android.R.id.content));

        list = findViewById(R.id.list);
        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        btnBack = findViewById(R.id.btn_back);
        btnSettings = findViewById(R.id.btn_settings);
        empty = findViewById(R.id.empty);
        progress = findViewById(R.id.progress);
        pullIndicator = findViewById(R.id.pull_indicator);
        pullText = findViewById(R.id.pull_text);
        pullZone = findViewById(R.id.pull_zone);
        pullArrow = findViewById(R.id.pull_arrow);
        android.util.Log.i("RGPull", "indicator=" + (pullIndicator != null));

        btnBack.setOnClickListener(v -> goUp());
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        adapter = new BrowserAdapter();
        GridLayoutManager glm = new GridLayoutManager(this, 3);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return entries.get(position) instanceof DirNode ? 3 : 1;
            }
        });
        list.setLayoutManager(glm);
        list.setAdapter(adapter);
        list.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                return handlePull(rv, e, true);
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                handlePull(rv, e, false);
            }
        });
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 10);
        } else {
            load();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 10 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            load();
        }
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            List<VideoItem> videos = MediaLibrary.loadVideos(this, Settings.getHideNoMedia(this));
            DirNode built = DirNode.build(videos);
            main.post(() -> {
                root = built;
                allVideos.clear();
                allVideos.addAll(videos);
                progress.setVisibility(View.GONE);
                if (pullIndicator != null) {
                    if (pullRefreshing) {
                        main.postDelayed(() -> {
                            collapsePull(true);
                            pullRefreshing = false;
                        }, 800);
                    } else {
                        collapsePull(true);
                    }
                }
                pullRefreshing = false;
                if (list != null) list.setTranslationY(0f);
                refresh();
                for (VideoItem v : videos) ThumbCache.ensure(MainActivity.this, v);
                main.removeCallbacks(cacheTicker);
                main.postDelayed(cacheTicker, 600);
            });
        });
    }

    private void refresh() {
        entries.clear();
        currentVideos.clear();
        if (root == null) return;

        if (currentPath == null) {
            List<DirNode> homes = root.homeEntries();
            homes.sort((a, b) -> NaturalComparator.INSTANCE.compare(a.name, b.name));
            if (homes.size() == 1) {
                showNode(homes.get(0), true);
                return;
            }
            title.setText("视频库");
            btnBack.setVisibility(View.GONE);
            btnSettings.setVisibility(View.VISIBLE);
            entries.addAll(homes);
            int total = 0;
            for (DirNode n : homes) total += n.count();
            subtitle.setText(homes.size() + " 个文件夹 · " + total + " 个视频");
        } else {
            DirNode node = root.find(currentPath);
            if (node == null) {
                currentPath = null;
                refresh();
                return;
            }
            showNode(node, false);
        }

        adapter.notifyDataSetChanged();
        empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showNode(DirNode node, boolean rootSingle) {
        title.setText(rootSingle ? "视频" : node.name);
        btnBack.setVisibility(rootSingle ? View.GONE : View.VISIBLE);
        btnSettings.setVisibility(rootSingle ? View.VISIBLE : View.GONE);
        List<DirNode> subs = new ArrayList<>();
        for (DirNode c : node.children.values()) {
            if (c.count() > 0) subs.add(c);
        }
        subs.sort((a, b) -> NaturalComparator.INSTANCE.compare(a.name, b.name));
        entries.addAll(subs);
        List<VideoItem> vids = new ArrayList<>(node.videos);
        vids.sort((a, b) -> NaturalComparator.INSTANCE.compare(a.name, b.name));
        currentVideos.addAll(vids);
        entries.addAll(vids);
        subtitle.setText(node.count() + " 个视频");
    }

    private void goUp() {
        if (currentPath == null) return;
        int i = currentPath.lastIndexOf('/');
        currentPath = i < 0 ? null : currentPath.substring(0, i);
        refresh();
    }

    @Override
    public void onBackPressed() {
        if (currentPath != null) goUp();
        else super.onBackPressed();
    }

    private void setZoneHeight(int px) {
        ViewGroup.LayoutParams lp = pullZone.getLayoutParams();
        if (lp.height != px) {
            lp.height = px;
            pullZone.setLayoutParams(lp);
        }
    }

    private void hidePullViews() {
        if (pullArrow != null) pullArrow.setVisibility(View.GONE);
        if (pullIndicator != null) pullIndicator.setVisibility(View.GONE);
        if (pullText != null) pullText.setVisibility(View.GONE);
    }

    private void collapsePull(boolean animate) {
        hidePullViews();
        if (animate) {
            int from = pullZone.getHeight();
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(from, 0);
            va.setDuration(180);
            va.addUpdateListener(a -> setZoneHeight((int) a.getAnimatedValue()));
            va.start();
        } else {
            setZoneHeight(0);
        }
    }

    private boolean handlePull(RecyclerView rv, MotionEvent e, boolean intercept) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pulling = !rv.canScrollVertically(-1);
                pullStartY = e.getY();
                pullArmed = false;
                collapsePull(false);
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (!pulling) return false;
                float dy = e.getY() - pullStartY;
                if (dy <= dp(30)) {
                    setZoneHeight(0);
                    hidePullViews();
                    return false;
                }
                int h = (int) Math.min(dp(150), dy * 0.75f);
                setZoneHeight(h);
                pullArrow.setVisibility(View.GONE);
                pullIndicator.setVisibility(View.GONE);
                if (!pullArmed && dy > dp(85)) {
                    pullArmed = true;
                    if (h < dp(72)) setZoneHeight(dp(72));
                }
                if (pullArmed) {
                    pullText.setText("松手刷新");
                    pullText.setVisibility(View.VISIBLE);
                } else {
                    pullText.setVisibility(View.GONE);
                }
                pullZone.setAlpha(Math.min(1f, dy / dp(60)));
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (!pulling) return false;
                boolean trigger = pullArmed;
                boolean consumed = trigger || pullZone.getHeight() > 0;
                pulling = false;
                pullArmed = false;
                if (trigger) {
                    pullRefreshing = true;
                    pullArrow.setVisibility(View.GONE);
                    pullText.setText("刷新中…");
                    pullText.setVisibility(View.VISIBLE);
                    pullIndicator.setVisibility(View.VISIBLE);
                    int target = dp(80);
                    android.animation.ValueAnimator va =
                            android.animation.ValueAnimator.ofInt(pullZone.getHeight(), target);
                    va.setDuration(180);
                    va.addUpdateListener(a -> setZoneHeight((int) a.getAnimatedValue()));
                    va.start();
                    load();
                    return true;
                }
                collapsePull(true);
                return consumed;
            }
            case MotionEvent.ACTION_CANCEL:
                pulling = false;
                pullArmed = false;
                collapsePull(true);
                return false;
        }
        return false;
    }
    private void openVideo(int position) {
        PlaylistHolder.set(new ArrayList<>(currentVideos), position);
        startActivity(new Intent(this, VlcPlayerActivity.class));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private class BrowserAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final ExecutorService pool = Executors.newFixedThreadPool(2);

        @Override
        public int getItemViewType(int position) {
            return entries.get(position) instanceof DirNode ? 0 : 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == 0) return new FolderVH(inf.inflate(R.layout.item_folder, parent, false));
            return new VideoVH(inf.inflate(R.layout.item_video, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            FontApplier.apply(MainActivity.this, holder.itemView);
            Object item = entries.get(position);
            if (item instanceof DirNode) {
                DirNode node = (DirNode) item;
                FolderVH h = (FolderVH) holder;
                h.name.setText(node.name);
                h.count.setText(node.count() + " 个视频");
                float cp = 0f;
                int n = 0;
                for (VideoItem v : node.videos) {
                    cp += ThumbCache.progress(MainActivity.this, v.uri);
                    n++;
                }
                float avg = n > 0 ? cp / n : 0f;
                if (avg > 0.001f && avg < 0.999f) {
                    h.cacheBar.setVisibility(View.VISIBLE);
                    h.cacheBar.setProgress((int) (avg * 100));
                } else {
                    h.cacheBar.setVisibility(View.GONE);
                }
                h.itemView.setOnClickListener(v -> {
                    currentPath = node.path;
                    refresh();
                });
            } else {
                ((VideoVH) holder).bind((VideoItem) item);
            }
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }

        class FolderVH extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView count;
            final ProgressBar cacheBar;

            FolderVH(@NonNull View v) {
                super(v);
                name = v.findViewById(R.id.folder_name);
                count = v.findViewById(R.id.folder_count);
                cacheBar = v.findViewById(R.id.folder_cache);
            }
        }

        class VideoVH extends RecyclerView.ViewHolder {
            final ImageView thumb;
            final TextView name;
            final TextView duration;
            final ProgressBar bar;
            final ProgressBar cacheBar;

            VideoVH(@NonNull View v) {
                super(v);
                thumb = v.findViewById(R.id.thumb);
                name = v.findViewById(R.id.name);
                duration = v.findViewById(R.id.duration);
                bar = v.findViewById(R.id.progress);
                cacheBar = v.findViewById(R.id.cache_progress);
            }

            void bind(VideoItem video) {
                name.setText(video.name);
                duration.setText(VideoAdapter.formatDuration(video.durationMs));
                thumb.setTag(video.uri.toString());
                thumb.setImageDrawable(null);
                thumb.setBackgroundColor(0xFF12141A);

                long pos = MediaLibrary.position(MainActivity.this, video.uri);
                if (pos > 0 && video.durationMs > 0) {
                    bar.setVisibility(View.VISIBLE);
                    bar.setProgress((int) Math.min(1000, pos * 1000 / video.durationMs));
                } else {
                    bar.setVisibility(View.GONE);
                }

                float cp = ThumbCache.progress(MainActivity.this, video.uri);
                if (cp > 0.001f && cp < 0.999f) {
                    cacheBar.setVisibility(View.VISIBLE);
                    cacheBar.setProgress((int) (cp * 100));
                } else {
                    cacheBar.setVisibility(View.GONE);
                }

                String tag = video.uri.toString();
                pool.execute(() -> {
                    Bitmap bmp = CoverLoader.load(MainActivity.this, video, 360, 204);
                    if (bmp == null) return;
                    main.post(() -> {
                        if (tag.equals(thumb.getTag())) thumb.setImageBitmap(bmp);
                    });
                });

                itemView.setOnClickListener(v -> {
                    int idx = currentVideos.indexOf(video);
                    if (idx >= 0) openVideo(idx);
                });
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        FontApplier.apply(this, findViewById(android.R.id.content));
        if (root != null && adapter != null) refresh();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
