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
    private int updateState;            // 0 未检查 1 检测中 2 有新版本 3 下载中 4 待安装
    private String updateTag;
    private String updateAssetUrl;
    private String updateAssetApiUrl;
    private Uri updateApkUri;
    private static final String UPDATE_API =
            "https://api.github.com/repos/826146857/RGPlayer/releases/latest"; 
    private View backBtn;
    private ActivityResultLauncher<Uri> dirPicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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

        Switch swFont = findViewById(R.id.sw_font);
        swFont.setChecked(Settings.getFontMode(this) == 1);
        swFont.setOnCheckedChangeListener((b, checked) -> {
            Settings.setFontMode(this, checked ? 1 : 0);
            FontApplier.apply(this, findViewById(android.R.id.content));
        });

        Switch swNoMedia = findViewById(R.id.sw_nomedia);
        swNoMedia.setChecked(Settings.getHideNoMedia(this));
        swNoMedia.setOnCheckedChangeListener((b, checked) -> Settings.setHideNoMedia(this, checked));

        findViewById(R.id.row_update).setOnClickListener(v -> onUpdateClick());

        findViewById(R.id.row_help).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, HelpActivity.class)));

        findViewById(R.id.row_clear_cache).setOnClickListener(v -> {
            CoverLoader.clearCache();
            deleteDir(getCacheDir());
            if (getExternalCacheDir() != null) deleteDir(getExternalCacheDir());
            int removed = pruneDeletedMedia();
            android.widget.Toast.makeText(this,
                    removed > 0 ? "已清理 " + removed + " 项已删除视频的缓存" : "缓存干净得能当镜子照了 ✨",
                    android.widget.Toast.LENGTH_SHORT).show();
        });

        TextView about = findViewById(R.id.about);
        String version = "0.42.2";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        about.setText("RGPlayer " + version);

        refreshResize();
        refreshMediaDir();
        setupSettingsFocus();
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
                    removed++;
                }
            }
            e.apply();
            return removed;
        } catch (Throwable t) {
            return 0;
        }
    }


    private void onUpdateClick() {
        switch (updateState) {
            case 0: checkUpdate(); break;
            case 2: downloadUpdate(); break;
            case 4: installUpdate(); break;
            default: break;
        }
    }

    private void setUpdateValue(String t) {
        if (valueUpdate != null) valueUpdate.setText(t);
    }

    private void checkUpdate() {
        if (UPDATE_API.contains("OWNER/REPO")) {
            android.widget.Toast.makeText(this, "更新源还没配置", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        updateState = 1;
        setUpdateValue("检测中…");
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c =
                        (java.net.HttpURLConnection) new java.net.URL(UPDATE_API).openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "RGPlayer-Updater");
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                org.json.JSONObject o = new org.json.JSONObject(sb.toString());
                String tag = o.optString("tag_name");
                String asset = null;
                org.json.JSONArray assets = o.optJSONArray("assets");
                if (assets != null && assets.length() > 0) {
                    asset = assets.getJSONObject(0).optString("browser_download_url");
                    updateAssetApiUrl = assets.getJSONObject(0).optString("url");
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
        updateState = 3;
        setUpdateValue("下载中…");
        String tagClean = updateTag == null ? "" : updateTag.replaceAll("[^0-9A-Za-z._-]", "");
        if (tagClean.startsWith("v") || tagClean.startsWith("V")) tagClean = tagClean.substring(1);
        final String name = "RGPlayer-" + tagClean + ".apk";
        new Thread(() -> {
            try {
                java.io.File tmp = new java.io.File(getCacheDir(), "update.apk");
                String[] sources = new String[] {
                        updateAssetApiUrl,
                        updateAssetUrl,
                        "https://ghproxy.net/" + updateAssetUrl,
                        "https://gh-proxy.com/" + updateAssetUrl
                };
                java.net.HttpURLConnection c = null;
                java.io.InputStream in = null;
                int total = -1;
                Exception last = null;
                for (String src : sources) {
                    if (src == null || src.isEmpty()) continue;
                    try {
                        java.net.HttpURLConnection cand =
                                (java.net.HttpURLConnection) new java.net.URL(src).openConnection();
                        cand.setInstanceFollowRedirects(true);
                        cand.setConnectTimeout(12000);
                        cand.setReadTimeout(30000);
                        cand.setRequestProperty("User-Agent", "RGPlayer-Updater");
                        if (src.equals(updateAssetApiUrl)) {
                            cand.setRequestProperty("Accept", "application/octet-stream");
                        }
                        int code = cand.getResponseCode();
                        if (code >= 400) {
                            last = new java.io.IOException("HTTP " + code + " for " + src);
                            cand.disconnect();
                            continue;
                        }
                        in = cand.getInputStream();
                        c = cand;
                        total = cand.getContentLength();
                        android.util.Log.i("RGUpdate", "downloading from " + src + " len=" + total);
                        break;
                    } catch (Exception e) {
                        last = e;
                        android.util.Log.w("RGUpdate", "source failed: " + src + " -> " + e);
                    }
                }
                if (in == null) throw last == null ? new java.io.IOException("no mirror") : last;
                java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                byte[] buf = new byte[8192];
                long got = 0;
                int r;
                int lastPct = -1;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    got += r;
                    if (total > 0) {
                        int pct = (int) (got * 100 / total);
                        if (pct / 5 != lastPct / 5) {
                            lastPct = pct;
                            final int p = pct;
                            runOnUiThread(() -> setUpdateValue("下载中 " + p + "%"));
                        }
                    }
                }
                out.close();
                in.close();
                Uri uri = saveToDownloads(tmp, name);
                runOnUiThread(() -> {
                    updateApkUri = uri;
                    updateState = 4;
                    setUpdateValue("安装");
                    android.widget.Toast.makeText(this, "已保存到 Download/" + name,
                            android.widget.Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                android.util.Log.e("RGUpdate", "download failed: " + e, e);
                runOnUiThread(() -> {
                    updateState = 2;
                    setUpdateValue("下载");
                    android.widget.Toast.makeText(this, "下载失败",
                            android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private Uri saveToDownloads(java.io.File src, String name) throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,
                    "application/vnd.android.package-archive");
            cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            cv.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new java.io.IOException("insert failed");
            java.io.OutputStream os = getContentResolver().openOutputStream(uri);
            copy(src, os);
            cv.clear();
            cv.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, cv, null, null);
            return uri;
        }
        java.io.File dir = android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
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
