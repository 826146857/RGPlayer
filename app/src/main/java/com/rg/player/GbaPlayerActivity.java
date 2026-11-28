package com.rg.player;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;

/** 用内置 mGBA libretro 核心播放 .gba 电影卡 ROM。 */
public class GbaPlayerActivity extends AppCompatActivity {

    private static final String[] MASK_NAMES = {
            "军绿", "橙色", "淡粉", "淡黑", "灰色", "白色", "粉色", "紫色", "红色",
            "绿色", "蓝色", "青色", "黄色", "黑色", "EVA紫绿", "塞尔达绿金",
            "湖人紫金", "高达红金"
    };

    private static final int B = 1 << 0;
    private static final int SELECT = 1 << 2;
    private static final int START = 1 << 3;
    private static final int UP = 1 << 4;
    private static final int DOWN = 1 << 5;
    private static final int LEFT = 1 << 6;
    private static final int RIGHT = 1 << 7;
    private static final int A = 1 << 8;
    private static final int L = 1 << 10;
    private static final int BTN_R = 1 << 11;

    private GbaCore core;
    private GbaView view;
    private ImageView maskView;
    private android.graphics.Bitmap maskBitmap;
    private int maskIndex;
    private java.util.ArrayList<String> romPaths;
    private int romIndex;
    private android.view.View maskMenuLayer;
    private View hint;
    private TextView hintTitle, hintKeys;
    private String currentRomName = "";
    private static final String CONTROLS = "A 暂停 L/R 快进快退 START 菜单 X 切换遮罩 Y 自选遮罩 L2/R2 切影片 BACK 退出";
    private AudioTrack audioTrack;

    private Thread renderThread;
    private Thread audioThread;
    private volatile boolean running;
    private int[] pixels;
    private short[] audioBuf;
    private short[] audioOut;
    private double audioStep = 1.0;
    private int buttons;

    private String savePath;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideHint = () -> hint.setVisibility(View.GONE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat ic = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        ic.hide(WindowInsetsCompat.Type.systemBars());
        ic.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_gba);

        view = findViewById(R.id.gba_view);
        maskView = findViewById(R.id.mask_view);
        hint = findViewById(R.id.gba_hint);
        hintTitle = findViewById(R.id.hint_title);
        hintKeys = findViewById(R.id.hint_keys);
        maskIndex = getSharedPreferences("rgsettings", MODE_PRIVATE).getInt("gba_mask", 0);
        applyMask();

