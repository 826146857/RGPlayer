package com.rg.player;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
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
    /** 文件夹导航历史：保存进入子目录前的状态（"" 表示首页），返回时逐级恢复。 */
    private final java.util.ArrayDeque<String> navStack = new java.util.ArrayDeque<>();
    private final List<Object> entries = new ArrayList<>();
    private final List<VideoItem> currentVideos = new ArrayList<>();
    private final List<VideoItem> allVideos = new ArrayList<>();
    private BrowserAdapter adapter;
    private int keyIndex = -1;
    private boolean focusSettings;
    private androidx.media3.exoplayer.ExoPlayer previewPlayer;
    private androidx.media3.ui.PlayerView previewView;
    private android.net.Uri previewUri;
    private android.widget.FrameLayout previewHost;
    private final Handler previewHandler = new Handler(Looper.getMainLooper());
    private Runnable previewTask;
    private int previewPos = -1;
    private View detailsOverlay;
    private long lastBackAt = 0;
    private static final long EXIT_WINDOW = 2000L;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private float pullStartY;
    private boolean pulling;
    private boolean pullRefreshing;
    private boolean pullArmed;
    private boolean pullTextShown;

    private final Runnable cacheTicker = new Runnable() {
        @Override
        public void run() {
            if (list == null) return;
            for (int i = 0; i < list.getChildCount(); i++) {
                View child = list.getChildAt(i);
                int pos = list.getChildAdapterPosition(child);
                if (pos < 0 || pos >= entries.size()) continue;
                RecyclerView.ViewHolder vh = list.getChildViewHolder(child);
                Object o = entries.get(pos);
                if (!(o instanceof VideoItem)) continue;
                VideoItem v = (VideoItem) o;
                ProgressBar pb = vh.itemView.findViewById(R.id.cache_progress);
                if (pb == null) continue;
                float cp = ThumbCache.progress(MainActivity.this, v.uri);
                if (cp > 0.001f && cp < 0.999f) {
                    pb.setVisibility(View.VISIBLE);
                    pb.setProgress((int) (cp * 100), true);
                } else {
                    pb.setVisibility(View.GONE);
                }
            }
            main.removeCallbacks(this);
            main.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        boolean debuggable = (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        String testUri = debuggable && getIntent() != null
                ? getIntent().getStringExtra("test_uri") : null;
        if (testUri != null) {
            startActivity(new Intent(this, VlcPlayerActivity.class).putExtra("test_uri", testUri));
            finish();
            return;
        }
        String gbaRom = debuggable && getIntent() != null
                ? getIntent().getStringExtra("gba_rom") : null;
        if (gbaRom != null) {
            startActivity(new Intent(this, GbaPlayerActivity.class)
                    .putExtra("rom_path", gbaRom)
                    .putExtra("rom_name", new java.io.File(gbaRom).getName()));
            finish();
            return;
        }
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
        btnSettings.setOnClickListener(v -> {
            focusSettings = false;
            btnSettings.setSelected(false);
                startActivity(new Intent(this, SettingsActivity.class));
        });

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
        list.setFocusable(false);
        btnSettings.setFocusable(false);
        btnBack.setFocusable(false);
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
            showSnapshot();
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
        progress.setVisibility(root == null ? View.VISIBLE : View.GONE);
        io.execute(() -> {
            List<VideoItem> videos = MediaLibrary.loadVideos(this, Settings.getHideNoMedia(this));
            saveSnapshot(videos);
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

    private java.io.File snapshotFile() {
        return new java.io.File(getFilesDir(), "library.json");
    }

    private void showSnapshot() {
        if (root != null) return;
        try {
            java.io.File f = snapshotFile();
            if (!f.isFile()) return;
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            org.json.JSONArray arr = new org.json.JSONArray(s);
            List<VideoItem> vs = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                vs.add(new VideoItem(
                        o.optLong("id"),
                        android.net.Uri.parse(o.getString("uri")),
                        o.optString("name"),
                        o.optLong("duration"),
                        o.optLong("size"),
                        o.optString("folder"),
                        o.optString("path"),
                        o.optString("rel"),
                        o.optLong("date"),
                        o.optString("vol")));
            }
            if (vs.isEmpty()) return;
            root = DirNode.build(vs);
            allVideos.clear();
            allVideos.addAll(vs);
            refresh();
            progress.setVisibility(View.GONE);
        } catch (Throwable ignored) {
        }
    }

    private void saveSnapshot(List<VideoItem> videos) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (VideoItem v : videos) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("id", v.id);
                o.put("uri", v.uri.toString());
                o.put("name", v.name);
                o.put("duration", v.durationMs);
                o.put("size", v.size);
                o.put("folder", v.folder);
                o.put("path", v.path);
                o.put("rel", v.relPath);
                o.put("date", v.dateAdded);
                o.put("vol", v.volumeId);
                arr.put(o);
            }
            java.nio.file.Files.write(snapshotFile().toPath(),
                    arr.toString().getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
    }

    private void refresh() {
        entries.clear();
        currentVideos.clear();
        if (root == null) return;

        if (currentPath == null) {
            DirNode start = root;
            while (true) {
                int direct = aliveDirect(start);
                List<DirNode> kids = aliveChildren(start);
                if (direct == 0 && kids.size() == 1) { start = kids.get(0); continue; }
                break;
            }
            showNode(start, true);
            adapter.notifyDataSetChanged();
            empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
            keyIndex = Math.min(keyIndex, Math.max(0, entries.size() - 1));
            list.post(this::updateSelection);
            return;
        } else {
            DirNode node = root.find(currentPath);
            if (node == null) {
                currentPath = null;
                navStack.clear();
                refresh();
                return;
            }
            showNode(node, false);
        }
        adapter.notifyDataSetChanged();
        empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        keyIndex = Math.min(keyIndex, Math.max(0, entries.size() - 1));
        list.post(this::updateSelection);
    }

    private void showNode(DirNode node, boolean rootSingle) {
        String shownName = node.name;
        while (true) {
            int direct = aliveDirect(node);
            List<DirNode> kids = aliveChildren(node);
            if (direct == 0 && kids.size() == 1) { node = kids.get(0); continue; }
            break;
        }
        title.setText(rootSingle ? "视频" : shownName);
        btnBack.setVisibility(rootSingle ? View.GONE : View.VISIBLE);
        btnSettings.setVisibility(rootSingle ? View.VISIBLE : View.GONE);
        List<DirNode> subs = aliveChildren(node);
        subs.sort((x, y) -> NaturalComparator.INSTANCE.compare(x.name, y.name));
        for (DirNode c : subs) entries.add(c);
        List<VideoItem> vids = new ArrayList<>();
        for (VideoItem vi : node.videos) if (alive(vi)) vids.add(vi);
        vids.sort((x, y) -> NaturalComparator.INSTANCE.compare(x.name, y.name));
        currentVideos.addAll(vids);
        entries.addAll(vids);
        subtitle.setText(aliveCount(node) + " 个视频");
        subtitle.setPadding(dp(rootSingle ? 30 : 70), 0, 0, dp(4));
    }

    private int aliveDirect(DirNode n) {
        int c = 0;
        for (VideoItem v : n.videos) if (alive(v)) c++;
        return c;
    }

    private List<DirNode> aliveChildren(DirNode n) {
        List<DirNode> out = new ArrayList<>();
        for (DirNode c : n.children.values()) if (aliveCount(c) > 0) out.add(c);
        return out;
    }

    private DirNode contentNode(DirNode n) {
        DirNode cur = n;
        while (true) {
            if (aliveCount(cur) == 1 && cur.children.isEmpty() && cur.videos.size() == 1
                    && alive(cur.videos.get(0))) break;
            DirNode onlyChild = null;
            int children = 0;
            for (DirNode c : cur.children.values()) {
                if (aliveCount(c) == 0) continue;
                children++;
                onlyChild = c;
            }
            boolean direct = false;
            for (VideoItem v : cur.videos) if (alive(v)) { direct = true; break; }
            if (direct || children != 1) break;
            cur = onlyChild;
        }
        return cur;
    }

    private boolean alive(VideoItem v) {
        return v != null && (v.path == null || new java.io.File(v.path).exists());
    }

    private int aliveCount(DirNode n) {
        int c = 0;
        for (VideoItem v : n.videos) if (alive(v)) c++;
        for (DirNode child : n.children.values()) c += aliveCount(child);
        return c;
    }

    /** 进入子文件夹：把当前状态压栈，返回时才能回到"上一层"，而不是直接回首页。 */
    private void enterDir(DirNode node) {
        if (node == null || node.path == null) return;
        navStack.push(currentPath == null ? "" : currentPath);
        currentPath = node.path;
        refresh();
    }

    private void goUp() {
        if (!navStack.isEmpty()) {
            String prev = navStack.pop();
            currentPath = prev.isEmpty() ? null : prev;
        } else if (currentPath != null) {
            int i = currentPath.lastIndexOf('/');
            currentPath = i < 0 ? null : currentPath.substring(0, i);
        }
        refresh();
    }

    @Override
    public void onBackPressed() {
        if (currentPath != null) {
            lastBackAt = 0;
            goUp();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackAt < EXIT_WINDOW) {
            super.onBackPressed();
        } else {
            lastBackAt = now;
            Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
        }
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
        pullTextShown = false;
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
                hidePreview();
                if (keyIndex >= 0 || focusSettings) {
                    keyIndex = -1;
                    focusSettings = false;
                    if (btnSettings != null) btnSettings.setSelected(false);
                    updateSelection();
                }
                pulling = !rv.canScrollVertically(-1);
                pullStartY = e.getRawY();
                pullArmed = false;
                collapsePull(false);
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (!pulling) return false;
                float dy = e.getRawY() - pullStartY;
                if (dy <= dp(30)) {
                    setZoneHeight(0);
                    hidePullViews();
                    return false;
                }
                int h = (int) Math.min(dp(200), dy * 0.50f);
                setZoneHeight(h);
                pullArrow.setVisibility(View.GONE);
                pullIndicator.setVisibility(View.GONE);
                pullArmed = dy > dp(180);
                if (pullArmed && h < dp(72)) setZoneHeight(dp(72));
                if (pullArmed) {
                    pullText.setText("松手刷新");
                    pullText.setVisibility(View.VISIBLE);
                    pullTextShown = true;
                } else {
                    pullText.setVisibility(View.GONE);
                    pullTextShown = false;
                }
                pullZone.setAlpha(Math.min(1f, dy / dp(60)));
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (!pulling) return false;
                boolean trigger = pullTextShown;
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
    private void openVideoEntry(VideoItem video, View source) {
        hidePreview();
        java.util.ArrayList<VideoItem> pl = new java.util.ArrayList<>();
        int idx = 0;
        for (Object o : entries) {
            if (!(o instanceof VideoItem)) continue;
            if (((VideoItem) o).uri.equals(video.uri)) idx = pl.size();
            pl.add((VideoItem) o);
        }
        if (pl.isEmpty()) return;
        PlaylistHolder.set(pl, Math.max(0, Math.min(idx, pl.size() - 1)));
        Intent intent = new Intent(this, PlayerActivity.class);
        if (source != null && source.getWidth() > 0) {
            try {
                android.app.ActivityOptions opts =
                        android.app.ActivityOptions.makeScaleUpAnimation(
                                source, 0, 0, source.getWidth(), source.getHeight());
                startActivity(intent, opts.toBundle());
                return;
            } catch (Throwable ignored) {
            }
        }
        startActivity(intent);
    }

    private void openVideo(int position, View source) {
        hidePreview();
        PlaylistHolder.set(new ArrayList<>(currentVideos), position);
        Intent intent = new Intent(this, PlayerActivity.class);
        if (source != null && source.getWidth() > 0) {
            try {
                android.app.ActivityOptions opts =
                        android.app.ActivityOptions.makeScaleUpAnimation(
                                source, 0, 0, source.getWidth(), source.getHeight());
                startActivity(intent, opts.toBundle());
                return;
            } catch (Throwable ignored) {
            }
        }
        startActivity(intent);
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
                h.itemView.setFocusable(false);
                h.itemView.setSelected(holder.getBindingAdapterPosition() == keyIndex);
                h.itemView.setOnClickListener(v -> enterDir(node));
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
                    cacheBar.setProgress((int) (cp * 100), true);
                } else {
                    cacheBar.setVisibility(View.GONE);
                }

                String tag = video.uri.toString();
                pool.execute(() -> {
                    Bitmap bmp = CoverLoader.load(MainActivity.this, video, 360, 360);
                    if (bmp == null) return;
                    main.post(() -> {
                        if (tag.equals(thumb.getTag())) thumb.setImageBitmap(bmp);
                    });
                });

                itemView.setFocusable(false);
                itemView.setSelected(getBindingAdapterPosition() == keyIndex);
                View nameView = itemView.findViewById(R.id.name);
                if (nameView instanceof MarqueeTextView) {
                    ((MarqueeTextView) nameView).setMarqueeEnabled(
                            getBindingAdapterPosition() == previewPos);
                }
                itemView.setOnClickListener(v -> openVideoEntry(video, itemView));
            }
        }
    }

    private final android.content.BroadcastReceiver f10Receiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            if (GbaNav.blocked()) return;
            GbaNav.lock(900);
            startActivity(new Intent(MainActivity.this, GbaListActivity.class));
        }
    };

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 手柄上的 F10（retrogame_joypad scanCode 68）：打开 GBA 影片
        if (event.getRepeatCount() == 0
                && (event.getScanCode() == 68 || keyCode == KeyEvent.KEYCODE_F10)) {
            if (GbaNav.blocked()) return true;
            GbaNav.lock(900);
            startActivity(new Intent(this, GbaListActivity.class));
            return true;
        }
        if (detailsOverlay != null
                && keyCode != KeyEvent.KEYCODE_BUTTON_B
                && keyCode != KeyEvent.KEYCODE_BACK) {
            return true;
        }
        if (focusSettings) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                setSettingsFocused(false);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK) {
                setSettingsFocused(false);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                int span = gridSpan();
                int wantCol = Math.max(0, span - 2);
                int videos = 0;
                int target = -1;
                for (int i = 0; i < entries.size(); i++) {
                    if (!(entries.get(i) instanceof VideoItem)) continue;
                    if (i == wantCol) { target = i; break; }
                    videos++;
                }
                if (target < 0) {
                    for (int i = 0; i < entries.size(); i++) {
                        if (entries.get(i) instanceof VideoItem) target = i;
                    }
                }
                if (target >= 0) {
                    setSettingsFocused(false);
                    keyIndex = target;
                    updateSelection();
                    scrollToSelected();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return true;
            }
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && (keyIndex < 0 || keyIndex < gridSpan())) {
            if (!entries.isEmpty()) {
                setSettingsFocused(true);
                return true;
            }
        }
        if (keyIndex >= 0 && !entries.isEmpty()) {
            int span = gridSpan();
            int row = keyIndex / span;
            int lastRow = (entries.size() - 1) / span;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (row < lastRow) {
                    int target = keyIndex + span;
                    if (target >= entries.size()) target = entries.size() - 1;
                    keyIndex = target;
                    updateSelection();
                    scrollToSelected();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                int target = keyIndex - span;
                if (target >= 0) {
                    keyIndex = target;
                    updateSelection();
                    scrollToSelected();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (keyIndex > 0) {
                    keyIndex--;
                    updateSelection();
                    scrollToSelected();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int next = keyIndex + 1;
                if (next < entries.size()) {
                    keyIndex = next;
                    updateSelection();
                    scrollToSelected();
                    return true;
                }
                int col = keyIndex % span;
                if (col < span - 1) {
                    int target = keyIndex - span + 1;
                    if (target >= 0) {
                        keyIndex = target;
                        updateSelection();
                        scrollToSelected();
                        return true;
                    }
                }
                if (row == 0) setSettingsFocused(true);
                return true;
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                activateFocused();
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                if (detailsOverlay != null) {
                    dismissDetails();
                    return true;
                }
                onBackPressed();
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                showDetailsFocused();
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveSelection(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveSelection(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                moveSelection(-3);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveSelection(3);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void scrollToSelected() {
        if (list == null || keyIndex < 0) return;
        list.smoothScrollToPosition(keyIndex);
    }

    private int gridSpan() {
        if (list != null && list.getLayoutManager() instanceof androidx.recyclerview.widget.GridLayoutManager) {
            return ((androidx.recyclerview.widget.GridLayoutManager) list.getLayoutManager()).getSpanCount();
        }
        return 3;
    }

    private void setSettingsFocused(boolean focused) {
        focusSettings = focused;
        btnSettings.setSelected(focused);
        if (!focused && keyIndex < 0) {
            keyIndex = 0;
        }
        if (list != null) list.post(this::updateSelection);
        if (focused) hidePreview();
    }

    private void moveSelection(int delta) {
        if (focusSettings) setSettingsFocused(false);
        if (entries.isEmpty()) return;
        int next = keyIndex + delta;
        if (next < 0) next = 0;
        if (next >= entries.size()) next = entries.size() - 1;
        keyIndex = next;
        list.scrollToPosition(keyIndex);
        list.post(this::updateSelection);
    }

    private void schedulePreview() {
        if (focusSettings) return;
        if (keyIndex < 0) return;
        if (previewPos == keyIndex && previewPlayer != null) return;
        if (previewTask != null) previewHandler.removeCallbacks(previewTask);
        int pos = keyIndex;
        previewTask = () -> {
            if (!isFinishing() && !isDestroyed() && pos == keyIndex) showPreview(pos);
        };
        previewHandler.postDelayed(previewTask, 700);
    }

    private void hidePreview() {
        if (previewTask != null) {
            previewHandler.removeCallbacks(previewTask);
            previewTask = null;
        }
        previewPos = -1;
        if (previewPlayer != null) {
            try {
                previewPlayer.stop();
                previewPlayer.release();
            } catch (Throwable ignored) {
            }
            previewPlayer = null;
        }
        if (previewHost != null) {
            android.view.ViewParent p = previewHost.getParent();
            if (p instanceof android.view.ViewGroup) ((android.view.ViewGroup) p).removeView(previewHost);
            previewHost = null;
        }
        previewView = null;
        setMarqueePosition(-1, false);
    }

    private void applyPreviewCrop(android.net.Uri uri) {
        new Thread(() -> {
            float top = 0f, bottom = 0f;
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            try {
                r.setDataSource(this, uri);
                android.graphics.Bitmap bmp = r.getFrameAtTime(1_000_000,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (bmp != null) {
                    int w = bmp.getWidth(), h = bmp.getHeight();
                    int[] row = new int[w];
                    int topRows = 0, bottomRows = 0;
                    for (int y = 0; y < h / 3; y++) {
                        bmp.getPixels(row, 0, w, 0, y, w, 1);
                        if (isDarkRow(row)) topRows++; else break;
                    }
                    for (int y = h - 1; y >= h * 2 / 3; y--) {
                        bmp.getPixels(row, 0, w, 0, y, w, 1);
                        if (isDarkRow(row)) bottomRows++; else break;
                    }
                    top = topRows / (float) h;
                    bottom = bottomRows / (float) h;
                    bmp.recycle();
                }
            } catch (Throwable ignored) {
            } finally {
                try { r.release(); } catch (Throwable ignored) {}
            }
            final float ft = top, fb = bottom;
            runOnUiThread(() -> setPreviewCrop(uri, ft, fb));
        }).start();
    }

    private static boolean isDarkRow(int[] row) {
        long sum = 0;
        for (int i = 0; i < row.length; i += 4) {
            int c = row[i];
            sum += ((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF);
        }
        int n = Math.max(1, row.length / 4);
        return sum / (n * 3) < 22;
    }

    private void setPreviewCrop(android.net.Uri uri, float top, float bottom) {
        if (previewView == null || previewPlayer == null || !uri.equals(previewUri)) return;
        float visible = 1f - top - bottom;
        if (visible <= 0.5f) visible = 0.85f;
        float scale = Math.min(1.6f, 1f / visible);
        View surface = previewView.getVideoSurfaceView();
        if (surface instanceof android.view.TextureView) {
            android.view.TextureView tv = (android.view.TextureView) surface;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(scale, scale, tv.getWidth() / 2f, tv.getHeight() / 2f);
            tv.setTransform(m);
        }
    }

    private void setMarqueePosition(int pos, boolean enabled) {
        if (list == null) return;
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            int p = list.getChildAdapterPosition(c);
            View name = c.findViewById(R.id.name);
            if (name instanceof MarqueeTextView) {
                ((MarqueeTextView) name).setMarqueeEnabled(enabled && p == pos);
            }
        }
    }

    private void showPreview(int pos) {
        if (pos < 0 || pos >= entries.size()) return;
        Object o = entries.get(pos);
        if (!(o instanceof VideoItem)) return;
        VideoItem v = (VideoItem) o;
        androidx.recyclerview.widget.LinearLayoutManager lm =
                (androidx.recyclerview.widget.LinearLayoutManager) list.getLayoutManager();
        if (lm == null) return;
        View card = lm.findViewByPosition(pos);
        if (card == null || card.getWidth() == 0) return;
        View thumb = card.findViewById(R.id.thumb);
        if (thumb == null) thumb = card;
        android.widget.FrameLayout content = findViewById(android.R.id.content);
        if (previewHost == null) {
            previewHost = new android.widget.FrameLayout(this);
            previewHost.setClickable(false);
            previewHost.setFocusable(false);
            previewHost.setOnTouchListener((view, e) -> false);
            previewHost.setElevation(dp(24));
            previewHost.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
                }
            });
            previewHost.setClipToOutline(true);
            content.addView(previewHost, new android.widget.FrameLayout.LayoutParams(
                    thumb.getWidth(), thumb.getHeight()));
        }
        int[] c = new int[2], r = new int[2];
        thumb.getLocationInWindow(c);
        content.getLocationInWindow(r);
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) previewHost.getLayoutParams();
        lp.width = thumb.getWidth();
        lp.height = thumb.getHeight();
        lp.leftMargin = c[0] - r[0];
        lp.topMargin = c[1] - r[1];
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        previewHost.setLayoutParams(lp);
        if (previewPlayer == null) {
            previewPlayer = new androidx.media3.exoplayer.ExoPlayer.Builder(this).build();
            previewPlayer.setVolume(0f);
            previewPlayer.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_ONE);
            previewView = (androidx.media3.ui.PlayerView)
                    android.view.LayoutInflater.from(this)
                            .inflate(R.layout.view_preview, previewHost, false);
            previewView.setShutterBackgroundColor(android.graphics.Color.BLACK);
            previewView.setClickable(false);
            previewView.setFocusable(false);
            previewView.setPlayer(previewPlayer);
            previewHost.addView(previewView, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        }
        setMarqueePosition(pos, true);
        previewHost.bringToFront();
        previewPos = pos;
        try {
            previewPlayer.stop();
            previewPlayer.clearMediaItems();
        } catch (Throwable ignored) {
        }
        previewPlayer.setMediaItem(androidx.media3.common.MediaItem.fromUri(v.uri));
        previewPlayer.prepare();
        previewPlayer.play();
        previewUri = v.uri;
        applyPreviewCrop(v.uri);
    }

    private void updateSelection() {
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            int pos = list.getChildAdapterPosition(c);
            c.setSelected(!focusSettings && pos == keyIndex);
        }
        if (focusSettings) {
            hidePreview();
            return;
        }
        if (keyIndex < 0) {
            hidePreview();
            return;
        }
        if (previewPos != -1 && previewPos != keyIndex) hidePreview();
        schedulePreview();
    }

    private void showDetailsOverlay(String text) {
        hidePreview();
        dismissDetails();
        int pad = dp(18);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(0xFFE6E9EF);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setLineSpacing(2f, 1.15f);
        tv.setPadding(pad, pad, pad, pad);
        TapDismissScrollView sc = new TapDismissScrollView(new android.view.ContextThemeWrapper(this, R.style.RGScroll), this::dismissDetails);
        sc.addView(tv);

        android.widget.FrameLayout card = new android.widget.FrameLayout(this);
        card.setBackgroundResource(R.drawable.bg_info);
        card.setClipToOutline(true);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.8f);
        int textMax = 0;
        for (String line : text.split("\n")) {
            textMax = Math.max(textMax, (int) Math.ceil(tv.getPaint().measureText(line)));
        }
        int cardW = Math.max((int) (screenW * 0.5f),
                Math.min((int) (screenW * 0.92f), textMax + 2 * pad));
        tv.measure(View.MeasureSpec.makeMeasureSpec(cardW - 2 * pad, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int cardH = Math.min(tv.getMeasuredHeight(), maxH);
        android.widget.FrameLayout.LayoutParams clp =
                new android.widget.FrameLayout.LayoutParams(cardW, cardH);
        clp.gravity = android.view.Gravity.CENTER;
        card.addView(sc, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.widget.FrameLayout overlay = new android.widget.FrameLayout(this);
        overlay.setBackgroundColor(0x99000000);
        overlay.setClickable(true);
        overlay.addView(card, clp);
        android.view.View.OnTouchListener tapOut = (v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_UP) dismissDetails();
            return true;
        };
        overlay.setOnTouchListener(tapOut);
        card.setOnTouchListener(tapOut);
        ViewGroup root = findViewById(android.R.id.content);
        root.addView(overlay, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        detailsOverlay = overlay;
    }

    private void dismissDetails() {
        if (detailsOverlay != null) {
            ViewGroup p = (ViewGroup) detailsOverlay.getParent();
            if (p != null) p.removeView(detailsOverlay);
            detailsOverlay = null;
        }
    }

    private int focusedPosition() {
        if (keyIndex < 0) return -1;
        View f = list.getFocusedChild();
        if (f != null) {
            int p = list.getChildAdapterPosition(f);
            if (p >= 0) return p;
        }
        return keyIndex;
    }

    private void activateFocused() {
        int pos = focusedPosition();
        if (pos < 0 || pos >= entries.size()) return;
        Object o = entries.get(pos);
        if (o instanceof DirNode) {
            enterDir((DirNode) o);
        } else if (o instanceof VideoItem) {
            View src = list.getLayoutManager() == null ? null
                    : list.getLayoutManager().findViewByPosition(pos);
            openVideoEntry((VideoItem) o, src);
        }
    }

    private void showDetailsFocused() {
        int pos = focusedPosition();
        if (pos < 0 || pos >= entries.size()) return;
        Object o = entries.get(pos);
        if (!(o instanceof VideoItem)) return;
        final VideoItem v = (VideoItem) o;
        new Thread(() -> {
            String res = "";
            try {
                android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
                r.setDataSource(MainActivity.this, v.uri);
                String w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                r.release();
                if (w != null && h != null) res = w + " × " + h;
            } catch (Exception ignored) {
            }
            final String resolution = res;
            runOnUiThread(() -> {
                String ext = "";
                int dot = v.name.lastIndexOf('.');
                if (dot >= 0 && dot < v.name.length() - 1) ext = v.name.substring(dot + 1).toUpperCase();
                String date = "";
                try {
                    date = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                            .format(new java.util.Date(v.dateAdded * 1000L));
                } catch (Exception ignored) {
                }
                String size = v.size >= 1024L * 1024L
                        ? String.format(java.util.Locale.CHINA, "%.1f MB", v.size / 1048576.0)
                        : String.format(java.util.Locale.CHINA, "%.0f KB", v.size / 1024.0);
                StringBuilder sb = new StringBuilder();
                sb.append("文件：").append(v.name).append('\n');
                sb.append("格式：").append(ext.isEmpty() ? "未知" : ext).append('\n');
                if (!resolution.isEmpty()) sb.append("分辨率：").append(resolution).append('\n');
                sb.append("大小：").append(size).append('\n');
                sb.append("时长：").append(VideoAdapter.formatDuration(v.durationMs)).append('\n');
                if (!date.isEmpty()) sb.append("添加：").append(date).append('\n');
                sb.append("路径：").append(v.path);
                showDetailsOverlay(sb.toString());
            });
        }).start();
    }

    @Override
    protected void onPause() {
        hidePreview();
        try {
            unregisterReceiver(f10Receiver);
        } catch (Exception ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            android.content.IntentFilter f = new android.content.IntentFilter("com.anbernic.f10");
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(f10Receiver, f, android.content.Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(f10Receiver, f);
            }
        } catch (Exception ignored) {
        }
        FontApplier.apply(this, findViewById(android.R.id.content));
        if (root != null && adapter != null) refresh();
        if (list != null) {
            list.postDelayed(this::updateSelection, 400);
            list.postDelayed(this::schedulePreview, 700);
        }
    }

    @Override
    protected void onDestroy() {
        hidePreview();
        io.shutdownNow();
        super.onDestroy();
    }
}
