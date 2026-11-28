package com.rg.player;

import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.KeyEvent;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private TextView valueResize;
    private TextView valueMediaDir;
    private View firstRow;
    private TextView valueUpdate;
    private static volatile int updateState;            // 0 未检查 1 检测中 2 有新版本 3 下载中 4 待安装
    private static volatile String updateTag;
    private static volatile String updateAssetUrl;
    private static volatile String updateAssetApiUrl;
    private static volatile Uri updateApkUri;
    private static volatile boolean updatePaused;
    private static volatile boolean updateDownloading;
    private static volatile boolean updateResumeAfterStop;
    private static volatile boolean updateCancelled;
    private static volatile int updateGen;
    private static volatile java.net.HttpURLConnection updateConn;
    private static final String[] UPDATE_URLS = {
            "https://raw.githubusercontent.com/826146857/RGPlayer/main/update.json",
            "https://gitee.com/Li826146857/RGPlayer/raw/main/update.json"
    };
    private static final String UPDATE_DOWNLOAD_BASE =
            "https://gitee.com/Li826146857/RGPlayer/releases/download/"; 
    private static volatile SettingsActivity ui;
    private static volatile String updateProgressText = "";
    private View backBtn;
    private ActivityResultLauncher<Uri> dirPicker;

    private static void uiText(String text) {
        updateProgressText = text;
        SettingsActivity a = ui;
        if (a == null) return;
        a.runOnUiThread(() -> {
            if (ui == a) a.setUpdateValue(text);
        });
    }

    private void restoreUpdateView() {
        if (updateState == 3) {
            updateState = 3;
            if (updatePaused) {
                setUpdateValue("已暂停，点继续");
                updateProgressText = "已暂停，点继续";
            } else {
                setUpdateValue(updateProgressText.isEmpty() ? "下载中…" : updateProgressText);
            }
        } else if (updateState == 4 && updateApkUri != null) {
            setUpdateValue("安装");
        } else if (updateState == 2 && updateAssetUrl != null) {
            setUpdateValue("下载");
        } else {
            updateState = 0;
            setUpdateValue("检查");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ui = this;
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        FontApplier.apply(this, findViewById(android.R.id.content));

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        valueResize = findViewById(R.id.value_resize);
        valueMediaDir = findViewById(R.id.value_media_dir);
        valueUpdate = findViewById(R.id.value_update);

        dirPicker = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
            if (uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            try {
                String docId = DocumentsContract.getTreeDocumentId(uri);
                int i = docId.indexOf(':');
                String vol = i >= 0 ? docId.substring(0, i) : "";
                String rel = i >= 0 ? docId.substring(i + 1) : docId;
                Settings.setMediaDir(this, vol, rel);
            } catch (Exception e) {
                Settings.setMediaDir(this, "", "");
            }
            refreshMediaDir();
        });

        findViewById(R.id.row_resize).setOnClickListener(v -> {
            Settings.setResize(this, (Settings.getResize(this) + 1) % 2);
            refreshResize();
        });

        findViewById(R.id.row_media_dir).setOnClickListener(v -> dirPicker.launch(null));
        findViewById(R.id.row_media_dir).setOnLongClickListener(v -> {
            Settings.setMediaDir(this, "", "");
            refreshMediaDir();
            return true;
        });

        findViewById(R.id.row_font).setOnClickListener(v -> {
            Settings.setFontMode(this, Settings.getFontMode(this) == 1 ? 0 : 1);
            refreshFont();
            FontApplier.apply(this, findViewById(android.R.id.content));
        });

        findViewById(R.id.row_nomedia).setOnClickListener(v -> {
            Settings.setHideNoMedia(this, !Settings.getHideNoMedia(this));
            refreshNoMedia();
        });

        findViewById(R.id.row_update).setOnClickListener(v -> onUpdateClick());

        findViewById(R.id.row_help).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, HelpActivity.class)));

        findViewById(R.id.row_clear_cache).setOnClickListener(v -> {
            CoverLoader.clearCache();
            ThumbCache.reset();
            updateGen++;
            updateCancelled = true;
            updateDownloading = false;
            updatePaused = false;
            try { if (updateConn != null) updateConn.disconnect(); } catch (Throwable ignored) {}
            java.io.File cacheDir = getCacheDir();
            java.io.File[] olds = cacheDir.listFiles((d, n) -> n.startsWith("update"));
            if (olds != null) for (java.io.File f : olds) f.delete();
            deleteDir(new java.io.File(getFilesDir(), "updates"));
            updateState = 0;
            updateApkUri = null;
            updateTag = null;
            updateAssetUrl = null;
            updatePaused = false;
            updateResumeAfterStop = false;
            setUpdateValue("检查");
            int removed = pruneDeletedMedia();
            java.util.Set<String> alivePaths = new java.util.HashSet<>();
            java.util.Set<String> aliveUris = new java.util.HashSet<>();
            for (VideoItem vi : MediaLibrary.loadVideos(this, Settings.getHideNoMedia(this))) {
                if (vi.path != null) alivePaths.add(vi.path);
                aliveUris.add(vi.uri.toString());
            }
            CoverLoader.prune(this, alivePaths);
            ThumbCache.prune(this, aliveUris);
            android.widget.Toast.makeText(this,
                    removed > 0 ? "已清理 " + removed + " 个已删除视频的缓存" : "缓存已清理",
                    android.widget.Toast.LENGTH_SHORT).show();
        });

        TextView about = findViewById(R.id.about);
        String version = "0.42.2";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        about.setText("RGPlayer " + version);

        restoreDownloadedUpdate();
        restoreUpdateView();
        refreshResize();
        refreshMediaDir();
        refreshFont();
        refreshNoMedia();
        setupSettingsFocus();
    }

    private void refreshFont() {
        android.widget.TextView v = findViewById(R.id.value_font);
        if (v != null) v.setText(Settings.getFontMode(this) == 1 ? "内置" : "系统");
    }

    private void refreshNoMedia() {
        android.widget.TextView v = findViewById(R.id.value_nomedia);
        if (v != null) v.setText(Settings.getHideNoMedia(this) ? "隐藏" : "显示");
    }

    private void restoreDownloadedUpdate() {
        try {
            java.io.File dir = new java.io.File(getFilesDir(), "updates");
            java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".apk"));
            if (files == null || files.length == 0) return;
            java.io.File apk = files[0];
            for (java.io.File f : files) if (f.lastModified() > apk.lastModified()) apk = f;
            updateApkUri = androidx.core.content.FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", apk);
            updateState = 4;
            setUpdateValue("安装");
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        if (ui == this) ui = null;
        super.onDestroy();
    }

    private void refreshResize() {
        valueResize.setText(Settings.getResize(this) == 0 ? "原画" : "沉浸");
    }

    private void refreshMediaDir() {
        String vol = Settings.getMediaVolume(this);
        String dir = Settings.getMediaDir(this);
        if (dir == null || dir.isEmpty()) {
            valueMediaDir.setText("全部");
        } else {
            valueMediaDir.setText(dir);
        }
    }

    /** 只删除已经不在媒体库里的视频缓存（响度/进度/音轨/字幕记忆）。 */
    private int pruneDeletedMedia() {
        try {
            java.util.Set<String> alive = new java.util.HashSet<>();
            for (VideoItem v : MediaLibrary.loadVideos(this, Settings.getHideNoMedia(this))) {
                alive.add(v.uri.toString());
            }
            android.content.SharedPreferences p = getSharedPreferences("rgsettings", MODE_PRIVATE);
            android.content.SharedPreferences.Editor e = p.edit();
            int removed = 0;
            java.util.Set<String> removedUris = new java.util.HashSet<>();
            for (String key : new java.util.ArrayList<>(p.getAll().keySet())) {
                String uri = null;
                for (String prefix : new String[]{"lg2:", "lg:", "pos:", "postime:", "txt:", "aud:"}) {
                    if (key.startsWith(prefix)) {
                        uri = key.substring(prefix.length());
                        break;
                    }
                }
                if (uri != null && !alive.contains(uri)) {
                    e.remove(key);
                    removedUris.add(uri);
                }
            }
            e.apply();
            return removedUris.size();
        } catch (Throwable t) {
            return 0;
        }
    }


    private void onUpdateClick() {
        switch (updateState) {
            case 0: checkUpdate(); break;
            case 2: downloadUpdate(); break;
            case 3:
                if (updatePaused) {
                    updatePaused = false;
                    uiText("下载中…");
                    if (!updateDownloading) downloadUpdate();
                } else {
                    updatePaused = true;
                    uiText("已暂停，点继续");
                }
                break;
            case 4: installUpdate(); break;
            default: break;
        }
    }

    private void setUpdateValue(String t) {
        if (valueUpdate != null) valueUpdate.setText(t);
    }

    private void checkUpdate() {
        if (UPDATE_URLS.length == 0) {
            android.widget.Toast.makeText(this, "更新源还没配置", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        updateState = 1;
        setUpdateValue("检测中…");
        new Thread(() -> {
            try {
                org.json.JSONObject o = null;
                Exception lastError = null;
                for (String base : UPDATE_URLS) {
                    try {
                        String api = base + (base.contains("?") ? "&" : "?")
                                + "t=" + System.currentTimeMillis();
                        java.net.HttpURLConnection c =
                                (java.net.HttpURLConnection) new java.net.URL(api).openConnection();
                        c.setRequestProperty("Accept", "application/json");
                        c.setRequestProperty("Cache-Control", "no-cache");
                        c.setRequestProperty("User-Agent", "RGPlayer-Updater");
                        c.setConnectTimeout(20000);
                        c.setReadTimeout(20000);
                        java.io.BufferedReader br = new java.io.BufferedReader(
                                new java.io.InputStreamReader(c.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        br.close();
                        o = new org.json.JSONObject(sb.toString());
                        break;
                    } catch (Exception e) {
                        lastError = e;
                    }
                }
                if (o == null) throw lastError == null
                        ? new java.io.IOException("no update source") : lastError;
                String tag = o.optString("version");
                String asset = o.optString("url");
                if ((tag == null || tag.isEmpty())) tag = o.optString("tag_name");
                if ((asset == null || asset.isEmpty()) && tag != null && !tag.isEmpty()) {
                    asset = UPDATE_DOWNLOAD_BASE + "v" + tag.replaceFirst("^[vV]", "")
                            + "/RGPlayer-" + tag.replaceFirst("^[vV]", "") + ".apk";
                }
                final String ftag = tag;
                final String fasset = asset;
                runOnUiThread(() -> {
                    String cur = "";
                    try {
                        cur = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                    } catch (Exception ignored) {
                    }
                    if (fasset != null && !fasset.isEmpty() && isNewer(ftag, cur)) {
                        updateTag = ftag;
                        updateAssetUrl = fasset;
                        updateState = 2;
                        setUpdateValue("下载");
                        android.widget.Toast.makeText(this, "发现新版本 " + ftag,
                                android.widget.Toast.LENGTH_SHORT).show();
                    } else {
                        updateState = 0;
                        setUpdateValue("检查");
                        android.widget.Toast.makeText(this, "已是最新版本",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                android.util.Log.e("RGUpdate", "check failed: " + e, e);
                runOnUiThread(() -> {
                    updateState = 0;
                    setUpdateValue("检查");
                    android.widget.Toast.makeText(this, "检查更新失败",
                            android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private static boolean isNewer(String tag, String current) {
        if (tag == null || current == null) return false;
        String a = tag.replaceAll("[^0-9.]", "");
        String b = current.replaceAll("[^0-9.]", "");
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int y = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (x > y) return true;
            if (x < y) return false;
        }
        return false;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private void downloadUpdate() {
        if (updateAssetUrl == null) return;
        if (updateDownloading) {
            if (updatePaused) {
                updatePaused = false;
                setUpdateValue("下载中…");
            }
            return;
        }
        updateDownloading = true;
        updatePaused = false;
        final int gen = ++updateGen;
        final java.io.File tmp = new java.io.File(getCacheDir(), "update_" + gen + ".apk");
        try { getCacheDir().mkdirs(); } catch (Throwable ignored) {}
        updateState = 3;
        setUpdateValue("下载中…");
        final String tagClean0 = updateTag == null ? "" : updateTag.replaceAll("[^0-9A-Za-z._-]", "");
        final String tagClean = tagClean0.startsWith("v") || tagClean0.startsWith("V")
                ? tagClean0.substring(1) : tagClean0;
        final String name = "RGPlayer-" + tagClean + ".apk";
        new Thread(() -> {
            java.io.FileOutputStream out = null;
            java.io.InputStream in = null;
            try {
                long existing = tmp.length();
                java.net.HttpURLConnection c =
                        (java.net.HttpURLConnection) new java.net.URL(updateAssetUrl).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(0);
                c.setRequestProperty("User-Agent", "RGPlayer-Updater");
                c.setRequestProperty("Accept-Encoding", "identity");
                if (existing > 0) c.setRequestProperty("Range", "bytes=" + existing + "-");
                updateConn = c;
                int code = c.getResponseCode();
                if (code >= 400) throw new java.io.IOException("HTTP " + code);
                boolean append = code == 206 && existing > 0;
                if (!append) existing = 0;
                long clen = c.getContentLengthLong();
                final long total = clen > 0 ? existing + clen : -1;
                in = c.getInputStream();
                out = new java.io.FileOutputStream(tmp, append);
                byte[] buf = new byte[64 * 1024];
                long got = existing;
                long lastUi = 0;
                int r;
                while (true) {
                    if (gen != updateGen) break;
                    if (updatePaused) {
                        try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    r = in.read(buf);
                    if (r <= 0) break;
                    out.write(buf, 0, r);
                    got += r;
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastUi > 120) {
                        lastUi = now;
                        final long g = got;
                        if (gen == updateGen) uiText("下载中 " + progressText(g, total));
                    }
                }
                out.flush();
                out.close();
                out = null;
                in.close();
                in = null;
                updateConn = null;
                if (gen != updateGen) {
                    try { tmp.delete(); } catch (Throwable ignored) {}
                    return;
                }
                if (updatePaused) {
                    uiText("已暂停，点继续");
                    return;
                }
                Uri uri = saveToDownloads(tmp, name);
                try { tmp.delete(); } catch (Throwable ignored) {}
                if (gen != updateGen) return;
                updateApkUri = uri;
                updateState = 4;
                uiText("安装");
                SettingsActivity a = ui;
                if (a != null) a.runOnUiThread(() -> android.widget.Toast.makeText(a,
                        "已下载，点安装继续", android.widget.Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                android.util.Log.e("RGUpdate", "download failed: " + e, e);
                try { if (out != null) out.close(); } catch (Throwable ignored) {}
                try { if (in != null) in.close(); } catch (Throwable ignored) {}
                if (gen != updateGen) {
                    try { tmp.delete(); } catch (Throwable ignored) {}
                    return;
                }
                if (updatePaused) {
                    uiText("已暂停，点继续");
                } else {
                    updateState = 2;
                    uiText("下载");
                }
            } finally {
                updateConn = null;
                if (gen == updateGen) updateDownloading = false;
            }
        }, "rg-update").start();
    }

    private static String progressText(long got, long total) {
        double mb = got / 1048576.0;
        if (total > 0) {
            int pct = (int) (got * 100 / total);
            return pct + "%  " + String.format(java.util.Locale.US, "%.1f/%.1fMB", mb, total / 1048576.0);
        }
        return String.format(java.util.Locale.US, "%.1fMB", mb);
    }

    private Uri saveToDownloads(java.io.File src, String name) throws Exception {
        java.io.File dir = new java.io.File(getFilesDir(), "updates");
        if (!dir.exists()) dir.mkdirs();
        java.io.File dst = new java.io.File(dir, name);
        copy(src, new java.io.FileOutputStream(dst));
        return androidx.core.content.FileProvider.getUriForFile(this,
                getPackageName() + ".fileprovider", dst);
    }

    private static void copy(java.io.File src, java.io.OutputStream os) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
        in.close();
        os.close();
    }

    private void installUpdate() {
        if (updateApkUri == null) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26
                    && !getPackageManager().canRequestPackageInstalls()) {
                android.widget.Toast.makeText(this, "请允许安装未知应用后，再点一次安装",
                        android.widget.Toast.LENGTH_LONG).show();
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW)
                    .setDataAndType(updateApkUri, "application/vnd.android.package-archive")
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "无法启动安装", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteDir(java.io.File dir) {
        if (dir == null) return;
        java.io.File[] files = dir.listFiles();
        if (files != null) {
            for (java.io.File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    private void setupSettingsFocus() {
        View root = findViewById(android.R.id.content);
        java.util.List<View> rows = new java.util.ArrayList<>();
        collectRows(root, rows);
        for (View row : rows) {
            row.setBackgroundResource(R.drawable.bg_row_focus);
            row.setFocusable(true);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                row.setDefaultFocusHighlightEnabled(false);
            }
            Switch sw = findSwitch(row);
            if (sw != null) {
                sw.setFocusable(false);
                sw.setFocusableInTouchMode(false);
                row.setOnClickListener(v -> sw.toggle());
            }
        }
        backBtn = findViewById(R.id.btn_back);
        if (backBtn != null) {
            backBtn.setFocusable(false);
            backBtn.setFocusableInTouchMode(false);
            if (android.os.Build.VERSION.SDK_INT >= 26) backBtn.setDefaultFocusHighlightEnabled(false);
        }
        if (!rows.isEmpty()) {
            firstRow = rows.get(0);
            final View first = firstRow;
            first.post(first::requestFocus);
            getWindow().getDecorView().postDelayed(first::requestFocus, 400);
        }
    }

    private void collectRows(View v, java.util.List<View> out) {
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (isSettingsRow(c)) out.add(c);
                collectRows(c, out);
            }
        }
    }

    private boolean isSettingsRow(View v) {
        if (!(v instanceof android.widget.LinearLayout)) return false;
        if (((android.view.ViewGroup) v).getChildCount() > 3) return false;
        android.view.ViewGroup g = (android.view.ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (!(c instanceof android.widget.LinearLayout)) continue;
            android.view.ViewGroup texts = (android.view.ViewGroup) c;
            int textCount = 0;
            boolean hasLayoutChild = false;
            for (int j = 0; j < texts.getChildCount(); j++) {
                View t = texts.getChildAt(j);
                if (t instanceof android.widget.TextView) textCount++;
                if (t instanceof android.widget.LinearLayout) hasLayoutChild = true;
            }
            if (!hasLayoutChild && textCount >= 2) return true;
        }
        return false;
    }

    private Switch findSwitch(View row) {
        if (row instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) row;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof Switch) return (Switch) c;
            }
        }
        return null;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            View focus = getCurrentFocus();
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
                    && (focus == backBtn || focus == null)) {
                if (firstRow != null) firstRow.requestFocus();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
