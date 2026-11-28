package com.rg.player;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Virtualizer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 播放页：Media3 播放 + 自研覆盖层 / 手势 / 菜单。 */
public class PlayerActivity extends AppCompatActivity {

    private static final long HIDE_DELAY_MS = 3200;
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f, 4f};
    private static final String[] SPEED_LABELS = {"0.5X", "0.75X", "1.0X", "1.25X", "1.5X", "2.0X", "2.5X", "3.0X", "4.0X"};

    private ExoPlayer player;
    private int filterIndex;
    private LcdGridView lcdGrid;
    private static final String[] FILTER_NAMES = {
            "关闭滤镜", "GBA LCD", "GBA 点阵", "GBA 扫描线"
    };
    private PlayerView playerView;
    private View overlay;
    private TextView btnReset;
    private androidx.media3.ui.SubtitleView subtitleView;
    private TextView btnPlay;
    private TextView btnPrev;
    private TextView btnNext;
    private TextView timeNow;
    private TextView timeTotal;
    private TextView titleView;
    private TextView brightnessHint;
    private SeekBar seek;

    private List<VideoItem> playlist;
    private int index;
    private int resizeMode;
    private boolean textEnabled = true;
    private boolean hasTextTracks;
    private Uri currentSubUri;
    private ActivityResultLauncher<String[]> subtitlePicker;
    private Settings settings;

    private LoudnessEnhancer loudnessEnhancer;
    private Virtualizer virtualizer;
    private float currentGainDb;
    private final ExecutorService analysisPool = Executors.newSingleThreadExecutor();

    private FrameLayout menuLayer;
    private LinearLayout menuCard;
    private LinearLayout menuItems;
    private ScrollView menuScroll;
    private boolean menuOpen;
    private final java.util.List<TextView> menuRows = new java.util.ArrayList<>();
    private final java.util.List<String> menuLabels = new java.util.ArrayList<>();
    private int menuSelected;
    private java.util.function.IntConsumer menuPick;
    private Runnable menuBack;
    private android.animation.ValueAnimator menuAnim;
    private final Runnable menuAutoHide = () -> {
        boolean controls = overlay != null && overlay.getVisibility() == View.VISIBLE;
        dismissMenu(true);
        if (controls) setOverlayVisible(false);
    };

    private ImageView previewImage;
    private TextView previewTime;
    private View previewPanel;
    private android.media.MediaMetadataRetriever previewRetriever;
    private Uri previewUri;
    private int previewSeq;
    private long lastPreviewMs = -1;
    private final ExecutorService previewPool = Executors.newSingleThreadExecutor();
    private final ExecutorService barPool = Executors.newSingleThreadExecutor();

    private String pendingTextKey;
    private String pendingAudioKey;
    private boolean tracksRestored;

    private long resumeMs;
    private boolean resumeApplied;

    private float zoom = 1f;
    private float panX, panY;
    private boolean pinching;
    private float baseZoom = 1f, baseDist = 1f, baseFocusX, baseFocusY, basePanX, basePanY;
    private float rotationDeg, baseRotation, baseAngle;
    private long startOverrideMs = -1;
    private float autoCropScale = 1f;
    private android.animation.ValueAnimator resetAnim;

    private boolean boosting;
    private float boostBase = 1f;

    private boolean scrubbing;
    private int gestureMode;
    private boolean edgeGesture;
    private long scrubStartMs, scrubTargetMs;

    private boolean gestureUsed;
    private long lastTap;
    private long lastSwitch;
    private float downX, downY;
    private long downTime;
    private float startBrightness;
    private float startPlayerVolume = 1f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideTask = () -> setOverlayVisible(false);
    private final Runnable progressTask = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(progressTask, 400);
        }
    };
    private final Runnable boostStartTask = () -> {
        if (player != null && !gestureUsed) startBoost();
    };
    private final Runnable hideChipTask = () -> {
        if (brightnessHint != null) brightnessHint.setVisibility(View.GONE);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        FontApplier.apply(this, findViewById(android.R.id.content));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat ic = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        ic.hide(WindowInsetsCompat.Type.systemBars());
        ic.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        overlay = findViewById(R.id.overlay);
        btnReset = findViewById(R.id.btn_reset);
        btnReset.setOnClickListener(v -> animateReset());
        playerView = findViewById(R.id.player_view);
        btnPlay = findViewById(R.id.btn_play);
        btnPrev = findViewById(R.id.btn_prev);
        btnNext = findViewById(R.id.btn_next);
        timeNow = findViewById(R.id.time_now);
        timeTotal = findViewById(R.id.time_total);
        titleView = findViewById(R.id.player_title);
        brightnessHint = findViewById(R.id.brightness_hint);
        lcdGrid = findViewById(R.id.lcd_grid);
        filterIndex = Math.min(Settings.getFilter(this), FILTER_NAMES.length - 1);
        if (player != null) applyFilter();
        seek = findViewById(R.id.seek);
        menuLayer = findViewById(R.id.menu_layer);
        menuCard = findViewById(R.id.menu_card);
        menuItems = findViewById(R.id.menu_items);
        menuScroll = findViewById(R.id.menu_scroll);
        previewImage = findViewById(R.id.preview_image);
        previewTime = findViewById(R.id.preview_time);
        previewPanel = findViewById(R.id.preview_panel);
        menuLayer.setOnClickListener(v -> dismissMenu(true));
        titleView.setSelected(true);
        // 全手柄操作：屏蔽控件焦点，避免方向键点亮返回/按钮
        ((android.view.ViewGroup) overlay).setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        for (int id : new int[]{R.id.btn_back, R.id.btn_play, R.id.btn_prev, R.id.btn_next, R.id.seek}) {
            View fv = findViewById(id);
            if (fv != null) {
                fv.setFocusable(false);
                fv.setFocusableInTouchMode(false);
            }
        }

        startOverrideMs = getIntent() == null ? -1 : getIntent().getLongExtra("start_ms", -1);
        playlist = PlaylistHolder.items();
        index = PlaylistHolder.index();
        if (playlist == null || playlist.isEmpty()) {
            finish();
            return;
        }

        subtitlePicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            VideoItem item = playlist.get(index);
            if (item != null) MediaLibrary.saveSubtitle(this, item.uri, uri.toString());
            applySubtitle(uri);
        });

        settings = Settings.load(this);
        resizeMode = settings.resize % 2;
        textEnabled = Settings.getTextEnabled(this);

        DefaultRenderersFactory rf = new DefaultRenderersFactory(this);
        rf.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
        player = new ExoPlayer.Builder(this, rf).build();
        applyFilter();
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build(), true);
        player.setHandleAudioBecomingNoisy(true);
        playerView.setPlayer(player);
        applyResizeMode(false);
        androidx.media3.ui.SubtitleView sv = playerView.getSubtitleView();
        if (sv != null) {
            sv.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            androidx.media3.ui.CaptionStyleCompat style = new androidx.media3.ui.CaptionStyleCompat(
                    android.graphics.Color.WHITE,
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                    androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    android.graphics.Color.BLACK, null);
            sv.setStyle(style);
            sv.setApplyEmbeddedStyles(false);
            sv.setBottomPaddingFraction(0.08f);
            subtitleView = sv;
        }

        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean playing) {
                btnPlay.setText(playing ? "❚❚" : "▶");
            }

            @Override
            public void onAudioSessionIdChanged(int sessionId) {
                applyAudioEffects(sessionId);
            }

            @Override
            public void onRenderedFirstFrame() {
                if (subtitleView != null) {
                    subtitleView.animate().alpha(1f).setDuration(180).start();
                            }
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                hasTextTracks = tracks.containsType(C.TRACK_TYPE_TEXT);
                if (!tracksRestored) {
                    tracksRestored = true;
                    restoreTracks(tracks);
                }
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                Toast.makeText(PlayerActivity.this, "解码失败，改用 VLC 内核", Toast.LENGTH_SHORT).show();
                try {
                    player.stop();
                } catch (Throwable ignored) {
                }
                startActivity(new android.content.Intent(PlayerActivity.this, VlcPlayerActivity.class));
                finish();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    if (!resumeApplied && resumeMs > 1000) {
                        player.seekTo(resumeMs);
                        showChip("从 " + VideoAdapter.formatDuration(resumeMs) + " 继续", 1400);
                    }
                    resumeApplied = true;
                    if (startOverrideMs > 0) {
                        player.seekTo(startOverrideMs);
                        startOverrideMs = -1;
                    }
                    applyAudioEffects(player.getAudioSessionId());
                    updateProgress();
                } else if (state == Player.STATE_ENDED) {
                    btnPlay.setText("↻");
                    if (settings.resume) savePosition(true);
                    if (settings.autoNext && index + 1 < playlist.size()) {
                        play(index + 1);
                    } else {
                        finish();
                    }
                }
            }
        });

        addCoreChip("核心：Media3", () -> {
            long pos = 0;
            try {
                pos = player.getCurrentPosition();
            } catch (Throwable ignored) {
            }
            Intent i = new Intent(this, VlcPlayerActivity.class);
            i.putExtra("start_ms", pos);
            startActivity(i);
            finish();
        });

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnPlay.setOnClickListener(v -> togglePlay());
        btnPlay.setOnLongClickListener(v -> {
            showMenu();
            return true;
        });
        btnPrev.setOnClickListener(v -> switchBy(-1));
        btnNext.setOnClickListener(v -> switchBy(1));
        findViewById(R.id.touch_catcher).setOnTouchListener(this::onTouch);

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                long dur = player.getDuration();
                if (fromUser && dur > 0) {
                    long ms = dur * p / 1000;
                    timeNow.setText(VideoAdapter.formatDuration(ms));
                    requestPreview(ms);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                handler.removeCallbacks(hideTask);
                lastPreviewMs = -1;
                if (previewPanel != null) previewPanel.setVisibility(View.VISIBLE);
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                long dur = player.getDuration();
                if (dur > 0) player.seekTo(dur * sb.getProgress() / 1000);
                if (previewPanel != null) previewPanel.setVisibility(View.GONE);
                scheduleHide();
            }
        });

        play(index);
        overlay.setAlpha(0f);
        overlay.setVisibility(View.INVISIBLE);
    }

    // ---------------- 播放 ----------------

    private void play(int i) {
        if (playlist == null || playlist.isEmpty()) return;
        index = Math.max(0, Math.min(i, playlist.size() - 1));
        PlaylistHolder.setIndex(index);
        VideoItem item = playlist.get(index);
        resumeMs = settings.resume ? MediaLibrary.position(this, item.uri) : 0;
        resumeApplied = false;
        titleView.setText(stripExtension(item.name));
        btnPrev.setAlpha(index > 0 ? 1f : 0.35f);
        btnNext.setAlpha(index + 1 < playlist.size() ? 1f : 0.35f);
        zoom = 1f;
        rotationDeg = 0f;
        panX = 0;
        panY = 0;
        autoCropScale = 1f;

        resetLoudness();
        if (Settings.getLoudness(this)) {
            if (Settings.hasLoudGain(this, item.uri)) {
                applyLoudness(Settings.getLoudGain(this, item.uri));
            } else {
                final VideoItem fi = item;
                analysisPool.execute(() -> {
                    float db = androidx.media3.decoder.ffmpeg.LoudnessAnalyzer.analyze(this, fi.uri, 60);
                    Settings.putLoudGain(this, fi.uri, db);
                    handler.post(() -> {
                        if (playlist != null && !playlist.isEmpty() && index < playlist.size()
                                && playlist.get(index).uri.equals(fi.uri)) {
                            applyLoudness(db);
                        }
                    });
                });
            }
        }

        pendingTextKey = Settings.getTrack(this, "txt", item.uri);
        pendingAudioKey = Settings.getTrack(this, "aud", item.uri);
        tracksRestored = false;
        currentSubUri = resolveSubtitle(item);

        if (subtitleView != null) {
            subtitleView.setAlpha(0f);
        }
        player.setMediaItem(buildMediaItem(item, currentSubUri));
        player.prepare();
        applyResizeMode(false);
        player.setPlayWhenReady(true);
        updateProgress();
        ThumbCache.ensure(this, item);
        analyzeBars(item);
    }

    private MediaItem buildMediaItem(VideoItem item, Uri subUri) {
        MediaItem.Builder b = new MediaItem.Builder().setUri(item.uri);
        if (subUri != null) {
            b.setSubtitleConfigurations(Collections.singletonList(
                    new MediaItem.SubtitleConfiguration.Builder(subUri)
                            .setMimeType(subMime(subUri))
                            .setLanguage("zh")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()));
        }
        return b.build();
    }

    private String subMime(Uri uri) {
        String p = uri.getLastPathSegment();
        p = p == null ? "" : p.toLowerCase();
        if (p.endsWith(".ass") || p.endsWith(".ssa")) return MimeTypes.TEXT_SSA;
        if (p.endsWith(".vtt")) return MimeTypes.TEXT_VTT;
        String t = getContentResolver().getType(uri);
        return t != null ? t : MimeTypes.APPLICATION_SUBRIP;
    }

    private Uri resolveSubtitle(VideoItem item) {
        String stored = MediaLibrary.subtitle(this, item.uri);
        if (stored != null) {
            Uri u = Uri.parse(stored);
            String scheme = u.getScheme();
            if (scheme != null && !scheme.startsWith("file")) return u;
            if (u.getPath() != null && new File(u.getPath()).exists()) return u;
        }
        File f = sideSubtitle(item);
        if (f != null) return Uri.fromFile(f);
        f = internalSubtitle(item);
        return f != null ? Uri.fromFile(f) : null;
    }

    private File sideSubtitle(VideoItem item) {
        String p = item.path;
        if (p == null) return null;
        int dot = p.lastIndexOf('.');
        if (dot <= 0) return null;
        String base = p.substring(0, dot);
        for (String ext : new String[]{".srt", ".SRT", ".ass", ".ssa", ".vtt"}) {
            File f = new File(base + ext);
            if (f.exists()) return f;
        }
        return null;
    }

    private File internalSubtitle(VideoItem item) {
        File dir = getExternalFilesDir("subtitles");
        if (dir == null) return null;
        String base = item.name;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        for (String ext : new String[]{".srt", ".ass", ".vtt"}) {
            File f = new File(dir, base + ext);
            if (f.exists()) return f;
        }
        return null;
    }

    private void pickSubtitle() {
        if (subtitlePicker != null) {
            subtitlePicker.launch(new String[]{"application/x-subrip", "text/*", "*/*"});
        }
    }

    private void applySubtitle(Uri subUri) {
        if (player == null || subUri == null) return;
        VideoItem item = playlist.get(index);
        long pos = player.getCurrentPosition();
        boolean playing = player.isPlaying();
        player.setMediaItem(buildMediaItem(item, subUri));
        player.prepare();
        player.seekTo(pos);
        player.setPlayWhenReady(playing);
        textEnabled = true;
        Settings.setTextEnabled(this, true);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
        currentSubUri = subUri;
        showOverlay();
        Toast.makeText(this, "已加载字幕", Toast.LENGTH_SHORT).show();
    }

    /** 沉浸模式：检测片源自带上下黑边，按比例裁掉。 */
    private void analyzeBars(VideoItem item) {
        barPool.execute(() -> {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            try {
                r.setDataSource(this, item.uri);
                long t = item.durationMs > 0 ? (long) (item.durationMs * 1000L * 0.3) : 10_000_000L;
                android.graphics.Bitmap bmp = r.getFrameAtTime(t,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (bmp == null) return;
                int h = bmp.getHeight();
                int top = 0;
                while (top < h / 3 && rowDark(bmp, top)) top++;
                int bottom = 0;
                while (bottom < h / 3 && rowDark(bmp, h - 1 - bottom)) bottom++;
                float frac = (top + bottom) / (float) h;
                float scale = (frac > 0.02f && frac < 0.45f)
                        ? Math.min(1.8f, 1f / Math.max(0.5f, 1f - frac)) : 1f;
                bmp.recycle();
                runOnUiThread(() -> {
                    autoCropScale = scale;
                    if (resizeMode == 1) applyZoom();
                });
            } catch (Throwable ignored) {
            } finally {
                try { r.release(); } catch (Throwable ignored) {}
            }
        });
    }

    private static boolean rowDark(android.graphics.Bitmap bmp, int y) {
        int w = bmp.getWidth();
        long sum = 0;
        int n = 0;
        int step = Math.max(1, w / 24);
        for (int x = 0; x < w; x += step) {
            int c = bmp.getPixel(x, y);
            sum += (int) (0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF));
            n++;
        }
        return n > 0 && sum / n < 14;
    }

    private static String stripExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ---------------- 控制层 ----------------

    /** 切集防抖：连续按只切一部。 */
    private void switchBy(int delta) {
        long now = System.currentTimeMillis();
        if (now - lastSwitch < 700) return;
        if (playlist == null || playlist.isEmpty()) return;
        int target = index + delta;
        if (target < 0) {
            lastSwitch = now;
            android.widget.Toast.makeText(this, "没有上一个视频",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (target >= playlist.size()) {
            lastSwitch = now;
            android.widget.Toast.makeText(this, "没有下一个视频",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        lastSwitch = now;
        play(target);
    }

    private void addCoreChip(String text, Runnable onClick) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(12);
        chip.setTextColor(0xFF35C8FF);
        chip.setPadding(dp(10), dp(6), dp(10), dp(6));
        chip.setBackgroundResource(R.drawable.bg_reset_pill);
        chip.setClickable(true);
        chip.setFocusable(false);
        chip.setOnClickListener(v -> onClick.run());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        lp.topMargin = dp(56);
        lp.rightMargin = dp(14);
        ((ViewGroup) findViewById(R.id.overlay)).addView(chip, lp);
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.getPlaybackState() == Player.STATE_ENDED) {
            player.seekTo(0);
            player.play();
        } else if (player.isPlaying()) {
            player.pause();
        } else {
            player.play();
        }
        showOverlay();
    }

    private void setOverlayVisible(boolean visible) {
        if (!visible && btnReset != null) btnReset.setVisibility(View.GONE);
        overlay.setVisibility(View.VISIBLE);
        overlay.animate().alpha(visible ? 1f : 0f).setDuration(160)
                .withEndAction(() -> overlay.setVisibility(visible ? View.VISIBLE : View.INVISIBLE))
                .start();
        updateResetBtn();
        if (visible) scheduleHide();
        else handler.removeCallbacks(hideTask);
        updateMenuPosition();


    }

    private void animateReset() {
        if (resetAnim != null) resetAnim.cancel();
        handler.removeCallbacks(hideTask);
        final float z0 = zoom, r0 = rotationDeg, px0 = panX, py0 = panY;
        resetAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(420);
        resetAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        resetAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            zoom = z0 + (1f - z0) * f;
            rotationDeg = r0 * (1f - f);
            panX = px0 * (1f - f);
            panY = py0 * (1f - f);
            applyZoom();
        });
        resetAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                zoom = 1f;
                rotationDeg = 0f;
                panX = 0f;
                panY = 0f;
                applyZoom();
                updateResetBtn();
                if (overlay != null && overlay.getVisibility() == View.VISIBLE) scheduleHide();
            }
        });
        resetAnim.start();
    }

    private void updateResetBtn() {
        if (btnReset == null) return;
        boolean active = Math.abs(zoom - 1f) > 0.01f || Math.abs(rotationDeg) > 0.5f
                || Math.abs(panX) > 0.5f || Math.abs(panY) > 0.5f;
        boolean overlayVisible = overlay != null && overlay.getVisibility() == View.VISIBLE;
        boolean show = active && overlayVisible;
        btnReset.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideTask);
        handler.postDelayed(hideTask, HIDE_DELAY_MS);
    }

    private void showOverlay() {
        setOverlayVisible(true);
    }

    private void updateProgress() {
        if (player == null || seek == null) return;
        if (scrubbing) return;
        long pos = Math.max(0, player.getCurrentPosition());
        long dur = player.getDuration();
        timeNow.setText(VideoAdapter.formatDuration(pos));
        if (dur > 0) {
            timeTotal.setText(VideoAdapter.formatDuration(dur));
            seek.setProgress((int) (pos * 1000 / dur));
        }
    }

    private void seekBy(long deltaMs) {
        if (player == null) return;
        long target = Math.max(0, player.getCurrentPosition() + deltaMs);
        long dur = player.getDuration();
        if (dur > 0) target = Math.min(target, dur);
        player.seekTo(target);
        updateProgress();
        showOverlay();
    }

    // ---------------- 菜单 ----------------

    private String quickTrackLabel(Format f) {
        if (f.language != null && !f.language.isEmpty()) {
            String l = f.language.toLowerCase(java.util.Locale.US);
            if (l.startsWith("zh")) return "中文";
            if (l.startsWith("en")) return "英语";
            if (l.startsWith("ja")) return "日语";
            if (l.startsWith("ko")) return "韩语";
            if (l.startsWith("sv")) return "瑞典语";
            return l;
        }
        if (f.label != null && !f.label.isEmpty()) return f.label;
        return "" + f.channelCount + "ch";
    }

    private String currentAudioLabel() {
        try {
            for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
                if (g.getType() != C.TRACK_TYPE_AUDIO || !g.isSelected()) continue;
                Format f = g.getMediaTrackGroup().getFormat(0);
                String s = quickTrackLabel(f);
                if (s != null && !s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {
        }
        return "默认";
    }

    private String currentSubtitleLabel() {
        try {
            for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
                if (g.getType() != C.TRACK_TYPE_TEXT || !g.isSelected()) continue;
                Format f = g.getMediaTrackGroup().getFormat(0);
                String s = quickTrackLabel(f);
                if (s != null && !s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {
        }
        return hasTextTracks ? "关闭" : "无";
    }

    private void applyFilter() {
        if (lcdGrid == null) return;
        lcdGrid.setVisibility(filterIndex <= 0 ? View.GONE : View.VISIBLE);
        lcdGrid.setStyle(filterIndex);
        if (player != null) {
            lcdGrid.setVideoSize(player.getVideoSize().width, player.getVideoSize().height);
        }
    }

    private void pickFilter(int idx) {
        filterIndex = Math.max(0, Math.min(FILTER_NAMES.length - 1, idx));
        Settings.putFilter(this, filterIndex);
        applyFilter();
        showChip("滤镜：" + FILTER_NAMES[filterIndex], 1000);
    }

    private void showFilterMenu() {
        showMenuPanel("滤镜", FILTER_NAMES, null, this::pickFilter);
        menuSelected = Math.max(0, Math.min(FILTER_NAMES.length - 1, filterIndex));
        updateMenuCursor();
    }

    private void showMenu() {
        final String[] items = {
                "画面比例：" + resizeLabel(),
                "播放速度：" + formatSpeed(player == null ? 1f : player.getPlaybackParameters().speed),
                "字幕：" + currentSubtitleLabel(),
                "音轨：" + currentAudioLabel(),
                "视频信息"
        };
        showMenuPanel("", items, null, which -> {
            switch (which) {
                case 0: cycleResizeMode(); break;
                case 1: chooseSpeed(); break;
                case 2: showSubtitleTracks(); break;
                case 3: showAudioTracks(); break;
                case 4: showVideoInfo(); break;
            }
        });
    }

    private void showMenuPanel(String header, String[] items, Runnable backAction,
                               java.util.function.IntConsumer pick) {
        menuBack = backAction;
        menuPick = pick;
        menuRows.clear();
        menuLabels.clear();
        menuSelected = 0;
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
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView row = new TextView(this);
            row.setText(items[i]);
            row.setTextSize(14);
            row.setTextColor(0xFFEEF0F4);
            row.setPadding(dp(16), dp(8), dp(16), dp(8));
            row.setOnClickListener(v -> {
                dismissMenu(false);
                if (menuPick != null) menuPick.accept(idx);
            });
            menuItems.addView(row);
            menuRows.add(row);
            menuLabels.add(items[i]);
        }
        if (!menuRows.isEmpty()) {
            menuSelected = 0;
            updateMenuCursor();
        }
        menuLayer.animate().cancel();
        menuLayer.setAlpha(1f);
        menuLayer.setVisibility(View.VISIBLE);
        menuOpen = true;
        menuCard.setAlpha(0f);
        menuCard.setTranslationY(dp(28));
        menuItems.post(() -> {
            int maxW = dp(200);
            int maxH = Math.max(dp(200), getWindow().getDecorView().getHeight()
                    - getMenuBottomMargin() - dp(24));
            menuCard.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
            int w = Math.max(dp(132), Math.min(menuCard.getMeasuredWidth(), maxW));
            menuCard.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menuCard.getLayoutParams();
            lp.width = w;
            lp.height = Math.min(menuCard.getMeasuredHeight(), maxH);
            lp.bottomMargin = getMenuBottomMargin();
            menuCard.setLayoutParams(lp);
            menuCard.animate().alpha(1f).translationY(0f).setDuration(170).start();
        });
        FontApplier.apply(this, menuCard);
        resetMenuTimer();
        handler.removeCallbacks(hideTask);
        menuScroll.setOnScrollChangeListener((v, x, y, ox, oy) -> resetMenuTimer());
        menuCard.setOnTouchListener((v, e) -> {
            resetMenuTimer();
            return false;
        });
    }

    private void updateMenuCursor() {
        for (int i = 0; i < menuRows.size(); i++) {
            TextView row = menuRows.get(i);
            boolean sel = i == menuSelected;
            row.setText(menuLabels.get(i));
            row.setTextColor(sel ? 0xFF35C8FF : 0xFFEEF0F4);
            row.setBackgroundResource(sel ? R.drawable.bg_row_focus : 0);
        }
        if (menuSelected < menuRows.size()) {
            TextView row = menuRows.get(menuSelected);
            try { menuScroll.smoothScrollTo(0, Math.max(0, row.getTop() - dp(8))); } catch (Throwable ignored) {}
        }
    }

    private void moveMenuCursor(int delta) {
        if (menuRows.isEmpty()) return;
        menuSelected = Math.max(0, Math.min(menuRows.size() - 1, menuSelected + delta));
        updateMenuCursor();
        resetMenuTimer();
    }

    private void activateMenuCursor() {
        if (menuSelected < 0 || menuSelected >= menuRows.size()) return;
        final int idx = menuSelected;
        dismissMenu(false);
        if (menuPick != null) menuPick.accept(idx);
    }

    private void resetMenuTimer() {
        handler.removeCallbacks(menuAutoHide);
        handler.removeCallbacks(hideTask);
        handler.postDelayed(menuAutoHide, HIDE_DELAY_MS);
    }

    private int getMenuBottomMargin() {
        boolean controls = overlay != null && overlay.getVisibility() == View.VISIBLE;
        return dp(controls ? 104 : 12);
    }

    private void updateMenuPosition() {
        if (!menuOpen) return;
        int target = getMenuBottomMargin();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menuCard.getLayoutParams();
        if (lp.bottomMargin == target) return;
        if (menuAnim != null) menuAnim.cancel();
        menuAnim = android.animation.ValueAnimator.ofInt(lp.bottomMargin, target);
        menuAnim.setDuration(220);
        menuAnim.addUpdateListener(a -> {
            FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) menuCard.getLayoutParams();
            l.bottomMargin = (int) a.getAnimatedValue();
            menuCard.setLayoutParams(l);
        });
        menuAnim.start();
    }

    private void dismissMenu(boolean animate) {
        menuOpen = false;
        handler.removeCallbacks(menuAutoHide);
        if (overlay != null && overlay.getVisibility() == View.VISIBLE) scheduleHide();
        if (menuLayer == null || menuLayer.getVisibility() != View.VISIBLE) return;
        if (!animate) {
            menuLayer.animate().cancel();
            menuLayer.setVisibility(View.GONE);
            menuLayer.setAlpha(1f);
            return;
        }
        final View layer = menuLayer;
        layer.animate().cancel();
        layer.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            layer.setVisibility(View.GONE);
            layer.setAlpha(1f);
        }).start();
    }

    private void cycleResizeMode() {
        resizeMode = (resizeMode + 1) % 2;
        applyResizeMode(true);
        showOverlay();
    }

    private String resizeLabel() {
        return resizeMode == 0 ? "原画" : "沉浸";
    }

    private void applyResizeMode(boolean toast) {
        int mode = resizeMode == 0
                ? AspectRatioFrameLayout.RESIZE_MODE_FIT
                : AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
        playerView.setResizeMode(mode);
        applyZoom();
        if (toast) Toast.makeText(this, resizeLabel(), Toast.LENGTH_SHORT).show();
    }

    private void chooseSpeed() {
        showMenuPanel("播放速度", SPEED_LABELS, this::showMenu, which ->
                applySpeed(SPEEDS[which]));
    }

    private void applySpeed(float speed) {
        if (player == null) return;
        player.setPlaybackSpeed(speed);
        showChip(formatSpeed(speed), 900);
    }

    private static String formatSpeed(float v) {
        String s = String.format(java.util.Locale.US, "%.2f", v);
        if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s + "X";
    }

    private void showVideoInfo() {
        if (player == null) return;
        StringBuilder sb = new StringBuilder();
        VideoItem item = playlist.get(index);
        sb.append("文件：").append(item.name).append("\n");
        sb.append("路径：").append(item.uri).append("\n");
        sb.append("容器：").append(containerOf(item.name)).append("\n");
        long dur = player.getDuration();
        sb.append("时长：").append(dur > 0 ? VideoAdapter.formatDuration(dur) : "-").append("\n\n");

        android.media.MediaFormat vf = null;
        Format video = player.getVideoFormat();
        if (video != null) {
            sb.append("【视频】\n");
            sb.append("编解码器：").append(nz(video.sampleMimeType)).append("\n");
            if (video.codecs != null) sb.append("codecs：").append(video.codecs).append("\n");
            sb.append("分辨率：").append(video.width).append("x").append(video.height).append("\n");
            if (video.frameRate > 0) sb.append("帧率：").append(String.format(java.util.Locale.US, "%.3f", video.frameRate)).append(" fps\n");
            if (video.bitrate > 0) sb.append("码率：").append(video.bitrate / 1000).append(" kbps\n");
            if (video.colorInfo != null) {
                androidx.media3.common.ColorInfo ci = video.colorInfo;
                sb.append("色彩：space=").append(ci.colorSpace)
                        .append(" transfer=").append(ci.colorTransfer)
                        .append(" range=").append(ci.colorRange).append("\n");
                if (ci.hdrStaticInfo != null && ci.hdrStaticInfo.length > 0) sb.append("HDR静态信息：有\n");
            }
            if (video.pixelWidthHeightRatio != 0) {
                sb.append("像素比：").append(video.pixelWidthHeightRatio).append("\n");
            }
            sb.append("解码器：").append(decoderNames(video.sampleMimeType)).append("\n");
            sb.append("\n");
        }

        Format audio = player.getAudioFormat();
        if (audio != null) {
            sb.append("【音频】\n");
            sb.append("编解码器：").append(nz(audio.sampleMimeType)).append("\n");
            sb.append("声道：").append(audio.channelCount).append("\n");
            sb.append("采样率：").append(audio.sampleRate).append(" Hz\n");
            if (audio.bitrate > 0) sb.append("码率：").append(audio.bitrate / 1000).append(" kbps\n");
            if (audio.language != null) sb.append("语言：").append(audio.language).append("\n");
            sb.append("解码器：").append(decoderNames(audio.sampleMimeType)).append("\n");
            sb.append("\n");
        }

        sb.append("【字幕】\n");
        int textCount = 0;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                Format f = g.getTrackFormat(i);
                textCount++;
                sb.append("字幕 ").append(textCount).append("：")
                        .append(nz(f.sampleMimeType));
                if (f.language != null) sb.append(" · ").append(f.language);
                sb.append(g.isTrackSelected(i) ? "（已选）" : "").append("\n");
            }
        }
        if (textCount == 0) sb.append("无内嵌字幕").append("\n");
        sb.append("\n");

        sb.append("【渲染器】\n");
        for (int i = 0; i < player.getRendererCount(); i++) {
            sb.append(player.getRendererType(i)).append("：")
                    .append(player.getRenderer(i).getName()).append("\n");
        }
        sb.append("\n播放状态：").append(player.isPlaying() ? "播放中" : "暂停")
                .append("，缓冲：").append(player.getBufferedPosition() / 1000).append(" s\n");
        sb.append("丢帧/解码：见统计（AnalyticsCollector 未接入）\n");

        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(12);
        tv.setTextColor(0xFFE6E9EF);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setLineSpacing(2f, 1.15f);
        int pad = dp(18);
        tv.setPadding(pad, pad, pad, pad);

        android.widget.ScrollView sc = new android.widget.ScrollView(
                new android.view.ContextThemeWrapper(this, R.style.RGScroll));
        sc.addView(tv);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.80f);
        sc.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, maxH));

        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setBackgroundResource(R.drawable.bg_info);
        box.setClipToOutline(true);
        box.addView(sc, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dlg.setContentView(box);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dlg.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            dlg.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
            lp.dimAmount = 0.2f;
            dlg.getWindow().setAttributes(lp);
        }
        final float[] downPt = new float[2];
        sc.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                downPt[0] = ev.getX();
                downPt[1] = ev.getY();
            } else if (ev.getAction() == android.view.MotionEvent.ACTION_UP) {
                float dx = Math.abs(ev.getX() - downPt[0]);
                float dy = Math.abs(ev.getY() - downPt[1]);
                if (dx < dp(10) && dy < dp(10)) {
                    dlg.dismiss();
                    return true;
                }
            }
            return false;
        });
        dlg.show();
    }

    private static String containerOf(String name) {
        if (name == null) return "-";
        int i = name.lastIndexOf('.');
        String ext = i >= 0 ? name.substring(i + 1).toLowerCase(java.util.Locale.US) : "";
        switch (ext) {
            case "mkv": return "Matroska (mkv)";
            case "mp4": case "m4v": return "MP4";
            case "mov": return "QuickTime (mov)";
            case "avi": return "AVI";
            case "ts": case "m2ts": return "MPEG-TS";
            case "webm": return "WebM";
            case "flv": return "FLV";
            case "wmv": return "WMV";
            case "rmvb": return "RealMedia";
            default: return ext.isEmpty() ? "-" : ext;
        }
    }

    private static String nz(String s) {
        return s == null ? "-" : s;
    }

    private String decoderNames(String mime) {
        if (mime == null) return "-";
        try {
            java.util.List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> infos =
                    androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, false, false);
            if (infos.isEmpty()) return "FFmpeg 扩展（软件）";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(2, infos.size()); i++) {
                if (i > 0) sb.append(" / ");
                sb.append(infos.get(i).name);
                sb.append(infos.get(i).hardwareAccelerated ? "（硬解）" : "（软解）");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "未知";
        }
    }

    private void applyZoom() {
        View frame = null;
        for (int i = 0; i < playerView.getChildCount(); i++) {
            View c = playerView.getChildAt(i);
            if (c instanceof AspectRatioFrameLayout) {
                frame = c;
                break;
            }
        }
        if (frame == null) frame = playerView.getVideoSurfaceView();
        if (frame == null) return;
        float s = Math.max(0.5f, Math.min(4f, zoom));
        frame.setScaleX(s);
        frame.setScaleY(s);
        frame.setRotation(rotationDeg);
        frame.setTranslationX(panX);
        frame.setTranslationY(panY);
        if (subtitleView != null) {
            subtitleView.setScaleX(1f / s);
            subtitleView.setScaleY(1f / s);
            subtitleView.setRotation(-rotationDeg);
            subtitleView.setTranslationX(-panX);
            subtitleView.setTranslationY(-panY);
        }
        updateResetBtn();
    }

    // ---------------- 轨道 ----------------

    private static String trackKey(Format f) {
        return (f.language == null ? "" : f.language) + "|" + (f.label == null ? "" : f.label);
    }

    private void showAudioTracks() {
        final List<TrackGroup> groups = new ArrayList<>();
        final List<Integer> indexes = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < g.length; i++) {
                Format f = g.getTrackFormat(i);
                StringBuilder sb = new StringBuilder();
                if (g.isTrackSelected(i)) sb.append("✓ ");
                sb.append("音轨 ").append(labels.size() + 1);
                if (f.language != null) sb.append(" · ").append(f.language);
                if (f.channelCount > 0) sb.append(" · ").append(f.channelCount).append("ch");
                groups.add(g.getMediaTrackGroup());
                indexes.add(i);
                labels.add(sb.toString());
                keys.add(trackKey(f));
            }
        }
        if (labels.isEmpty()) {
            Toast.makeText(this, "当前没有可选音轨", Toast.LENGTH_SHORT).show();
            return;
        }
        showMenuPanel("音轨", labels.toArray(new String[0]), this::showMenu, which -> {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .addOverride(new TrackSelectionOverride(groups.get(which), indexes.get(which)))
                    .build());
            Settings.putTrack(this, "aud", playlist.get(index).uri, keys.get(which));
            Toast.makeText(this, "已切换音轨", Toast.LENGTH_SHORT).show();
            showOverlay();
        });
    }

    private void showSubtitleTracks() {
        final List<TrackGroup> groups = new ArrayList<>();
        final List<Integer> indexes = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        labels.add("关闭字幕");
        keys.add("off");
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                Format f = g.getTrackFormat(i);
                StringBuilder sb = new StringBuilder();
                if (g.isTrackSelected(i)) sb.append("✓ ");
                sb.append("字幕 ").append(labels.size());
                if (f.language != null) sb.append(" · ").append(f.language);
                groups.add(g.getMediaTrackGroup());
                indexes.add(i);
                labels.add(sb.toString());
                keys.add(trackKey(f));
            }
        }
        if (labels.size() == 1) {
            Toast.makeText(this, "当前没有可用字幕轨道", Toast.LENGTH_SHORT).show();
            return;
        }
        labels.add("加载字幕文件…");
        showMenuPanel("字幕", labels.toArray(new String[0]), this::showMenu, which -> {
            if (which == labels.size() - 1) {
                pickSubtitle();
                return;
            }
            if (which == 0) {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
                textEnabled = false;
            } else {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .addOverride(new TrackSelectionOverride(groups.get(which - 1), indexes.get(which - 1)))
                        .build());
                textEnabled = true;
            }
            Settings.putTrack(this, "txt", playlist.get(index).uri, keys.get(which));
            showOverlay();
        });
    }

    private void restoreTracks(Tracks tracks) {
        boolean wantOff = "off".equals(pendingTextKey);
        boolean restoredText = false;
        if (pendingTextKey != null && !wantOff) {
            outer:
            for (Tracks.Group g : tracks.getGroups()) {
                if (g.getType() != C.TRACK_TYPE_TEXT) continue;
                for (int i = 0; i < g.length; i++) {
                    if (pendingTextKey.equals(trackKey(g.getTrackFormat(i)))) {
                        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .addOverride(new TrackSelectionOverride(g.getMediaTrackGroup(), i))
                                .build());
                        textEnabled = true;
                        restoredText = true;
                        break outer;
                    }
                }
            }
        }
        if (!restoredText && !wantOff && hasTextTracks) {
            selectDefaultTextTrack(tracks);
        }
        if (wantOff) {
            textEnabled = false;
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        }
        if (pendingAudioKey != null) {
            outer2:
            for (Tracks.Group g : tracks.getGroups()) {
                if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
                for (int i = 0; i < g.length; i++) {
                    if (pendingAudioKey.equals(trackKey(g.getTrackFormat(i)))) {
                        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                .addOverride(new TrackSelectionOverride(g.getMediaTrackGroup(), i))
                                .build());
                        break outer2;
                    }
                }
            }
        }
    }

    private void selectDefaultTextTrack(Tracks tracks) {
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            int pick = 0;
            for (int i = 0; i < g.length; i++) {
                androidx.media3.common.Format f = g.getTrackFormat(i);
                if ((f.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0
                        || (f.selectionFlags & C.SELECTION_FLAG_FORCED) != 0) {
                    pick = i;
                    break;
                }
            }
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .addOverride(new TrackSelectionOverride(g.getMediaTrackGroup(), pick))
                    .build());
            textEnabled = true;
            return;
        }
    }

    // ---------------- 音频效果 ----------------

    private void applyAudioEffects(int sessionId) {
        releaseAudioEffects();
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return;
        if (Settings.getStereo(this)) {
            try {
                virtualizer = new Virtualizer(0, sessionId);
                virtualizer.setStrength((short) 800);
                virtualizer.setEnabled(true);
            } catch (Exception e) {
                virtualizer = null;
            }
        }
        if (Settings.getLoudness(this)) applyLoudness(currentGainDb);
    }

    private void ensureEnhancer() {
        if (player == null) return;
        int session = player.getAudioSessionId();
        if (session == C.AUDIO_SESSION_ID_UNSET || loudnessEnhancer != null) return;
        try {
            loudnessEnhancer = new LoudnessEnhancer(session);
            loudnessEnhancer.setEnabled(true);
        } catch (Exception e) {
            loudnessEnhancer = null;
        }
    }

    private void applyLoudness(float gainDb) {
        if (player == null) return;
        currentGainDb = gainDb;
        float g = (float) Math.pow(10, gainDb / 20.0);
        if (g <= 1f) {
            player.setVolume(g);
            if (loudnessEnhancer != null) {
                try { loudnessEnhancer.setTargetGain(0); } catch (Exception ignored) {}
            }
        } else {
            player.setVolume(1f);
            ensureEnhancer();
            if (loudnessEnhancer != null) {
                try {
                    loudnessEnhancer.setTargetGain(Math.round((g - 1f) * 1000f));
                } catch (Exception ignored) {}
            }
        }
    }

    private void resetLoudness() {
        currentGainDb = 0f;
        if (player != null) player.setVolume(1f);
        if (loudnessEnhancer != null) {
            try { loudnessEnhancer.setTargetGain(0); } catch (Exception ignored) {}
        }
    }

    private void releaseAudioEffects() {
        if (loudnessEnhancer != null) {
            try {
                loudnessEnhancer.setEnabled(false);
                loudnessEnhancer.release();
            } catch (Exception ignored) {}
            loudnessEnhancer = null;
        }
        if (virtualizer != null) {
            try {
                virtualizer.setEnabled(false);
                virtualizer.release();
            } catch (Exception ignored) {}
            virtualizer = null;
        }
    }

    // ---------------- 预览 ----------------

    private void requestPreview(long ms) {
        if (playlist == null || index < 0 || index >= playlist.size()) return;
        if (Math.abs(ms - lastPreviewMs) < 350) return;
        lastPreviewMs = ms;
        previewTime.setText(VideoAdapter.formatDuration(ms));
        final int seq = ++previewSeq;
        final Uri uri = playlist.get(index).uri;
        long dur = player.getDuration();
        android.graphics.Bitmap hit = ThumbCache.nearest(this, uri, ms, dur);
        if (hit != null) {
            previewImage.setVisibility(View.VISIBLE);
            previewImage.setImageBitmap(hit);
            previewImage.setAlpha(1f);
            return;
        }
        previewPool.execute(() -> {
            try {
                if (previewRetriever == null || !uri.equals(previewUri)) {
                    if (previewRetriever != null) previewRetriever.release();
                    previewRetriever = new android.media.MediaMetadataRetriever();
                    previewRetriever.setDataSource(this, uri);
                    previewUri = uri;
                }
                long base = dur > 0 ? Math.min(ms, dur - 500) : ms;
                android.graphics.Bitmap bmp = previewRetriever.getFrameAtTime(base * 1000,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (bmp == null) return;
                final android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(
                        bmp, dp(232), dp(131), true);
                if (scaled != bmp) bmp.recycle();
                runOnUiThread(() -> {
                    if (seq != previewSeq || previewImage == null) return;
                    previewImage.setVisibility(View.VISIBLE);
                    previewImage.setImageBitmap(scaled);
                    previewImage.setAlpha(1f);
                });
            } catch (Throwable ignored) {
            }
        });
    }

    private void releasePreview() {
        previewSeq++;
        try {
            if (previewRetriever != null) previewRetriever.release();
        } catch (Throwable ignored) {}
        previewRetriever = null;
        previewUri = null;
    }

    // ---------------- 手势 ----------------

    private boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (btnReset != null && btnReset.getVisibility() == View.VISIBLE) {
                    int[] loc = new int[2];
                    btnReset.getLocationOnScreen(loc);
                    float rx = e.getRawX();
                    float ry = e.getRawY();
                    if (rx >= loc[0] && rx <= loc[0] + btnReset.getWidth()
                            && ry >= loc[1] && ry <= loc[1] + btnReset.getHeight()) {
                        zoom = 1f;
                        rotationDeg = 0f;
                        panX = 0f;
                        panY = 0f;
                        applyZoom();
                        updateResetBtn();
                        return true;
                    }
                }
                downX = e.getX();
                downY = e.getY();
                downTime = System.currentTimeMillis();
                scrubbing = false;
                gestureMode = 0;
                edgeGesture = downX < dp(28) || downX > v.getWidth() - dp(28);
                scrubStartMs = player == null ? 0 : Math.max(0, player.getCurrentPosition());
                gestureUsed = false;
                startBrightness = currentBrightness();
                startPlayerVolume = player == null ? 1f : player.getVolume();
                handler.removeCallbacks(boostStartTask);
                handler.postDelayed(boostStartTask, 420);
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() >= 2) {
                    pinching = true;
                    gestureUsed = true;
                    handler.removeCallbacks(boostStartTask);
                    handler.removeCallbacks(hideTask);
                    if (boosting) endBoost();
                    baseZoom = zoom;
                    baseDist = pointerDistance(e);
                    baseFocusX = (e.getX(0) + e.getX(1)) / 2f;
                    baseFocusY = (e.getY(0) + e.getY(1)) / 2f;
                    basePanX = panX;
                    basePanY = panY;
                    baseRotation = rotationDeg;
                    baseAngle = pointerAngle(e);
                }
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (pinching && e.getPointerCount() >= 2) {
                    float d = pointerDistance(e);
                    float fx = (e.getX(0) + e.getX(1)) / 2f;
                    float fy = (e.getY(0) + e.getY(1)) / 2f;
                    zoom = Math.max(0.5f, Math.min(4f, baseZoom * (d / Math.max(1f, baseDist))));
                    panX = basePanX + (fx - baseFocusX);
                    panY = basePanY + (fy - baseFocusY);
                    float ang = pointerAngle(e) - baseAngle;
                    while (ang > 180f) ang -= 360f;
                    while (ang < -180f) ang += 360f;
                    rotationDeg = baseRotation + ang;
                    applyZoom();
                    return true;
                }
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (edgeGesture) {
                    // 边缘横滑直接退出，不先唤起状态栏
                    if (Math.abs(dx) > dp(48) && Math.abs(dx) > Math.abs(dy)) {
                        finish();
                        return true;
                    }
                    return false;
                }
                if (boosting) {
                    updateBoostSpeed(boostBase + (dx / Math.max(1, v.getWidth())) * 3.0f);
                    gestureUsed = true;
                    return true;
                }
                if (Math.abs(dx) > 24 || Math.abs(dy) > 24) {
                    handler.removeCallbacks(boostStartTask);
                    if (gestureMode == 0) {
                        if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 40) gestureMode = 1;
                        else if (Math.abs(dx) > Math.abs(dy)) gestureMode = 2;
                    }
                    if (gestureMode == 1) {
                        gestureUsed = true;
                        float w = v.getWidth();
                        float factor = -dy / Math.max(1, v.getHeight());
                        if (downX < w / 4f) {
                            applyBrightness(startBrightness + factor * 0.9f);
                        } else if (downX > w * 3f / 4f) {
                            adjustPlayerVolume(factor);
                        }
                        // 中间一半区域：上下滑不生效
                        return true;
                    }
                    if (gestureMode == 2) {
                        if (!scrubbing) {
                            scrubbing = true;
                            gestureUsed = true;
                            lastPreviewMs = -1;
                            previewPanel.setVisibility(View.VISIBLE);
                            setOverlayVisible(true);
                        }
                        long dur = player == null ? 0 : player.getDuration();
                        if (dur > 0) {
                            scrubTargetMs = Math.max(0, Math.min(dur,
                                    scrubStartMs + (long) (dx / Math.max(1, v.getWidth()) * dur * 0.6)));
                            timeNow.setText(VideoAdapter.formatDuration(scrubTargetMs));
                            seek.setProgress((int) (scrubTargetMs * 1000 / Math.max(1, dur)));
                            requestPreview(scrubTargetMs);
                        }
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                pinching = false;
                gestureMode = 0;
                if (scrubbing) {
                    scrubbing = false;
                    if (player != null && scrubTargetMs > 0) player.seekTo(scrubTargetMs);
                    previewPanel.setVisibility(View.GONE);
                    scheduleHide();
                    return true;
                }
                handler.removeCallbacks(boostStartTask);
                if (boosting) {
                    endBoost();
                    return true;
                }
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                long dt = System.currentTimeMillis() - downTime;
                if (Math.abs(dx) < 24 && Math.abs(dy) < 24 && dt < 300) {
                    long now = System.currentTimeMillis();
                    if (now - lastTap < 350) {
                        togglePlay();
                        lastTap = 0;
                    } else {
                        lastTap = now;
                        setOverlayVisible(overlay.getVisibility() != View.VISIBLE);
                    }
                    return true;
                }
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerCount() - 1 < 2) {
                    pinching = false;
                    if (zoom >= 0.98f && zoom <= 1.02f) {
                        zoom = 1f;
                    }
                    applyZoom();
                    showChip("缩放 " + Math.round(zoom * 100) + "%", 1000);
                    if (overlay.getVisibility() == View.VISIBLE) scheduleHide();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                scrubbing = false;
                gestureMode = 0;
                if (previewPanel != null) previewPanel.setVisibility(View.GONE);
                handler.removeCallbacks(boostStartTask);
                endBoost();
                return true;

            default:
                return true;
        }
    }

    private static float pointerDistance(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float pointerAngle(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0f;
        return (float) Math.toDegrees(Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0)));
    }

    private float currentBrightness() {
        float b = getWindow().getAttributes().screenBrightness;
        if (b > 0) return b;
        try {
            int v = android.provider.Settings.System.getInt(getContentResolver(),
                    android.provider.Settings.System.SCREEN_BRIGHTNESS);
            return Math.max(0.05f, v / 255f);
        } catch (Exception e) {
            return 0.5f;
        }
    }

    private void applyBrightness(float value) {
        float b = Math.max(0.05f, Math.min(1f, value));
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b;
        getWindow().setAttributes(lp);
        showChip("亮度 " + Math.round(b * 100) + "%", 700);
    }

    private void adjustPlayerVolume(float factor) {
        if (player == null) return;
        float v = Math.max(0f, Math.min(1f, startPlayerVolume + factor * 1.2f));
        player.setVolume(v);
        showChip("音量 " + Math.round(v * 100) + "%", 700);
    }

    private void startBoost() {
        if (player == null || boosting) return;
        boosting = true;
        float cur = player.getPlaybackParameters().speed;
        boostBase = cur > 0 ? cur : 1f;
        setOverlayVisible(false);
        showChipPersistent(formatSpeed(boostBase));
    }

    private void updateBoostSpeed(float speed) {
        if (player == null) return;
        float v = Math.max(0.5f, Math.min(4.0f, Math.round(speed * 10f) / 10f));
        player.setPlaybackSpeed(v);
        showChipPersistent(formatSpeed(v));
    }

    private void endBoost() {
        if (!boosting) return;
        boosting = false;
        hideChip();
        // 松手保留当前速度；退出播放页后由新实例恢复 1.0X
    }

    private void showChipPersistent(String text) {
        if (brightnessHint == null) return;
        brightnessHint.setText(text);
        brightnessHint.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideChipTask);
    }

    private void hideChip() {
        if (brightnessHint != null) brightnessHint.setVisibility(View.GONE);
        handler.removeCallbacks(hideChipTask);
    }

    private void showChip(String text, long ms) {
        if (brightnessHint == null) return;
        brightnessHint.setText(text);
        brightnessHint.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideChipTask);
        handler.postDelayed(hideChipTask, ms);
    }

    // ---------------- 按键 ----------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (menuOpen) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) { moveMenuCursor(-1); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { moveMenuCursor(1); return true; }
            if (keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                activateMenuCursor();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK) {
                dismissMenu(false);
                return true;
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: seekBy(-10000); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: seekBy(10000); return true;
            case KeyEvent.KEYCODE_DPAD_UP: switchBy(-1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: switchBy(1); return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                pickFilter((filterIndex + 1) % FILTER_NAMES.length);
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                showFilterMenu();
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlay();
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                finish();
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

    @Override
    public void onBackPressed() {
        if (menuOpen) {
            if (menuBack != null) menuBack.run();
            else dismissMenu(true);
            return;
        }
        showMenu();
    }

    // ---------------- 生命周期 ----------------

    private void savePosition(boolean ended) {
        if (playlist == null || playlist.isEmpty()) return;
        VideoItem item = playlist.get(index);
        long dur = player == null ? 0 : player.getDuration();
        long pos = player == null ? 0 : player.getCurrentPosition();
        if (ended) pos = dur;
        MediaLibrary.savePosition(this, item.uri, pos, dur);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (playlist != null && PlaylistHolder.index() != index) play(PlaylistHolder.index());
        handler.post(progressTask);
        if (player != null) player.play();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(progressTask);
        handler.removeCallbacks(hideTask);
        handler.removeCallbacks(boostStartTask);
        endBoost();
        if (player != null) {
            if (settings != null && settings.resume) savePosition(false);
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        releasePreview();
        handler.removeCallbacksAndMessages(null);
        analysisPool.shutdownNow();
        releaseAudioEffects();
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
