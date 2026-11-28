package com.rg.player;

import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 扫描并列出 .gba 电影卡 ROM（MediaStore + 文件系统增量扫描）。 */
public class GbaListActivity extends AppCompatActivity {

    private final List<Item> items = new ArrayList<>();
    private final List<Item> allItems = new ArrayList<>();
    private boolean showAll;
    private int gbaIndex = -1;
    private RecyclerView list;
    private boolean focusAll;
    private boolean selectFirstOnRebuild;
    private final java.util.ArrayDeque<Integer> folderStack = new java.util.ArrayDeque<>();
    private int restoreIndexOnRebuild = -1;
    private TextView btnAll;
    private final Set<String> seen = new HashSet<>();
    private Adapter adapter;
    private TextView empty;
    private boolean firstResume = true;
    private boolean skipNextResumeRefresh;
    private volatile boolean scanning;
    private String currentDir;
    private boolean singleRootMode;
    private final List<String> scanRoots = new ArrayList<>();
    private java.util.concurrent.ExecutorService previewExec;
    private volatile boolean previewEnabled = true;
    private final Object coreLock = new Object();
    private final java.util.Set<String> previewQueued = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private android.view.View pullZone;
    private android.widget.ProgressBar pullProgress;
    private android.widget.TextView pullText;
    private float pullStartY;
    private boolean pulling, pullArmed, pullTextShown;
    private android.animation.ValueAnimator pullAnim;

    private static class Item {
        String name;
        String path;
        boolean video;
        boolean dir;
        int count;
    }