        romPaths = getIntent() == null ? null
                : getIntent().getStringArrayListExtra("rom_paths");
        romIndex = getIntent() == null ? 0 : getIntent().getIntExtra("rom_index", 0);
        if (romPaths != null && !romPaths.isEmpty()) {
            romIndex = Math.max(0, Math.min(romPaths.size() - 1, romIndex));
        }
        String romPath = romPaths != null && !romPaths.isEmpty()
                ? romPaths.get(romIndex) : getIntent().getStringExtra("rom_path");
        String romName = getIntent().getStringExtra("rom_name");
        if (romPath == null || !new File(romPath).exists()) {
            Toast.makeText(this, "ROM 不存在", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (romName == null) romName = new File(romPath).getName();

        startRom(romPath, romName);

        hint.setOnClickListener(v -> toggleHint());
        view.setOnClickListener(v -> toggleHint());

        // 线程在 onResume 启动，避免 onCreate/onResume 各启一份
    }

    private void startRom(String romPath, String romName) {
        File base = new File(getFilesDir(), "gba");
        File saves = new File(base, "saves");
        File system = new File(base, "system");
        saves.mkdirs();
        system.mkdirs();
        savePath = new File(saves, romName.replaceAll("[^0-9A-Za-z._-]", "_") + ".sav").getAbsolutePath();

        String corePath = new File(getApplicationInfo().nativeLibraryDir, "libmgba_core.so").getAbsolutePath();
        core = GbaCore.create(corePath, romPath, system.getAbsolutePath(), saves.getAbsolutePath());
        if (core == null) {
            Toast.makeText(this, "GBA 核心加载失败", Toast.LENGTH_LONG).show();
            return;
        }
        core.loadSram(savePath);

        int coreRate = Math.round(core.sampleRate());
        if (coreRate <= 0) coreRate = 32768;
        final int rate = 48000;
        audioStep = coreRate / (double) rate;
        int minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, rate / 2 * 4);
        audioTrack = new AudioTrack(android.media.AudioManager.STREAM_MUSIC, rate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                bufSize, AudioTrack.MODE_STREAM);
        audioBuf = new short[2048];
        audioOut = new short[2048];
        currentRomName = romName;
        hintTitle.setText(romName);
        hintKeys.setText(CONTROLS);
        hint.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideHint);
        handler.postDelayed(hideHint, 3000);
    }

    private void startThreads() {
        if (running || core == null || audioTrack == null) return;
        audioTrack.play();
        running = true;
        renderThread = new Thread(this::renderLoop, "gba-render");
        renderThread.start();
        audioThread = new Thread(this::audioLoop, "gba-audio");
        audioThread.start();
    }

    private void stopThreads() {
        running = false;
        Thread r = renderThread, a = audioThread;
        if (r != null) {
            try {
                r.join(1500);
            } catch (InterruptedException ignored) {
            }
        }
        if (a != null) {
            try {
                a.join(1500);
            } catch (InterruptedException ignored) {
            }
        }
        boolean stopped = (r == null || !r.isAlive()) && (a == null || !a.isAlive());
        renderThread = null;
        audioThread = null;
        // 只有确认写线程已退出才 flush，避免 releaseBuffer 越界崩溃
        if (stopped && audioTrack != null) {
            try {
                audioTrack.pause();
                audioTrack.flush();
            } catch (Throwable ignored) {
            }
        }
    }

    private void renderLoop() {
        final long frameNs = 1_000_000_000L / 60;
        long next = System.nanoTime();
        while (running) {
            core.run();
            int w = core.width();
            int h = core.height();
            if (w > 0 && h > 0) {
                if (pixels == null || pixels.length < w * h) pixels = new int[w * h];
                int n = core.video(pixels);
                if (n > 0) {
                    final int fw = w, fh = h;
                    runOnUiThread(() -> view.setFrame(fw, fh, pixels));
                }
            }

            next += frameNs;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                } catch (InterruptedException ignored) {
                }
            } else {
                next = System.nanoTime();
            }
        }
    }

    private void audioLoop() {
        double pos = 0;
        while (running) {
            int n = core.audio(audioBuf);
            int frames = n / 2;
            if (frames < 2) {
                try {
                    Thread.sleep(4);
                } catch (InterruptedException ignored) {
                }
                continue;
            }
            int out = 0;
            while (pos < frames - 1 && out + 1 < audioOut.length) {
                int i = (int) pos;
                float f = (float) (pos - i);
                short l0 = audioBuf[i * 2], l1 = audioBuf[(i + 1) * 2];
                short r0 = audioBuf[i * 2 + 1], r1 = audioBuf[(i + 1) * 2 + 1];
                audioOut[out++] = (short) (l0 + (l1 - l0) * f);
                audioOut[out++] = (short) (r0 + (r1 - r0) * f);
                pos += audioStep;
            }
            pos -= (frames - 1);
            if (pos < 0) pos = 0;
            if (out > 0 && running) {
                AudioTrack track = audioTrack;
                if (track != null) {
                    try {
                        track.write(audioOut, 0, out);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private final android.content.BroadcastReceiver f10 = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, android.content.Intent i) {
            if (GbaNav.blocked()) return;
            GbaNav.lock(700);
            finish();
        }
    };

    private void toggleHint() {
        handler.removeCallbacks(hideHint);
        if (hint.getVisibility() == View.VISIBLE) {
            hint.setVisibility(View.GONE);
        } else {
            hintTitle.setText(currentRomName);
            hintKeys.setText(CONTROLS);
            hint.setVisibility(View.VISIBLE);
            handler.postDelayed(hideHint, 3000);
        }
    }

    private void applyMask() {
        if (maskView == null) return;
        if (maskBitmap != null) {
            maskBitmap.recycle();
            maskBitmap = null;
        }
        if (maskIndex <= 0 || maskIndex > MASK_NAMES.length) {
            maskView.setImageDrawable(null);
            maskView.setVisibility(View.GONE);
            if (view != null) view.setContentInset(0, 0, 0, 0);
            return;
        }
        try {
            String name = String.format(java.util.Locale.US, "masks/mask_%02d.png", maskIndex);
            try (java.io.InputStream in = getAssets().open(name)) {
                maskBitmap = android.graphics.BitmapFactory.decodeStream(in);
            }
            if (maskBitmap != null) {
                maskView.setImageBitmap(maskBitmap);
                maskView.setVisibility(View.VISIBLE);
                if (view != null) view.setContentInset(0, 0, 0, 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private void showMaskMenu() {
        if (maskMenuLayer != null) {
            dismissMaskMenu();
            return;
        }
        android.widget.FrameLayout layer = new android.widget.FrameLayout(this);
        layer.setBackgroundColor(0x88000000);
        layer.setClickable(true);
        layer.setOnClickListener(v -> dismissMaskMenu());

        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_menu);
        card.setPadding(dp(6), dp(8), dp(6), dp(8));

        android.widget.TextView head = new android.widget.TextView(this);
        head.setText("遮罩");
        head.setTextColor(0xFF35C8FF);
        head.setTextSize(12);
        head.setPadding(dp(12), dp(4), dp(12), dp(6));
        card.addView(head);

        android.widget.ScrollView sc = new android.widget.ScrollView(
                new android.view.ContextThemeWrapper(this, R.style.RGScroll));
        android.widget.LinearLayout items = new android.widget.LinearLayout(this);
        items.setOrientation(android.widget.LinearLayout.VERTICAL);
        sc.addView(items);

        String[] labels = new String[MASK_NAMES.length + 1];
        labels[0] = "关闭遮罩";
        for (int i = 0; i < MASK_NAMES.length; i++) labels[i + 1] = MASK_NAMES[i];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            android.widget.TextView row = new android.widget.TextView(this);
            row.setText((i == maskIndex ? "✓ " : "") + labels[i]);
            row.setTextSize(15);
            row.setTextColor(0xFFE6E9EF);
            row.setPadding(dp(10), dp(10), dp(16), dp(10));
            row.setOnClickListener(v -> {
                maskIndex = idx;
                getSharedPreferences("rgsettings", MODE_PRIVATE).edit()
                        .putInt("gba_mask", maskIndex).apply();
                applyMask();
                dismissMaskMenu();
                hintKeys.setText(maskIndex == 0 ? "遮罩：关闭" : "遮罩：" + MASK_NAMES[maskIndex - 1]);
                hint.setVisibility(View.VISIBLE);
                handler.removeCallbacks(hideHint);
                handler.postDelayed(hideHint, 1500);
            });
            items.addView(row);
        }

        card.addView(sc, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                dp(158), (int) (getResources().getDisplayMetrics().heightPixels * 0.8f));
        lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        lp.setMargins(dp(8), dp(8), dp(12), dp(12));
        layer.addView(card, lp);
        ((android.view.ViewGroup) findViewById(android.R.id.content)).addView(layer,
                new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        maskMenuLayer = layer;
    }

    private void dismissMaskMenu() {
        if (maskMenuLayer == null) return;
        android.view.ViewGroup p = (android.view.ViewGroup) maskMenuLayer.getParent();
        if (p != null) p.removeView(maskMenuLayer);
        maskMenuLayer = null;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void switchVideo(int delta) {
        if (romPaths == null || romPaths.size() < 2) return;
        int n = Math.max(0, Math.min(romPaths.size() - 1, romIndex + delta));
        if (n == romIndex) return;
        stopThreads();
        if (core != null) {
            core.saveSram(savePath);
            core.destroy();
            core = null;
        }
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Throwable ignored) {
            }
            audioTrack = null;
        }
        romIndex = n;
        String path = romPaths.get(n);
        startRom(path, new File(path).getName());
        startThreads();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            if (isTaskRoot()) {
                startActivity(new android.content.Intent(this, MainActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
            }
            finish();
            return true;
        }
        if (event.getScanCode() == 68 || keyCode == KeyEvent.KEYCODE_F10) {
            if (GbaNav.blocked()) return true;
            GbaNav.lock(700);
            finish();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_X) {
            maskIndex = (maskIndex + 1) % (MASK_NAMES.length + 1);
            getSharedPreferences("rgsettings", MODE_PRIVATE).edit()
                    .putInt("gba_mask", maskIndex).apply();
            applyMask();
            hintKeys.setText(maskIndex == 0 ? "遮罩：关闭" : "遮罩：" + MASK_NAMES[maskIndex - 1]);
            hint.setVisibility(View.VISIBLE);
            handler.removeCallbacks(hideHint);
            handler.postDelayed(hideHint, 1500);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
            showMaskMenu();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
            switchVideo(-1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            switchVideo(1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && maskMenuLayer != null) {
            dismissMaskMenu();
            return true;
        }
        int bit = mapKey(keyCode);
        if (bit == 0) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                if (isTaskRoot()) {
                    startActivity(new android.content.Intent(this, MainActivity.class)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
                }
                finish();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
        buttons |= bit;
        if (core != null) core.buttons(buttons);
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int bit = mapKey(keyCode);
        if (bit != 0) {
            buttons &= ~bit;
            if (core != null) core.buttons(buttons);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private static int mapKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_SPACE:
                return A;
            case KeyEvent.KEYCODE_BUTTON_B:
                return B;
            case KeyEvent.KEYCODE_BUTTON_L1:
                return L;
            case KeyEvent.KEYCODE_BUTTON_R1:
                return BTN_R;
            case KeyEvent.KEYCODE_BUTTON_START:
                return START;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return SELECT;
            case KeyEvent.KEYCODE_DPAD_UP:
                return UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return RIGHT;
            default:
                return 0;
        }
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(f10);
        } catch (Exception ignored) {
        }
        super.onPause();
        stopThreads();
        if (core != null) core.saveSram(savePath);
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
        if (audioTrack != null && core != null) startThreads();
    }

    @Override
    protected void onDestroy() {
        running = false;
        if (renderThread != null) {
            try {
                renderThread.join(800);
            } catch (InterruptedException ignored) {
            }
        }
        if (audioThread != null) {
            try {
                audioThread.join(800);
            } catch (InterruptedException ignored) {
            }
        }
        if (core != null) {
            core.saveSram(savePath);
            core.destroy();
            core = null;
        }
        if (audioTrack != null) {
            try {
                audioTrack.stop();
            } catch (Exception ignored) {
            }
            audioTrack.release();
            audioTrack = null;
        }
        super.onDestroy();
    }
}
