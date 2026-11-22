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
    private View firstRow; // TODO: 换成实际仓库
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