    private final android.content.BroadcastReceiver f10 = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, android.content.Intent i) {
            if (GbaNav.blocked()) return;
            GbaNav.lock(700);
            finish();
        }
    };

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (focusAll) {
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                focusAll = false;
                btnAll.setSelected(false);
                gbaIndex = 0;
                updateGbaSelection(true);
                return true;
            }
            if (keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A
                    || keyCode == android.view.KeyEvent.KEYCODE_ENTER
                    || keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER) {
                focusAll = false;
                btnAll.setSelected(false);
                btnAll.performClick();
                return true;
            }
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
                    || keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                return true;
            }
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && gbaIndex <= 0 && !items.isEmpty()) {
            focusAll = true;
            btnAll.setSelected(true);
            for (int i = 0; i < list.getChildCount(); i++) list.getChildAt(i).setSelected(false);
            return true;
        }
        if (!items.isEmpty()) {
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    || keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                int delta = keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
                if (gbaIndex < 0) gbaIndex = 0;
                else gbaIndex = Math.max(0, Math.min(items.size() - 1, gbaIndex + delta));
                updateGbaSelection(true);
                return true;
            }
            if (keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A
                    || keyCode == android.view.KeyEvent.KEYCODE_ENTER
                    || keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER) {
                if (gbaIndex < 0) gbaIndex = 0;
                if (gbaIndex < items.size()) {
                    Item it = items.get(gbaIndex);
                    if (it.dir) {
                        if (gbaIndex >= 0) folderStack.push(gbaIndex);
                        focusAll = false;
                        btnAll.setSelected(false);
                        selectFirstOnRebuild = true;
                        currentDir = it.path;
                        rebuild();
                    }
                    else open(it);
                }
                return true;
            }
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (currentDir != null) {
                if (singleRootMode && collapsedRoots().contains(currentDir)) {
                    finish();
                    return true;
                }
                goUp();
                return true;
            }
            if (isTaskRoot()) {
                startActivity(new android.content.Intent(this, MainActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
            }
            finish();
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_HOME) {
            if (isTaskRoot()) {
                startActivity(new android.content.Intent(this, MainActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
            }
            finish();
            return true;
        }
        if (event.getScanCode() == 68 || keyCode == android.view.KeyEvent.KEYCODE_F10) {
            if (GbaNav.blocked()) return true;
            GbaNav.lock(700);
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            android.content.IntentFilter f = new android.content.IntentFilter("com.anbernic.f10");
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(f10, f, android.content.Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(f10, f);
            }
        } catch (Exception ignored) {
        }
        previewEnabled = true;
        if (firstResume) {
            firstResume = false;
        } else if (skipNextResumeRefresh) {
            skipNextResumeRefresh = false;
        } else {
            refresh(false);
        }
        schedulePreviews();
    }

    private void refresh(boolean fromPull) {
        if (scanning) {
            collapsePull(true);
            return;
        }
        allItems.clear();
        synchronized (seen) {
            seen.clear();
        }
        if (!fromPull) {
            items.clear();
            if (adapter != null) adapter.notifyDataSetChanged();
            empty.setText("正在刷新…");
            empty.setVisibility(View.VISIBLE);
        }
        scan();
    }

    @Override
    protected void onPause() {
        previewEnabled = false;
        try {
            unregisterReceiver(f10);
        } catch (Exception ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gba_list);
        empty = findViewById(R.id.gba_empty);
        btnAll = findViewById(R.id.btn_all_rom);
        btnAll.setBackgroundResource(R.drawable.bg_row_focus);
        btnAll.setOnClickListener(v -> {
            showAll = !showAll;
            btnAll.setText(showAll ? "返回" : "找不到想要的影片？");
            focusAll = true;
            gbaIndex = -1;
            currentDir = null;
            singleRootMode = false;
            folderStack.clear();
            btnAll.setSelected(true);
            rebuild();
        });
        pullZone = findViewById(R.id.gba_pull_zone);
        pullProgress = findViewById(R.id.gba_pull_progress);
        pullText = findViewById(R.id.gba_pull_text);
        collapsePull(false);
        list = findViewById(R.id.gba_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                gbaIndex = -1;
                for (int i = 0; i < list.getChildCount(); i++) {
                    list.getChildAt(i).setSelected(false);
                }
            }
            return false;
        });
        list.addOnItemTouchListener(new androidx.recyclerview.widget.RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull androidx.recyclerview.widget.RecyclerView rv,
                                                 @NonNull android.view.MotionEvent e) {
                return handlePull(rv, e);
            }
            @Override
            public void onTouchEvent(@NonNull androidx.recyclerview.widget.RecyclerView rv,
                                     @NonNull android.view.MotionEvent e) {
                handlePull(rv, e);
            }
        });
        adapter = new Adapter();
        list.setAdapter(adapter);
        previewExec = java.util.concurrent.Executors.newSingleThreadExecutor();
        loadCache();
        empty.setText("正在扫描 .gba 文件…");
        empty.setVisibility(View.VISIBLE);
        scan();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void setZoneHeight(int px) {
        if (pullZone == null) return;
        android.view.ViewGroup.LayoutParams lp = pullZone.getLayoutParams();
        if (lp.height != px) { lp.height = px; pullZone.setLayoutParams(lp); }
    }

    private void collapsePull(boolean animate) {
        pullTextShown = false;
        if (pullProgress != null) pullProgress.setVisibility(android.view.View.GONE);
        if (pullText != null) pullText.setVisibility(android.view.View.GONE);
        if (pullAnim != null) pullAnim.cancel();
        if (pullZone == null) return;
        int from = pullZone.getHeight();
        if (!animate) { setZoneHeight(0); return; }
        pullAnim = android.animation.ValueAnimator.ofInt(from, 0);
        pullAnim.setDuration(180);
        pullAnim.addUpdateListener(a -> setZoneHeight((int) a.getAnimatedValue()));
        pullAnim.start();
    }

    private boolean handlePull(androidx.recyclerview.widget.RecyclerView rv, android.view.MotionEvent e) {
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                pulling = !rv.canScrollVertically(-1);
                pullStartY = e.getRawY();
                pullArmed = false;
                collapsePull(false);
                return false;
            case android.view.MotionEvent.ACTION_MOVE: {
                if (!pulling) return false;
                float dy = e.getRawY() - pullStartY;
                if (dy <= dp(30)) { setZoneHeight(0); return false; }
                int h = (int) Math.min(dp(220), dy * 0.42f);
                setZoneHeight(h);
                pullProgress.setVisibility(android.view.View.GONE);
                pullArmed = dy > dp(240);
                if (pullArmed && h < dp(56)) setZoneHeight(dp(56));
                if (pullArmed) {
                    pullText.setText("松手刷新");
                    pullText.setVisibility(android.view.View.VISIBLE);
                    pullTextShown = true;
                } else {
                    pullText.setVisibility(android.view.View.GONE);
                    pullTextShown = false;
                }
                pullZone.setAlpha(Math.min(1f, dy / dp(60)));
                return true;
            }
            case android.view.MotionEvent.ACTION_UP: {
                if (!pulling) return false;
                boolean trigger = pullTextShown;
                boolean consumed = trigger || pullZone.getHeight() > 0;
                pulling = false;
                pullArmed = false;
                if (trigger) {
                    pullText.setText("刷新中…");
                    pullText.setVisibility(android.view.View.VISIBLE);
                    pullProgress.setVisibility(android.view.View.VISIBLE);
                    pullAnim = android.animation.ValueAnimator.ofInt(pullZone.getHeight(), dp(56));
                    pullAnim.setDuration(180);
                    pullAnim.addUpdateListener(a -> setZoneHeight((int) a.getAnimatedValue()));
                    pullAnim.start();
                    refresh(true);
                    return true;
                }
                collapsePull(true);
                return consumed;
            }
        }
        return false;
    }

    private void scan() {
        scanning = true;
        final boolean hideNoMedia = Settings.getHideNoMedia(this);
        final String mediaDir = Settings.getMediaDir(this);
        final String mediaVol = Settings.getMediaVolume(this);
        new Thread(() -> {
            List<File> roots = new ArrayList<>();
            if (!mediaDir.isEmpty()) {
                // 设置了自定义媒体目录：只扫这个目录（和普通视频一致）
                File emu = new File(android.os.Environment.getExternalStorageDirectory(), mediaDir);
                if (emu.isDirectory()) roots.add(emu);
                if (!mediaVol.isEmpty()) {
                    File sd = new File("/storage/" + mediaVol, mediaDir);
                    if (sd.isDirectory()) roots.add(sd);
                }
            }
            if (roots.isEmpty()) {
                roots.add(android.os.Environment.getExternalStorageDirectory());
                File[] vols = new File("/storage").listFiles();
                if (vols != null) {
                    for (File v : vols) if (v.isDirectory()) roots.add(v);
                }
            }
            int maxDepth = mediaDir.isEmpty() ? 5 : 12;
            Set<String> rootSeen = new HashSet<>();
            List<String> foundRoots = new ArrayList<>();
            for (File root : roots) {
                String canon;
                try {
                    canon = root.getCanonicalPath();
                } catch (Exception e) {
                    canon = root.getAbsolutePath();
                }
                if (!rootSeen.add(canon)) continue;
                foundRoots.add(canon);
                walk(root, 0, maxDepth, hideNoMedia);
            }

            runOnUiThread(() -> {
                scanning = false;
                new android.os.Handler(getMainLooper()).postDelayed(
                        () -> collapsePull(true), 450);
                java.util.Iterator<Item> itr = allItems.iterator();
                while (itr.hasNext()) {
                    Item one = itr.next();
                    if (!new java.io.File(one.path).exists()) itr.remove();
                }
                saveCache();
                scanRoots.clear();
                scanRoots.addAll(foundRoots);
                rebuild();
            });
        }).start();
    }

    private void walk(File dir, int depth, int maxDepth, boolean hideNoMedia) {
        if (depth > maxDepth) return;
        // 与视频库一致：隐藏含 .nomedia 的目录
        if (hideNoMedia && new File(dir, ".nomedia").exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            if (f.isDirectory()) {
                if (n.startsWith(".") || n.equals("Android") || n.equals("obb")
                        || n.equalsIgnoreCase("DCIM") || n.equalsIgnoreCase("Pictures")
                        || n.equalsIgnoreCase("Music")) continue;
                walk(f, depth + 1, maxDepth, hideNoMedia);
            } else if (n.toLowerCase(Locale.US).endsWith(".gba")) {
                addItem(f);
            }
        }
    }

    private java.io.File cacheFile() {
        return new java.io.File(getFilesDir(), "gba_cache.json");
    }

    private void loadCache() {
        try {
            java.io.File f = cacheFile();
            if (!f.isFile()) return;
            String raw = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                String path = o.optString("path");
                if (path.isEmpty() || !new java.io.File(path).exists()) continue;
                Item it = new Item();
                it.name = o.optString("name");
                it.path = path;
                it.dir = o.optBoolean("dir");
                it.video = GbaRomDetector.isVideo(this, new java.io.File(path));
                it.count = o.optInt("count");
                allItems.add(it);
                synchronized (seen) { seen.add(path); }
            }
            if (!allItems.isEmpty()) {
                rebuild();
                empty.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {
        }
    }

    private void saveCache() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            synchronized (seen) {
                for (Item it : allItems) {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("name", it.name);
                    o.put("path", it.path);
                    o.put("dir", it.dir);
                    o.put("video", it.video);
                    o.put("count", it.count);
                    arr.put(o);
                }
            }
            java.io.File f = cacheFile();
            java.nio.file.Files.write(f.toPath(), arr.toString().getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
    }

    private void addItem(File f) {
        String key;
        try {
            key = f.getCanonicalPath();
        } catch (Exception e) {
            key = f.getAbsolutePath();
        }
        synchronized (seen) {
            if (!seen.add(key)) return;
        }
        Item it = new Item();
        it.name = f.getName();
        it.path = key;
        it.video = GbaRomDetector.isVideo(this, f);
        runOnUiThread(() -> allItems.add(it));
    }

    private int directCount(String dir) {
        int n = 0;
        for (Item it : allItems) {
            if (!showAll && !it.video) continue;
            File p = new File(it.path).getParentFile();
            if (p != null && p.getAbsolutePath().equals(dir)) n++;
        }
        return n;
    }

    private List<String> childContentDirs(String dir) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (Item it : allItems) {
            if (!showAll && !it.video) continue;
            if (!it.path.startsWith(dir + "/")) continue;
            String rel = it.path.substring(dir.length() + 1);
            int slash = rel.indexOf('/');
            if (slash > 0) set.add(dir + "/" + rel.substring(0, slash));
        }
        return new ArrayList<>(set);
    }

    private List<String> collapsedRoots() {
        List<String> out = new ArrayList<>();
        for (String root : scanRoots) {
            if (countUnder(root) == 0) continue;
            String node = root;
            while (true) {
                List<String> kids = childContentDirs(node);
                if (directCount(node) == 0 && kids.size() == 1) {
                    node = kids.get(0);
                } else {
                    break;
                }
            }
            out.add(node);
        }
        return out;
    }

    private int countUnder(String dir) {
        int n = 0;
        for (Item it : allItems) {
            if (!showAll && !it.video) continue;
            if (it.path.startsWith(dir + "/")) n++;
        }
        return n;
    }

    private void rebuild() {
        items.clear();
        List<String> content = collapsedRoots();
        if (currentDir == null) {
            if (content.size() == 1) {
                currentDir = content.get(0);
                singleRootMode = true;
            } else {
                singleRootMode = false;
                List<Item> dirs = new ArrayList<>();
                for (String d : content) {
                    Item it = new Item();
                    it.name = dirLabel(d);
                    it.path = d;
                    it.dir = true;
                    it.count = countUnder(d);
                    dirs.add(it);
                }
                dirs.sort((a, b) -> NaturalComparator.INSTANCE.compare(a.name, b.name));
                items.addAll(dirs);
                if (adapter != null) adapter.notifyDataSetChanged();
                updateEmpty();
                return;
            }
        }
        while (directCount(currentDir) == 0) {
            List<String> only = childContentDirs(currentDir);
            if (only.size() != 1) break;
            currentDir = only.get(0);
        }
        java.util.TreeMap<String, Integer> subs = new java.util.TreeMap<>();
        for (Item it : allItems) {
            if (!showAll && !it.video) continue;
            if (!it.path.startsWith(currentDir + "/")) continue;
            String rel = it.path.substring(currentDir.length() + 1);
            int slash = rel.indexOf('/');
            if (slash > 0) {
                subs.merge(rel.substring(0, slash), 1, Integer::sum);
            } else {
                items.add(it);
            }
        }
        List<Item> dirs = new ArrayList<>();
        for (java.util.Map.Entry<String, Integer> e : subs.entrySet()) {
            Item d = new Item();
            d.name = e.getKey();
            d.path = currentDir + "/" + e.getKey();
            d.dir = true;
            d.count = e.getValue();
            dirs.add(d);
        }
        dirs.sort((a, b) -> NaturalComparator.INSTANCE.compare(a.name, b.name));
        List<Item> vids = new ArrayList<>(items);
        vids.sort((a, b) -> NaturalComparator.INSTANCE.compare(a.name, b.name));
        items.clear();
        items.addAll(dirs);
        items.addAll(vids);
        if (selectFirstOnRebuild) {
            selectFirstOnRebuild = false;
            gbaIndex = items.isEmpty() ? -1 : 0;
        } else if (restoreIndexOnRebuild >= 0) {
            gbaIndex = items.isEmpty() ? -1 : Math.min(restoreIndexOnRebuild, items.size() - 1);
            restoreIndexOnRebuild = -1;
        } else if (gbaIndex >= items.size()) {
            gbaIndex = items.isEmpty() ? -1 : items.size() - 1;
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        updateEmpty();
        updateGbaSelection(true);
        schedulePreviews();
    }

    private static final String[] COVER_EXTS = {".jpg", ".jpeg", ".png", ".webp"};

    private String displayName(String name) {
        String n = name;
        for (String pre : new String[]{"GBA电影-", "GBA视频-", "GBA电影：", "GBA电影:", "GBA "}) {
            if (n.startsWith(pre)) {
                n = n.substring(pre.length());
                break;
            }
        }
        return n;
    }

    private File findCoverFile(Item it) {
        File file = new File(it.path);
        File dir = file.getParentFile();
        if (dir == null) return null;
        String base = file.getName();
        if (base.toLowerCase(java.util.Locale.US).endsWith(".gba")) {
            base = base.substring(0, base.length() - 4);
        }
        String shortBase = displayName(base);
        String[] bases = shortBase.equals(base) ? new String[]{base} : new String[]{base, shortBase};
        for (String b : bases) {
            for (String ext : COVER_EXTS) {
                File f = new File(dir, b + ext);
                if (f.isFile()) return f;
            }
        }
        for (String b : bases) {
            for (String suffix : new String[]{"-poster", ".poster", "-cover"}) {
                for (String ext : COVER_EXTS) {
                    File f = new File(dir, b + suffix + ext);
                    if (f.isFile()) return f;
                }
            }
        }
        for (String n : new String[]{"poster", "folder", "cover"}) {
            for (String ext : COVER_EXTS) {
                File f = new File(dir, n + ext);
                if (f.isFile()) return f;
            }
        }
        return null;
    }

    private File previewFile(Item it) {
        try {
            File f = new File(it.path);
            String key = it.path + "|" + f.length() + "|" + f.lastModified();
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(java.util.Locale.US, "%02x", b));
            return new File(new File(getCacheDir(), "gba_previews"), sb + ".jpg");
        } catch (Throwable e) {
            return null;
        }
    }

    private void schedulePreviews() {
        if (previewExec == null || adapter == null) return;
        for (Item it : new ArrayList<>(allItems)) {
            if (it.dir) continue;
            if (!showAll && !it.video) continue;
            File pf = previewFile(it);
            if (pf == null || pf.exists()) continue;
            if (!previewQueued.add(it.path)) continue;
            previewExec.execute(() -> generatePreview(it, pf));
        }
    }

    private void generatePreview(Item it, File out) {
        synchronized (coreLock) {
            if (!previewEnabled) return;
            GbaCore core = null;
            try {
                File base = new File(getFilesDir(), "gba");
                File system = new File(base, "system");
                File saves = new File(base, "saves");
                system.mkdirs();
                saves.mkdirs();
                String corePath = new File(getApplicationInfo().nativeLibraryDir,
                        "libmgba_core.so").getAbsolutePath();
                core = GbaCore.create(corePath, it.path, system.getAbsolutePath(),
                        saves.getAbsolutePath());
                if (core == null) return;
                for (int i = 0; i < 900; i++) {
                    if (!previewEnabled) return;
                    core.run();
                }
                int w = core.width(), h = core.height();
                if (w <= 0 || h <= 0) return;
                int[] px = new int[w * h];
                if (core.video(px) <= 0) return;
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                        w, h, android.graphics.Bitmap.Config.ARGB_8888);
                bmp.setPixels(px, 0, w, 0, 0, w, h);
                File dir = out.getParentFile();
                if (dir != null) dir.mkdirs();
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos);
                }
                bmp.recycle();
                runOnUiThread(() -> {
                    int idx = items.indexOf(it);
                    if (idx >= 0 && adapter != null) adapter.notifyItemChanged(idx);
                });
            } catch (Throwable ignored) {
            } finally {
                previewQueued.remove(it.path);
                if (core != null) {
                    try {
                        core.destroy();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private String dirLabel(String path) {
        String name = new File(path).getName();
        if (name.isEmpty()) return path;
        return name;
    }

    private void goUp() {
        if (currentDir == null) return;
        focusAll = false;
        if (btnAll != null) btnAll.setSelected(false);
        if (!folderStack.isEmpty()) restoreIndexOnRebuild = folderStack.pop();
        List<String> content = collapsedRoots();
        if (content.contains(currentDir)) {
            currentDir = null;
            singleRootMode = false;
            rebuild();
            return;
        }
        File parent = new File(currentDir).getParentFile();
        if (parent == null) {
            currentDir = null;
        } else {
            String p = parent.getAbsolutePath();
            if (countUnder(p) == 0 && !content.contains(p)) {
                currentDir = null;
            } else {
                currentDir = p;
            }
        }
        rebuild();
    }

    private void updateEmpty() {
        if (!items.isEmpty()) {
            empty.setVisibility(View.GONE);
            return;
        }
        empty.setVisibility(View.VISIBLE);
        if (allItems.isEmpty()) {
            empty.setText("没有找到 .gba 文件\n把 M3 电影卡 ROM 放到媒体目录里再回来");
        } else if (!showAll) {
            empty.setText("没有检测到影片 ROM\n点右上角「找不到想要的影片？」查看全部");
        } else {
            empty.setText("没有找到 .gba 文件");
        }
    }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        class VH extends RecyclerView.ViewHolder {
            TextView title, sub;
            android.widget.ImageView thumb;

            VH(View v) {
                super(v);
                title = v.findViewById(R.id.gba_title);
                sub = v.findViewById(R.id.gba_sub);
                thumb = v.findViewById(R.id.gba_thumb);
            }
        }

        class FolderVH extends RecyclerView.ViewHolder {
            TextView name, count;

            FolderVH(View v) {
                super(v);
                name = v.findViewById(R.id.folder_name);
                count = v.findViewById(R.id.folder_count);
            }
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).dir ? 0 : 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == 0) {
                return new FolderVH(inf.inflate(R.layout.item_folder, parent, false));
            }
            return new VH(inf.inflate(R.layout.item_gba, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Item it = items.get(position);
            if (holder instanceof FolderVH) {
                FolderVH h = (FolderVH) holder;
                h.name.setText(it.name);
                h.count.setText(it.count + " 个影片");
                h.itemView.setFocusable(false);
                h.itemView.setBackgroundResource(R.drawable.bg_gba_row);
                h.itemView.setSelected(!focusAll && position == gbaIndex);
                h.itemView.setOnClickListener(v -> {
                    if (gbaIndex >= 0) folderStack.push(gbaIndex);
                    focusAll = false;
                    btnAll.setSelected(false);
                    currentDir = it.path;
                    rebuild();
                });
            } else {
                VH h = (VH) holder;
                h.title.setText(it.name);
                h.sub.setText(it.path);
                h.sub.setVisibility(View.VISIBLE);
                File pf = findCoverFile(it);
                if (pf == null) pf = previewFile(it);
                if (pf != null && pf.exists()) {
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(
                            pf.getAbsolutePath());
                    if (bmp != null) {
                        h.thumb.setImageBitmap(bmp);
                        h.thumb.setVisibility(View.VISIBLE);
                    } else {
                        h.thumb.setVisibility(View.GONE);
                    }
                } else {
                    h.thumb.setVisibility(View.GONE);
                }
                h.itemView.setFocusable(false);
                h.itemView.setBackgroundResource(R.drawable.bg_gba_row);
                h.itemView.setSelected(!focusAll && position == gbaIndex);
                h.itemView.setOnClickListener(v -> open(it));
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private void updateGbaSelection(boolean scroll) {
        if (list == null) return;
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            int pos = list.getChildAdapterPosition(c);
            c.setSelected(!focusAll && pos == gbaIndex);
        }
        if (scroll && gbaIndex >= 0) {
            try { list.smoothScrollToPosition(gbaIndex); } catch (Throwable ignored) {}
        }
    }

    private void open(Item it) {
        if (it.dir) {
            if (gbaIndex >= 0) folderStack.push(gbaIndex);
            currentDir = it.path;
            rebuild();
            return;
        }
        if (it.path == null) {
            Toast.makeText(this, "无法访问 ROM", Toast.LENGTH_SHORT).show();
            return;
        }
        previewEnabled = false;
        synchronized (coreLock) { }
        skipNextResumeRefresh = true;
        java.util.ArrayList<String> paths = new java.util.ArrayList<>();
        int idx = 0;
        for (Item cur : items) {
            if (cur.dir) continue;
            if (cur == it) idx = paths.size();
            paths.add(cur.path);
        }
        startActivity(new Intent(this, GbaPlayerActivity.class)
                .putExtra("rom_path", it.path)
                .putExtra("rom_name", it.name)
                .putStringArrayListExtra("rom_paths", paths)
                .putExtra("rom_index", idx));
    }
}
