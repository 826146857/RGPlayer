package com.rg.player;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 基于 libVLC 的播放页：尽量对齐 Media3 版的交互。 */
public class VlcPlayerActivity extends AppCompatActivity {

    private static final long HIDE_DELAY_MS = 3200;
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f, 4f};
    private static final String[] SPEED_LABELS = {"0.5X", "0.75X", "1.0X", "1.25X", "1.5X", "2.0X", "2.5X", "3.0X", "4.0X"};
    private static final String[] RATIO_LABELS = {"自动", "铺满", "16:9", "4:3", "1:1", "原始"};

    private LibVLC libVLC;
    private MediaPlayer mp;
    private VLCVideoLayout videoLayout;
    private android.os.ParcelFileDescriptor keepFd;
    private String currentPath;

    private View overlay;
    private View touchCatcher;
    private TextView btnPlay, btnPrev, btnNext, title, timeNow, timeTotal, btnReset, brightnessHint;
    private SeekBar seek;
    private View previewPanel;
    private ImageView previewImage;
    private TextView previewTime;
    private FrameLayout menuLayer;
    private LinearLayout menuCard, menuItems;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 600);
        }
    };
    private final Runnable hideTask = () -> setOverlayVisible(false);

    private List<VideoItem> playlist = new ArrayList<>();
    private int index;
    private boolean menuOpen;
    private Runnable menuBack;
    private java.util.function.IntConsumer menuPick;

    private float appVolume = 1f;
    private int ratioIndex;
    private float rate = 1f;
    private float zoom = 1f;
    private int rotationSteps;
    private boolean pinching;
    private float baseDist, baseZoom, baseFocusX, baseFocusY;
    private String[] EXTRA = {"0°", "90°", "180°", "270°"};

    private long resumeMs;
    private boolean resumeApplied;
    private long lastSaveMs;
    private boolean endHandled;
    private long pendingSeekMs = -1;
    private long lastTickPos = -1;
    private long lastTickChange;

    private float downX, downY;
    private long downTime;
    private boolean gestureUsed;
    private int gestureMode; // 0 无 1 竖向 2 拖动进度
    private boolean scrubbing;
    private long scrubStartMs, scrubTargetMs;
    private long lastPreviewMs = -1;
    private float startBrightness = -1f;
    private float startVolume;
    private boolean edgeGesture;
    private long lastTap;
    private boolean boosting;
    private float boostBase = 1f;
    private long lastSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat ic = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        ic.hide(WindowInsetsCompat.Type.systemBars());
        ic.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_vlc);
        FontApplier.apply(this, findViewById(android.R.id.content));

        videoLayout = findViewById(R.id.vlc_video);
        overlay = findViewById(R.id.overlay);
        touchCatcher = findViewById(R.id.touch_catcher);
        btnPlay = findViewById(R.id.btn_play);
        btnPrev = findViewById(R.id.btn_prev);
        btnNext = findViewById(R.id.btn_next);
        btnReset = findViewById(R.id.btn_reset);
        title = findViewById(R.id.player_title);
        timeNow = findViewById(R.id.time_now);
        timeTotal = findViewById(R.id.time_total);
        seek = findViewById(R.id.seek);
        brightnessHint = findViewById(R.id.brightness_hint);
        previewPanel = findViewById(R.id.preview_panel);
        previewImage = findViewById(R.id.preview_image);
        previewTime = findViewById(R.id.preview_time);
        menuLayer = findViewById(R.id.menu_layer);
        menuCard = findViewById(R.id.menu_card);
        menuItems = findViewById(R.id.menu_items);

        playlist = PlaylistHolder.items();
        index = PlaylistHolder.index();
        if (playlist == null || playlist.isEmpty()) {
            finish();
            return;
        }

        List<String> options = new ArrayList<>();
        options.add("--audio-time-stretch");
        options.add("--avcodec-hw=any");
        options.add("--no-drop-late-frames");
        options.add("--no-skip-frames");
        options.add("--sub-autodetect-file");
        options.add("--video-filter=transform");
        libVLC = new LibVLC(this, options);
        mp = new MediaPlayer(libVLC);
        mp.attachViews(videoLayout, null, false, false);

        mp.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.TimeChanged:
                case MediaPlayer.Event.LengthChanged:
                    updateProgress();
                    break;
                case MediaPlayer.Event.Playing:
                    btnPlay.setText("❚❚");
                    if (!resumeApplied) {
                        resumeApplied = true;
                        if (resumeMs > 1000) {
                            mp.setTime(resumeMs);
                            showChip("从 " + formatDuration(resumeMs) + " 继续", 1200);
                        }
                    }
                    updateProgress();
                    break;
                case MediaPlayer.Event.Paused:
                    btnPlay.setText("▶");
                    savePosition();
                    break;
                case MediaPlayer.Event.EndReached:
                    android.util.Log.i("RGPlayerVLC", "EndReached");
                    if (!endHandled) {
                        endHandled = true;
                        onEnded();
                    }
                    break;
                case MediaPlayer.Event.EncounteredError:
                    Toast.makeText(this, "VLC 无法播放该文件", Toast.LENGTH_LONG).show();
                    break;
                default:
                    break;
            }
        });

        btnPlay.setOnClickListener(v -> togglePlay());
        btnPrev.setOnClickListener(v -> switchBy(-1));
        btnNext.setOnClickListener(v -> switchBy(1));
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    long dur = mp.getLength();
                    timeNow.setText(formatDuration(progress * dur / 1000));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                handler.removeCallbacks(hideTask);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                long dur = mp.getLength();
                mp.setTime(seekBar.getProgress() * dur / 1000);
                scheduleHide();
            }
        });

        btnReset.setOnClickListener(v -> resetTransform());

        menuLayer.setOnClickListener(v -> dismissMenu(true));
        menuLayer.setAlpha(1f);

        touchCatcher.setOnTouchListener(this::onTouch);

        setOverlayVisible(false);
        play(index, true);
        handler.post(tick);
    }

    // ---------------- 播放控制 ----------------

    private void play(int i, boolean resetResume) {
        if (playlist == null || playlist.isEmpty()) return;
        i = Math.max(0, Math.min(i, playlist.size() - 1));
        index = i;
        PlaylistHolder.setIndex(i);
        VideoItem item = playlist.get(i);
        title.setText(stripExtension(item.name));
        btnPrev.setAlpha(i > 0 ? 1f : 0.35f);
        btnNext.setAlpha(i + 1 < playlist.size() ? 1f : 0.35f);
        zoom = 1f;
        updateResetButton();

        mp.stop();
        if (keepFd != null) {
            try {
                keepFd.close();
            } catch (Exception ignored) {
            }
            keepFd = null;
        }
        endHandled = false;
        resumeMs = resetResume ? MediaLibrary.position(this, item.uri) : 0;
        if (pendingSeekMs >= 0) {
            resumeMs = pendingSeekMs;
            pendingSeekMs = -1;
        }
        if (resetResume) resumeMs = MediaLibrary.position(this, item.uri);
        resumeApplied = false;

        Media media = buildMedia(item);
        int rot = rotationSteps % 4;
        media.addOption(":transform-type=" + (rot == 1 ? "90" : rot == 2 ? "180" : rot == 3 ? "270" : "0"));
        media.addOption(":sub-text-scale=1.4");
        media.addOption(":sub-margin=24");
        Uri storedSub = resolveSubtitle(item);
        if (storedSub != null && storedSub.getPath() != null) {
            media.addOption(":sub-file=" + storedSub.getPath());
        } else {
            String auto = findSiblingSubtitle();
            if (auto != null) media.addOption(":sub-file=" + auto);
        }
        media.setHWDecoderEnabled(rotationSteps % 4 == 0, rotationSteps % 4 == 0);
        mp.setMedia(media);
        media.release();
        mp.setRate(rate);
        mp.play();
        updateProgress();
    }

    private Media buildMedia(VideoItem item) {
        try {
            if ("content".equals(item.uri.getScheme())) {
                android.database.Cursor c = getContentResolver().query(item.uri, new String[]{"_data"}, null, null, null);
                if (c != null) {
                    String path = null;
                    if (c.moveToFirst()) path = c.getString(0);
                    c.close();
                    if (path != null && new java.io.File(path).exists()) {
                        currentPath = path;
                        return new Media(libVLC, path);
                    }
                }
                keepFd = getContentResolver().openFileDescriptor(item.uri, "r");
                if (keepFd != null) return new Media(libVLC, keepFd.getFileDescriptor());
            }
        } catch (Throwable t) {
            android.util.Log.e("RGPlayerVLC", "open failed", t);
        }
        return new Media(libVLC, item.uri.toString());
    }

    private String findSiblingSubtitle() {
        if (currentPath == null) return null;
        int dot = currentPath.lastIndexOf('.');
        String base = dot > 0 ? currentPath.substring(0, dot) : currentPath;
        String[] exts = {".srt", ".ass", ".ssa", ".vtt", ".sub", ".SRT", ".ASS"};
        for (String ext : exts) {
            java.io.File f = new java.io.File(base + ext);
            if (f.exists()) return f.getAbsolutePath();
        }
        return null;
    }

    private Uri resolveSubtitle(VideoItem item) {
        String stored = MediaLibrary.subtitle(this, item.uri);
        if (stored != null) {
            Uri u = Uri.parse(stored);
            if (u.getPath() != null && new java.io.File(u.getPath()).exists()) return u;
        }
        return null;
    }

    private void switchBy(int delta) {
        long now = System.currentTimeMillis();
        if (now - lastSwitch < 700) return;
        lastSwitch = now;
        int next = index + delta;
        if (next < 0 || next >= playlist.size()) return;
        savePosition();
        play(next, true);
    }

    private void onEnded() {
        if (index + 1 < playlist.size()) {
            play(index + 1, false);
        } else {
            finish();
        }
    }

    private void togglePlay() {
        if (mp.isPlaying()) mp.pause();
        else mp.play();
        showOverlay();
    }

    private void savePosition() {
        if (playlist == null || index < 0 || index >= playlist.size()) return;
        long t = mp.getTime();
        long d = mp.getLength();
        if (t > 1000 && d > 0) {
            MediaLibrary.savePosition(this, playlist.get(index).uri, t, d);
            lastSaveMs = t;
        }
    }

    private void updateProgress() {
        long dur = mp.getLength();
        long pos = mp.getTime();
        if (dur > 0) {
            timeTotal.setText(formatDuration(dur));
            int st = mp.getPlayerState();
            if (pos != lastTickPos) {
                lastTickPos = pos;
                lastTickChange = System.currentTimeMillis();
            }
            boolean stalled = lastTickPos > 0
                    && System.currentTimeMillis() - lastTickChange > 2000
                    && pos >= dur - 2000;
            if (!endHandled && (st == 6 || (!mp.isPlaying() && pos >= dur - 250) || stalled)) {
                endHandled = true;
                android.util.Log.i("RGPlayerVLC", "end fallback pos=" + pos + " dur=" + dur + " st=" + st);
                onEnded();
            }
            if (!scrubbing) {
                seek.setProgress((int) Math.max(0, Math.min(1000, pos * 1000 / dur)));
                timeNow.setText(formatDuration(pos));
            }
        }
    }

    private void resetTransform() {
        long t = mp.getTime();
        zoom = 1f;
        rotationSteps = 0;
        pendingSeekMs = t;
        resumeApplied = false;
        try {
            mp.setScale(0f);
        } catch (Throwable ignored) {
        }
        play(index, false);
        updateResetButton();
    }

    // ---------------- 界面 ----------------

    private void setOverlayVisible(boolean visible) {
        if (visible) {
            overlay.setVisibility(View.VISIBLE);
            overlay.setAlpha(0f);
            overlay.animate().alpha(1f).setDuration(160).withEndAction(() -> {
                overlay.setVisibility(View.VISIBLE);
                updateResetButton();
            }).start();
            scheduleHide();
        } else {
            overlay.animate().alpha(0f).setDuration(160).withEndAction(() -> {
                overlay.setVisibility(View.INVISIBLE);
                updateResetButton();
            }).start();
            handler.removeCallbacks(hideTask);
            if (btnReset != null) btnReset.setVisibility(View.GONE);
        }
    }

    private void showOverlay() {
        setOverlayVisible(true);
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideTask);
        handler.postDelayed(hideTask, HIDE_DELAY_MS);
    }

    private void showChip(String text, long ms) {
        brightnessHint.setText(text);
        brightnessHint.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideHintTask);
        handler.postDelayed(hideHintTask, ms);
    }

    private final Runnable hideHintTask = () -> brightnessHint.setVisibility(View.GONE);

    private void updateResetButton() {
        if (btnReset == null) return;
        boolean active = Math.abs(zoom - 1f) > 0.01f || rotationSteps % 4 != 0;
        boolean overlayVisible = overlay != null && overlay.getVisibility() == View.VISIBLE;
        btnReset.setVisibility(active && overlayVisible ? View.VISIBLE : View.GONE);
    }

    // ---------------- 菜单 ----------------

    private void showMenu() {
        final String[] items = {
                "画面比例：" + ratioLabel(),
                "播放速度：" + formatSpeed(rate),
                "旋转：" + EXTRA[rotationSteps % 4],
                "字幕",
                "音轨",
                "视频信息"
        };
        showMenuPanel("", items, null, which -> {
            switch (which) {
                case 0: cycleRatio(); break;
                case 1: chooseSpeed(); break;
                case 2: rotateStep(); break;
                case 3: showSubtitles(); break;
                case 4: showAudio(); break;
                case 5: showVideoInfo(); break;
            }
        });
    }

    private void showMenuPanel(String header, String[] list, Runnable backAction, java.util.function.IntConsumer pick) {
        menuBack = backAction;
        menuPick = pick;
        menuItems.removeAllViews();
        if (header != null && !header.isEmpty()) {
            TextView h = new TextView(this);
            h.setText(header);
            h.setTextSize(11);
            h.setTextColor(0xFF8A90A0);
            h.setPadding(dp(16), dp(4), dp(16), dp(6));
            menuItems.addView(h);
        }
        if (backAction != null) {
            TextView back = new TextView(this);
            back.setText("← 返回");
            back.setTextSize(14);
            back.setTextColor(0xFF35C8FF);
            back.setPadding(dp(16), dp(8), dp(16), dp(8));
            back.setOnClickListener(v -> backAction.run());
            menuItems.addView(back);
        }
        for (int i = 0; i < list.length; i++) {
            final int idx = i;
            TextView row = new TextView(this);
            row.setText(list[i]);
            row.setTextSize(14);
            row.setTextColor(0xFFEEF0F4);
            row.setPadding(dp(16), dp(8), dp(16), dp(8));
            row.setOnClickListener(v -> {
                if (idx == list.length - 1 && list[idx].equals("加载字幕文件…")) {
                    pickSubtitle();
                    dismissMenu(true);
                    return;
                }
                final java.util.function.IntConsumer action = menuPick;
                dismissMenu(false);
                if (action != null) action.accept(idx);
            });
            menuItems.addView(row);
        }
        FontApplier.apply(this, menuCard);
        menuLayer.setVisibility(View.VISIBLE);
        menuOpen = true;
        menuLayer.setAlpha(1f);
        scheduleMenuHide();
    }

    private final Runnable menuHide = () -> dismissMenu(true);

    private void scheduleMenuHide() {
        handler.removeCallbacks(menuHide);
        handler.postDelayed(menuHide, 15000);
    }

    private void dismissMenu(boolean closeOverlay) {
        handler.removeCallbacks(menuHide);
        menuOpen = false;
        menuLayer.setVisibility(View.GONE);
        menuBack = null;
        menuPick = null;
        if (closeOverlay) setOverlayVisible(false);
    }

    private String ratioLabel() {
        return RATIO_LABELS[ratioIndex];
    }

    private void cycleRatio() {
        ratioIndex = (ratioIndex + 1) % RATIO_LABELS.length;
        applyRatio();
        Toast.makeText(this, "画面比例：" + ratioLabel(), Toast.LENGTH_SHORT).show();
        showOverlay();
    }

    private void applyRatio() {
        switch (ratioIndex) {
            case 0:
                mp.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
                break;
            case 1:
                mp.setVideoScale(MediaPlayer.ScaleType.SURFACE_FILL);
                break;
            case 2:
                mp.setVideoScale(MediaPlayer.ScaleType.SURFACE_16_9);
                break;
            case 3:
                mp.setVideoScale(MediaPlayer.ScaleType.SURFACE_4_3);
                break;
            case 4:
                mp.setVideoScale(MediaPlayer.ScaleType.SURFACE_ORIGINAL);
                break;
            default:
                mp.setVideoScale(MediaPlayer.ScaleType.SURFACE_ORIGINAL);
                break;
        }
    }

    private void rotateStep() {
        pendingSeekMs = mp.getTime();
        resumeApplied = false;
        rotationSteps = (rotationSteps + 1) % 4;
        play(index, false);
        showChip("旋转 " + EXTRA[rotationSteps], 800);
    }

    private void chooseSpeed() {
        showMenuPanel("播放速度", SPEED_LABELS, this::showMenu, which -> {
            rate = SPEEDS[which];
            mp.setRate(rate);
            showChip(formatSpeed(rate), 800);
        });
    }

    private void showAudio() {
        MediaPlayer.TrackDescription[] tracks = mp.getAudioTracks();
        if (tracks == null || tracks.length == 0) {
            Toast.makeText(this, "没有可选音轨", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> labels = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        for (MediaPlayer.TrackDescription t : tracks) {
            labels.add((t.id == mp.getAudioTrack() ? "✓ " : "") + t.name);
            ids.add(t.id);
        }
        showMenuPanel("音轨", labels.toArray(new String[0]), this::showMenu,
                which -> mp.setAudioTrack(ids.get(which)));
    }

    private void showSubtitles() {
        MediaPlayer.TrackDescription[] tracks = mp.getSpuTracks();
        List<String> labels = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        if (tracks != null) {
            for (MediaPlayer.TrackDescription t : tracks) {
                String nm = t.name;
                if (t.id == -1 || "Disable".equalsIgnoreCase(nm)) nm = "关闭字幕";
                labels.add((t.id == mp.getSpuTrack() ? "✓ " : "") + nm);
                ids.add(t.id);
            }
        }
        labels.add("加载字幕文件…");
        showMenuPanel("字幕", labels.toArray(new String[0]), this::showMenu, which -> {
            if (which < ids.size()) mp.setSpuTrack(ids.get(which));
        });
    }

    private void pickSubtitle() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        subtitlePicker.launch(i);
    }

    private final androidx.activity.result.ActivityResultLauncher<Intent> subtitlePicker =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                        Uri uri = result.getData().getData();
                        if (uri == null) return;
                        try {
                            getContentResolver().takePersistableUriPermission(uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception ignored) {
                        }
                        MediaLibrary.saveSubtitle(this, playlist.get(index).uri, uri.toString());
                        try {
                            mp.addSlave(IMedia.Slave.Type.Subtitle, uri, true);
                            Toast.makeText(this, "已加载字幕", Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {
                            Toast.makeText(this, "字幕加载失败", Toast.LENGTH_SHORT).show();
                        }
                        showOverlay();
                    });

    private static String formatSpeed(float s) {
        if (Math.abs(s - Math.round(s)) < 0.001f) return ((int) s) + ".0X";
        return String.format(Locale.US, "%.2f", s).replaceAll("0$", "") + "X";
    }

    private void showVideoInfo() {
        android.util.Log.i("RGPlayerVLC", "showVideoInfo");
        StringBuilder sb = new StringBuilder();
        VideoItem item = playlist.get(index);
        sb.append("文件：").append(item.name).append("\n");
        String path = currentPath;
        sb.append("路径：").append(path != null ? path : item.uri).append("\n");
        try {
            if (path != null) {
                java.io.File f = new java.io.File(path);
                if (f.exists()) sb.append("大小：").append(f.length() / 1024 / 1024).append(" MB\n");
            }
        } catch (Throwable ignored) {
        }
        sb.append("时长：").append(formatDuration(mp.getLength())).append("\n");
        sb.append("内核：libVLC ").append(LibVLC.version()).append("\n");
        sb.append("解码：硬件优先，失败回退软解\n");

        try {
            IMedia m = mp.getMedia();
            if (m != null) {
                IMedia.Stats st = m.getStats();
                if (st != null) {
                    sb.append(String.format(Locale.US, "输入码率：%.1f Mbps\n", st.inputBitrate * 8f));
                    sb.append("已解码视频帧：").append(st.decodedVideo).append("\n");
                    sb.append("已显示帧：").append(st.displayedPictures)
                            .append("，丢帧：").append(st.lostPictures).append("\n");
                    sb.append("已解码音频帧：").append(st.decodedAudio)
                            .append("，丢失：").append(st.lostAbuffers).append("\n");
                }
            }
        } catch (Throwable ignored) {
        }

        MediaPlayer.TrackDescription[] v = mp.getVideoTracks();
        MediaPlayer.TrackDescription[] a = mp.getAudioTracks();
        MediaPlayer.TrackDescription[] s = mp.getSpuTracks();
        int curA = mp.getAudioTrack();
        int curS = mp.getSpuTrack();

        sb.append("\n【视频】\n");
        if (v != null) {
            for (MediaPlayer.TrackDescription t : v) {
                sb.append((t.id == mp.getVideoTrack() ? "✓ " : "")).append(t.name).append("\n");
            }
        }
        sb.append("\n【音频】\n");
        if (a != null) {
            for (MediaPlayer.TrackDescription t : a) {
                sb.append((t.id == curA ? "✓ " : "")).append(t.name).append("\n");
            }
        }
        sb.append("\n【字幕】\n");
        if (s != null) {
            for (MediaPlayer.TrackDescription t : s) {
                sb.append((t.id == curS ? "✓ " : "")).append(t.name).append("\n");
            }
        } else {
            sb.append("无\n");
        }

        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(12);
        tv.setTextColor(0xFFE6E9EF);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setLineSpacing(2f, 1.15f);
        int pad = dp(18);
        tv.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(tv);
        sc.setBackgroundResource(R.drawable.bg_info);

        FrameLayout card = new FrameLayout(this);
        card.setBackgroundResource(R.drawable.bg_info);
        card.setClipToOutline(true);
        int cardW = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.8f);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(cardW, maxH);
        clp.gravity = android.view.Gravity.CENTER;
        card.addView(sc, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        clp.gravity = android.view.Gravity.CENTER;

        FrameLayout infoOverlay = new FrameLayout(this);
        infoOverlay.setBackgroundColor(0x99000000);
        infoOverlay.setClickable(true);
        infoOverlay.addView(card, clp);
        infoOverlay.setOnClickListener(vv -> ((ViewGroup) infoOverlay.getParent()).removeView(infoOverlay));

        ViewGroup root = findViewById(android.R.id.content);
        root.addView(infoOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        android.util.Log.i("RGPlayerVLC", "info overlay added");
    }

    // ---------------- 手势 ----------------

    private boolean onTouch(View v, MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN || e.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
            if (e.getPointerCount() >= 2) {
                if (e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
                    pinching = true;
                    gestureUsed = true;
                    baseDist = pointerDist(e);
                    baseZoom = zoom;
                    baseFocusX = (e.getX(0) + e.getX(1)) / 2f;
                    baseFocusY = (e.getY(0) + e.getY(1)) / 2f;
                }
            } else {
                pinching = false;
            }
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downTime = System.currentTimeMillis();
                gestureUsed = false;
                gestureMode = 0;
                scrubbing = false;
                edgeGesture = downX < dp(28) || downX > v.getWidth() - dp(28);
                startBrightness = currentBrightness();
                startVolume = appVolume;
                if (overlay.getVisibility() == View.VISIBLE && !menuOpen) scheduleHide();
                handler.removeCallbacks(boostStartTask);
                if (!edgeGesture) handler.postDelayed(boostStartTask, 550);
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (pinching && e.getPointerCount() >= 2) {
                    float d = pointerDist(e);
                    zoom = Math.max(1f, Math.min(4f, baseZoom * (d / Math.max(1f, baseDist))));
                    try {
                        mp.setScale(zoom <= 1.01f ? 0f : zoom);
                    } catch (Throwable ignored) {
                    }
                    showChip("缩放 " + Math.round(zoom * 100) + "%", 500);
                    updateResetButton();
                    return true;
                }
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (edgeGesture) {
                    if (Math.abs(dx) > dp(48) && Math.abs(dx) > Math.abs(dy)) {
                        finish();
                        return true;
                    }
                    return false;
                }
                if (boosting) {
                    float factor = dx / Math.max(1, v.getWidth());
                    float target = Math.max(0.5f, Math.min(4f, boostBase + factor * 3f));
                    rate = Math.round(target * 10f) / 10f;
                    mp.setRate(rate);
                    showChip("速度 " + formatSpeed(rate), 600);
                    return true;
                }
                if (Math.abs(dx) > dp(24) || Math.abs(dy) > dp(24)) {
                    handler.removeCallbacks(boostStartTask);
                    if (gestureMode == 0) {
                        if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > dp(40)) gestureMode = 1;
                        else if (Math.abs(dx) > Math.abs(dy)) gestureMode = 2;
                    }
                    if (gestureMode == 1) {
                        gestureUsed = true;
                        float w = v.getWidth();
                        float factor = -dy / Math.max(1, v.getHeight());
                        if (downX < w / 4f) {
                            applyBrightness(startBrightness + factor * 0.9f);
                        } else if (downX > w * 3f / 4f) {
                            appVolume = Math.max(0f, Math.min(1f, startVolume + factor));
                            mp.setVolume((int) (appVolume * 100));
                            showChip("音量 " + Math.round(appVolume * 100) + "%", 600);
                        }
                        return true;
                    }
                    if (gestureMode == 2) {
                        if (!scrubbing) {
                            scrubbing = true;
                            gestureUsed = true;
                            lastPreviewMs = -1;
                            previewPanel.setVisibility(View.VISIBLE);
                            showOverlay();
                            scrubStartMs = Math.max(0, mp.getTime());
                        }
                        long dur = mp.getLength();
                        if (dur > 0) {
                            scrubTargetMs = Math.max(0, Math.min(dur,
                                    scrubStartMs + (long) (dx / Math.max(1, v.getWidth()) * dur * 0.6)));
                            timeNow.setText(formatDuration(scrubTargetMs));
                            seek.setProgress((int) (scrubTargetMs * 1000 / Math.max(1, dur)));
                            requestPreview(scrubTargetMs);
                        }
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                handler.removeCallbacks(boostStartTask);
                if (boosting) {
                    boosting = false;
                    hideHint();
                    savePosition();
                    return true;
                }
                if (scrubbing) {
                    scrubbing = false;
                    mp.setTime(scrubTargetMs);
                    previewPanel.setVisibility(View.GONE);
                    scheduleHide();
                    return true;
                }
                if (menuOpen) return true;
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                long dt = System.currentTimeMillis() - downTime;
                if (!gestureUsed && Math.abs(dx) < dp(24) && Math.abs(dy) < dp(24) && dt < 300) {
                    long now = System.currentTimeMillis();
                    if (now - lastTap < 350) {
                        togglePlay();
                        lastTap = 0;
                    } else {
                        lastTap = now;
                        if (overlay.getVisibility() == View.VISIBLE) setOverlayVisible(false);
                        else showOverlay();
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP:
                pinching = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(boostStartTask);
                boosting = false;
                scrubbing = false;
                gestureMode = 0;
                handler.removeCallbacks(hideHintTask);
                brightnessHint.setVisibility(View.GONE);
                previewPanel.setVisibility(View.GONE);
                return true;

            default:
                return false;
        }
    }

    private final Runnable boostStartTask = () -> {
        if (menuOpen) return;
        boosting = true;
        boostBase = rate;
        showChip("滑动调速 " + formatSpeed(rate), 800);
    };

    private void hideHint() {
        handler.removeCallbacks(hideHintTask);
        brightnessHint.setVisibility(View.GONE);
    }

    private void requestPreview(long ms) {
        if (lastPreviewMs >= 0 && Math.abs(ms - lastPreviewMs) < 2000) return;
        lastPreviewMs = ms;
        final long target = ms;
        final Uri uri = playlist.get(index).uri;
        final long dur = mp.getLength();
        new Thread(() -> {
            android.graphics.Bitmap bmp = ThumbCache.nearest(this, uri, target, dur);
            if (bmp == null) return;
            runOnUiThread(() -> {
                previewImage.setImageBitmap(bmp);
                previewTime.setText(formatDuration(target));
            });
        }).start();
    }

    private float currentBrightness() {
        float b = getWindow().getAttributes().screenBrightness;
        if (b > 0) return b;
        try {
            int v = android.provider.Settings.System.getInt(getContentResolver(),
                    android.provider.Settings.System.SCREEN_BRIGHTNESS);
            return v / 255f;
        } catch (Exception e) {
            return 0.5f;
        }
    }

    private void applyBrightness(float v) {
        float b = Math.max(0.02f, Math.min(1f, v));
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b;
        getWindow().setAttributes(lp);
        brightnessHint.setText("亮度 " + Math.round(b * 100) + "%");
        brightnessHint.setVisibility(View.VISIBLE);
    }

    // ---------------- 按键 ----------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (menuOpen) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (menuBack != null) menuBack.run();
                else dismissMenu(true);
                return true;
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_SPACE:
                togglePlay();
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                finish();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (menuOpen) {
                    if (menuBack != null) menuBack.run();
                    else dismissMenu(true);
                } else {
                    showMenu();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (mp != null) mp.setTime(Math.max(0, mp.getTime() - 10000));
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (mp != null) mp.setTime(mp.getTime() + 10000);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                switchBy(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                switchBy(1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                switchBy(-1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                switchBy(1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    // ---------------- 生命周期 ----------------

    @Override
    protected void onPause() {
        super.onPause();
        if (mp != null) {
            savePosition();
            mp.pause();
        }
    }

    @Override
    protected void onDestroy() {
        savePosition();
        handler.removeCallbacksAndMessages(null);
        if (mp != null) {
            try {
                mp.stop();
                mp.detachViews();
            } catch (Throwable ignored) {
            }
            mp.release();
        }
        if (libVLC != null) libVLC.release();
        if (keepFd != null) {
            try {
                keepFd.close();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    // ---------------- 工具 ----------------

    private static String stripExtension(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private static float pointerDist(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    static String formatDuration(long ms) {
        if (ms < 0) ms = 0;
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }
}
