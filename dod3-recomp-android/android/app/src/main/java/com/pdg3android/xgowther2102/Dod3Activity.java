package com.pdg3android.xgowther2102;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLControllerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Dod3Activity extends SDLActivity {

    public static final int PERF_STYLE_FPS_ONLY = 0;
    public static final int PERF_STYLE_BAR      = 1;
    public static final int PERF_STYLE_DETAILED = 2;

    public static final int PERF_POS_LEFT   = 0;
    public static final int PERF_POS_CENTER = 1;
    public static final int PERF_POS_RIGHT  = 2;

    private VirtualPadView mPadView;
    private TextView mFpsView;
    private FrameLayout mExitOverlay;
    private Object mBackCallback;
    private long mLastBackToggleMs = 0L;
    private boolean mExitingToLauncher = false;
    private Handler mHudHandler;
    private ExecutorService mPerfExec;
    private volatile boolean mPerfBusy;
    private boolean mResumed;
    private boolean mHudEnabled;
    private int mPerfStyle = PERF_STYLE_BAR;
    private int mPerfPos = PERF_POS_LEFT;
    private int mPerfSize = 1;
    private int mPerfBgOpacity = 65;
    private boolean mShowFrametime = true;
    private boolean mShowCpu = true;
    private boolean mShowGpu = true;
    private boolean mShowRam = true;
    private boolean mShowTemp = true;
    private long mPrevProcTicks = -1L;
    private long mPrevSampleNs = -1L;
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRehideRunnable = () -> {
        hideSystemUI();
        bringOverlaysToFront();
    };

    private static native long nativeGetPerfStats();

    private static final class PerfSample {
        int cpuMaxMhz = -1;
        int cpuLoadPct = -1;
        int gpuMhz = -1;
        int gpuLoadPct = -1;
        int appRamMb = -1;
        long sysUsedMb = -1L;
        long sysTotalMb = -1L;
        float tempC = -1f;
    }

    private final Runnable mHudTick = new Runnable() {
        @Override
        public void run() {
            if (!mResumed || !mHudEnabled || mFpsView == null || isFinishing() || isDestroyed()) return;
            updatePerfHud();
            if (mHudHandler != null) mHudHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "dod3" };
    }

    @Override
    protected String getMainSharedObject() {
        return "libdod3.so";
    }

    @Override
    protected String getMainFunction() {
        return "SDL_main";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashReporter.install(this);
        CrashReporter.markGameSessionStart(this);
        SDLActivity.mFullscreenModeActive = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams wlp = getWindow().getAttributes();
            wlp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(wlp);
        }
        installInsetsRehideListener();
        hideSystemUI();

        mPadView = new VirtualPadView(this);
        applyPadPrefsFromIntent(getIntent());
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT);
        mLayout.addView(mPadView, lp);

        reloadPerfPrefs();
        if (mHudEnabled) {
            ensurePerfHudCreated();
            applyPerfHudStyle();
        }
        if (Build.VERSION.SDK_INT >= 33) {
            android.window.OnBackInvokedCallback cb = this::toggleExitOverlay;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            mBackCallback = cb;
        }
        bringOverlaysToFront();
    }

    private void applyPadPrefsFromIntent(Intent src) {
        if (mPadView == null) return;
        SharedPreferences prefs = getSharedPreferences("dod3_android", MODE_PRIVATE);
        String tpl = (src != null && src.hasExtra("pad_template"))
                ? src.getStringExtra("pad_template")
                : prefs.getString("pad_template", "auto");
        if (tpl == null) tpl = "auto";
        if ("auto".equals(tpl)) {
            IniStore ini = new IniStore(new File("/sdcard/Drakengard3/dod3.ini"));
            String prompts = ini.get("DOD3_PROMPTS", "").trim().toLowerCase();
            if (prompts.equals("ps3") || prompts.equals("ps4") || prompts.equals("ps5")
                    || prompts.equals("xbox") || prompts.equals("switch")) {
                tpl = prompts;
            } else {
                tpl = detectConnectedPadStyle();
            }
        }
        boolean padVis = (src != null && src.hasExtra("pad_visible"))
                ? src.getBooleanExtra("pad_visible", true)
                : prefs.getBoolean("pad_visible", true);
        int padOp = (src != null && src.hasExtra("pad_opacity"))
                ? src.getIntExtra("pad_opacity", 75)
                : prefs.getInt("pad_opacity", 75);
        mPadView.setPadTemplate(tpl);
        mPadView.setPadConfig(padVis, padOp / 100f);
    }

    private void reloadPerfPrefs() {
        Intent in = getIntent();
        SharedPreferences prefs = getSharedPreferences("dod3_android", MODE_PRIVATE);
        mHudEnabled = (in != null && in.hasExtra("fps_hud"))
                ? in.getBooleanExtra("fps_hud", true) : prefs.getBoolean("fps_hud", true);
        mPerfStyle = clampInt((in != null && in.hasExtra("perf_style"))
                ? in.getIntExtra("perf_style", PERF_STYLE_BAR) : prefs.getInt("perf_style", PERF_STYLE_BAR), 0, 2);
        mPerfPos = clampInt((in != null && in.hasExtra("perf_pos"))
                ? in.getIntExtra("perf_pos", PERF_POS_LEFT) : prefs.getInt("perf_pos", PERF_POS_LEFT), 0, 2);
        mPerfSize = clampInt((in != null && in.hasExtra("perf_size"))
                ? in.getIntExtra("perf_size", 1) : prefs.getInt("perf_size", 1), 0, 2);
        mPerfBgOpacity = clampInt((in != null && in.hasExtra("perf_bg_opacity"))
                ? in.getIntExtra("perf_bg_opacity", 65) : prefs.getInt("perf_bg_opacity", 65), 0, 100);
        mShowFrametime = (in != null && in.hasExtra("perf_show_frametime"))
                ? in.getBooleanExtra("perf_show_frametime", true) : prefs.getBoolean("perf_show_frametime", true);
        mShowCpu = (in != null && in.hasExtra("perf_show_cpu"))
                ? in.getBooleanExtra("perf_show_cpu", true) : prefs.getBoolean("perf_show_cpu", true);
        mShowGpu = (in != null && in.hasExtra("perf_show_gpu"))
                ? in.getBooleanExtra("perf_show_gpu", true) : prefs.getBoolean("perf_show_gpu", true);
        mShowRam = (in != null && in.hasExtra("perf_show_ram"))
                ? in.getBooleanExtra("perf_show_ram", true) : prefs.getBoolean("perf_show_ram", true);
        mShowTemp = (in != null && in.hasExtra("perf_show_temp"))
                ? in.getBooleanExtra("perf_show_temp", true) : prefs.getBoolean("perf_show_temp", true);
    }

    private void ensurePerfHudCreated() {
        if (mFpsView == null) {
            float d = getResources().getDisplayMetrics().density;
            mFpsView = new TextView(this);
            mFpsView.setText("-- FPS");
            mFpsView.setTextColor(0xFF5CE68C);
            mFpsView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            mFpsView.setSingleLine(true);
            mFpsView.setClickable(false);
            mFpsView.setFocusable(false);
            mFpsView.setPadding((int)(8 * d), (int)(3 * d), (int)(8 * d), (int)(3 * d));
            mLayout.addView(mFpsView);
        }
        if (mHudHandler == null) {
            mHudHandler = new Handler(Looper.getMainLooper());
        }
        if (mPerfExec == null || mPerfExec.isShutdown()) {
            mPerfExec = Executors.newSingleThreadExecutor();
        }
    }

    private void applyPerfHudStyle() {
        if (mFpsView == null) return;
        float d = getResources().getDisplayMetrics().density;
        float spSize = mPerfSize == 0 ? 8.5f : (mPerfSize == 2 ? 11.5f : 9.8f);
        mFpsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, spSize);

        int bgAlpha = Math.round(255f * clampInt(mPerfBgOpacity, 0, 100) / 100f);
        int strokeAlpha = Math.round(bgAlpha * 0.45f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(bgAlpha, 12, 12, 18));
        bg.setCornerRadius(8 * d);
        bg.setStroke((int)Math.max(1, d), Color.argb(strokeAlpha, 255, 255, 255));
        mFpsView.setBackground(bg);
        mFpsView.setShadowLayer(bgAlpha < 60 ? 3f : 0f, 0f, 1f, 0xCC000000);

        RelativeLayout.LayoutParams flp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT);
        flp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        flp.topMargin = (int)(6 * d);
        if (mPerfPos == PERF_POS_CENTER) {
            flp.addRule(RelativeLayout.CENTER_HORIZONTAL);
        } else if (mPerfPos == PERF_POS_RIGHT) {
            flp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
            flp.rightMargin = (int)(12 * d);
        } else {
            flp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
            flp.leftMargin = (int)(12 * d);
        }
        mFpsView.setLayoutParams(flp);
        mFpsView.setVisibility(mHudEnabled ? View.VISIBLE : View.GONE);
    }

    private void updatePerfHud() {
        if (mPerfBusy || mPerfExec == null || mPerfExec.isShutdown()) return;
        final int style = mPerfStyle;
        mPerfBusy = true;
        try {
            mPerfExec.execute(() -> {
                long packed = 0L;
                try {
                    packed = nativeGetPerfStats();
                } catch (UnsatisfiedLinkError ignored) {}
                final PerfSample perf = (style != PERF_STYLE_FPS_ONLY) ? sampleHardwarePerf() : null;
                final long finalPacked = packed;
                mUiHandler.post(() -> {
                    mPerfBusy = false;
                    renderPerfHud(finalPacked, perf);
                });
            });
        } catch (Throwable t) {
            mPerfBusy = false;
        }
    }

    private void renderPerfHud(long packed, PerfSample perf) {
        if (!mResumed || !mHudEnabled || mFpsView == null || isFinishing() || isDestroyed()) return;
        long fpsX10 = (packed >>> 32) & 0xFFFFFFFFL;
        long msX10  = packed & 0xFFFFFFFFL;
        double fps = fpsX10 / 10.0;
        double ms  = msX10 / 10.0;

        SpannableStringBuilder bar = new SpannableStringBuilder();
        if (fpsX10 > 0) {
            int fpsColor = fps >= 45.0 ? 0xFF5CE68C : (fps >= 25.0 ? 0xFFFFC857 : 0xFFFF6B6B);
            appendColored(bar, String.format(Locale.US, "%.1f FPS", fps), fpsColor);
        } else {
            appendColored(bar, "-- FPS", 0xFF5CE68C);
        }

        if (mPerfStyle != PERF_STYLE_FPS_ONLY) {
            if ((mShowFrametime || mPerfStyle == PERF_STYLE_DETAILED) && msX10 > 0) {
                appendColored(bar, String.format(Locale.US, " · %.1fms", ms), 0xFFBFD4F2);
            }
            if (perf != null) {
                if (mShowCpu && (perf.cpuMaxMhz > 0 || perf.cpuLoadPct >= 0)) {
                    appendColored(bar, " · CPU ", 0xFF8FB4FF);
                    StringBuilder cpuVal = new StringBuilder();
                    if (perf.cpuMaxMhz >= 1000) {
                        cpuVal.append(String.format(Locale.US, "%.2fG", perf.cpuMaxMhz / 1000.0));
                    } else if (perf.cpuMaxMhz > 0) {
                        cpuVal.append(perf.cpuMaxMhz).append("M");
                    }
                    if (perf.cpuLoadPct >= 0) {
                        if (cpuVal.length() > 0) cpuVal.append(' ');
                        cpuVal.append(perf.cpuLoadPct).append('%');
                    }
                    appendColored(bar, cpuVal.toString(), 0xFFE6EDF7);
                }
                if (mShowGpu) {
                    if (perf.gpuMhz > 0 || perf.gpuLoadPct >= 0) {
                        appendColored(bar, " · GPU ", 0xFFC59BFF);
                        StringBuilder gpuVal = new StringBuilder();
                        if (perf.gpuMhz > 0) {
                            gpuVal.append(perf.gpuMhz).append("M");
                        }
                        if (perf.gpuLoadPct >= 0) {
                            if (gpuVal.length() > 0) gpuVal.append(' ');
                            gpuVal.append(perf.gpuLoadPct).append('%');
                        }
                        appendColored(bar, gpuVal.toString(), 0xFFE6EDF7);
                    } else if (mPerfStyle == PERF_STYLE_BAR) {
                        appendColored(bar, " · GPU ", 0xFFC59BFF);
                        appendColored(bar, "-", 0xFFE6EDF7);
                    }
                }
                if (mShowRam && (perf.appRamMb > 0 || perf.sysTotalMb > 0)) {
                    appendColored(bar, " · RAM ", 0xFF7CE8D8);
                    String ramStr;
                    if (mPerfStyle == PERF_STYLE_DETAILED && perf.appRamMb > 0 && perf.sysTotalMb > 0) {
                        ramStr = perf.appRamMb + "M ("
                                + String.format(Locale.US, "%.1f/%.1fG",
                                        perf.sysUsedMb / 1024.0, perf.sysTotalMb / 1024.0)
                                + ")";
                    } else if (perf.appRamMb > 0 && perf.sysTotalMb > 0) {
                        ramStr = perf.appRamMb + "M/"
                                + String.format(Locale.US, "%.1fG", perf.sysTotalMb / 1024.0);
                    } else if (perf.appRamMb > 0) {
                        ramStr = perf.appRamMb + "M";
                    } else {
                        ramStr = String.format(Locale.US, "%.1f/%.1fG",
                                perf.sysUsedMb / 1024.0, perf.sysTotalMb / 1024.0);
                    }
                    appendColored(bar, ramStr, 0xFFE6EDF7);
                }
                if ((mShowTemp || mPerfStyle == PERF_STYLE_DETAILED) && perf.tempC > 0f) {
                    int tCol = perf.tempC >= 43f ? 0xFFFF6B6B : (perf.tempC >= 38f ? 0xFFFFC857 : 0xFF9BE7FF);
                    appendColored(bar, String.format(Locale.US, " · %.0f°C", perf.tempC), tCol);
                }
            }
        }
        mFpsView.setText(bar);
        mFpsView.setVisibility(View.VISIBLE);
    }

    private static void appendColored(SpannableStringBuilder sb, String text, int color) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private PerfSample sampleHardwarePerf() {
        PerfSample p = new PerfSample();
        int peakKhz = -1;
        for (int i = 0; i < 16; i++) {
            long khz = readSysLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            if (khz > peakKhz) peakKhz = (int) khz;
        }
        if (peakKhz > 0) p.cpuMaxMhz = peakKhz / 1000;

        long nowNs = System.nanoTime();
        long procTicks = readSelfCpuTicks();
        if (procTicks >= 0 && mPrevProcTicks >= 0 && mPrevSampleNs > 0) {
            double dtSec = (nowNs - mPrevSampleNs) / 1_000_000_000.0;
            if (dtSec > 0.05) {
                int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
                double pct = ((procTicks - mPrevProcTicks) / 100.0) / (dtSec * cores) * 100.0;
                p.cpuLoadPct = Math.max(0, Math.min(100, (int) Math.round(pct)));
            }
        }
        if (procTicks >= 0) {
            mPrevProcTicks = procTicks;
            mPrevSampleNs = nowNs;
        }

        long gpuClkMhz = readSysLong("/sys/class/kgsl/kgsl-3d0/clock_mhz");
        if (gpuClkMhz > 0) {
            p.gpuMhz = (int) gpuClkMhz;
        } else {
            long hz = readSysLong("/sys/class/kgsl/kgsl-3d0/gpuclk");
            if (hz <= 0) hz = readSysLong("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq");
            if (hz <= 0) hz = readSysLong("/sys/kernel/gpu/gpu_clock");
            if (hz <= 0) hz = readSysLong("/sys/class/misc/mali0/device/devfreq/devfreq0/cur_freq");
            if (hz > 1_000_000L) p.gpuMhz = (int) (hz / 1_000_000L);
            else if (hz > 10_000L) p.gpuMhz = (int) (hz / 1_000L);
            else if (hz > 0L) p.gpuMhz = (int) hz;
        }
        p.gpuLoadPct = readGpuBusyPct();

        long rssPages = readSelfRssPages();
        if (rssPages > 0) p.appRamMb = (int) ((rssPages * 4096L) / (1024L * 1024L));
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            p.sysTotalMb = mi.totalMem / (1024L * 1024L);
            p.sysUsedMb = Math.max(0L, (mi.totalMem - mi.availMem) / (1024L * 1024L));
        }
        Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int tTenths = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            if (tTenths > 0) p.tempC = tTenths / 10f;
        }
        return p;
    }

    private static int readGpuBusyPct() {
        int pct = readFirstInt("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage");
        if (pct >= 0) return clampInt(pct, 0, 100);
        try (BufferedReader br = new BufferedReader(new FileReader("/sys/class/kgsl/kgsl-3d0/gpubusy"))) {
            String line = br.readLine();
            if (line != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    long busy = Long.parseLong(parts[0]);
                    long total = Long.parseLong(parts[1]);
                    if (total > 0) return clampInt((int) Math.round((busy * 100.0) / total), 0, 100);
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static long readSysLong(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            if (line == null) return -1L;
            return Long.parseLong(line.trim());
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static int readFirstInt(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            if (line == null) return -1;
            String cleaned = line.replace("%", "").trim();
            return Integer.parseInt(cleaned);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static long readSelfCpuTicks() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/stat"))) {
            String line = br.readLine();
            if (line == null) return -1L;
            int rp = line.lastIndexOf(')');
            if (rp < 0 || rp + 2 >= line.length()) return -1L;
            String[] parts = line.substring(rp + 2).trim().split("\\s+");
            if (parts.length <= 12) return -1L;
            return Long.parseLong(parts[11]) + Long.parseLong(parts[12]);
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static long readSelfRssPages() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/statm"))) {
            String line = br.readLine();
            if (line == null) return -1L;
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) return -1L;
            return Long.parseLong(parts[1]);
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyPadPrefsFromIntent(intent);
        reloadPerfPrefs();
        if (mHudEnabled) {
            ensurePerfHudCreated();
            applyPerfHudStyle();
        } else if (mFpsView != null) {
            mFpsView.setVisibility(View.GONE);
        }
        bringOverlaysToFront();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        mPerfBusy = false;
        reloadPerfPrefs();
        if (mHudEnabled) {
            ensurePerfHudCreated();
            applyPerfHudStyle();
        } else if (mFpsView != null) {
            mFpsView.setVisibility(View.GONE);
        }
        bringOverlaysToFront();
        if (mPadView != null) mPadView.resetTouches();
        hideSystemUI();
        mUiHandler.removeCallbacks(mRehideRunnable);
        mUiHandler.postDelayed(mRehideRunnable, 250);
        if (mHudEnabled && mHudHandler != null) {
            mHudHandler.removeCallbacks(mHudTick);
            mHudHandler.postDelayed(mHudTick, 500);
        }
    }

    @Override
    protected void onPause() {
        mResumed = false;
        mUiHandler.removeCallbacks(mRehideRunnable);
        if (mPadView != null) mPadView.resetTouches();
        if (mHudHandler != null) mHudHandler.removeCallbacks(mHudTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mResumed = false;
        mUiHandler.removeCallbacks(mRehideRunnable);
        if (mHudHandler != null) mHudHandler.removeCallbacks(mHudTick);
        if (mPerfExec != null) mPerfExec.shutdownNow();
        if (Build.VERSION.SDK_INT >= 33 && mBackCallback != null) {
            try {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                        (android.window.OnBackInvokedCallback) mBackCallback);
            } catch (Throwable ignored) {}
            mBackCallback = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        toggleExitOverlay();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (mPadView != null) mPadView.resetTouches();
        if (hasFocus) {
            hideSystemUI();
            bringOverlaysToFront();
            mUiHandler.removeCallbacks(mRehideRunnable);
            mUiHandler.postDelayed(mRehideRunnable, 250);
        }
    }

    @Override
    public void onSystemUiVisibilityChange(int visibility) {
        super.onSystemUiVisibilityChange(visibility);
        if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0
                || (visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
            mUiHandler.removeCallbacks(mRehideRunnable);
            mUiHandler.postDelayed(mRehideRunnable, 550);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            int src = event.getSource();
            boolean fromPad = (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
            if (fromPad) {
                // Xbox One/Series BT View button arrives as KEYCODE_BACK on Android;
                // translate it to SELECT so pressing View does not exit the game.
                int devId = event.getDeviceId();
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    SDLControllerManager.onNativePadDown(devId, KeyEvent.KEYCODE_BUTTON_SELECT);
                    return true;
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    SDLControllerManager.onNativePadUp(devId, KeyEvent.KEYCODE_BUTTON_SELECT);
                    return true;
                }
            } else {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    toggleExitOverlay();
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void toggleExitOverlay() {
        if (isFinishing() || isDestroyed() || mExitingToLauncher) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - mLastBackToggleMs < 200L) return;
        mLastBackToggleMs = now;

        ensureExitOverlayCreated();
        if (mExitOverlay == null) return;
        if (mExitOverlay.getVisibility() == View.VISIBLE) {
            hideExitOverlay();
        } else {
            if (mPadView != null) mPadView.resetTouches();
            mExitOverlay.setVisibility(View.VISIBLE);
            mExitOverlay.bringToFront();
        }
    }

    private void hideExitOverlay() {
        if (mExitOverlay != null) mExitOverlay.setVisibility(View.GONE);
        if (mPadView != null) mPadView.resetTouches();
        hideSystemUI();
        bringOverlaysToFront();
    }

    private void ensureExitOverlayCreated() {
        if (mExitOverlay != null || mLayout == null) return;
        float d = getResources().getDisplayMetrics().density;
        int dp = (int) d;

        mExitOverlay = new FrameLayout(this);
        mExitOverlay.setBackgroundColor(Color.argb(195, 8, 8, 14));
        mExitOverlay.setClickable(true);
        mExitOverlay.setFocusable(true);
        mExitOverlay.setOnTouchListener((v, ev) -> true);
        mExitOverlay.setVisibility(View.GONE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Math.round(20 * d), Math.round(16 * d), Math.round(20 * d), Math.round(16 * d));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.argb(245, 20, 18, 26));
        cardBg.setCornerRadius(14 * d);
        cardBg.setStroke(Math.max(1, dp), Color.argb(170, 229, 57, 65));
        card.setBackground(cardBg);

        TextView title = new TextView(this);
        title.setText("Salir al Launcher");
        title.setTextColor(Color.rgb(245, 243, 240));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        card.addView(title);

        TextView msg = new TextView(this);
        msg.setText("Se cerrará la sesión actual de Drakengard 3 para regresar al launcher de PDG3Android.\n\nRecomendación: guardar partida antes de salir.");
        msg.setTextColor(Color.rgb(200, 198, 208));
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        msg.setPadding(0, Math.round(8 * d), 0, Math.round(16 * d));
        card.addView(msg);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);

        TextView btnResume = new TextView(this);
        btnResume.setText("Seguir jugando");
        btnResume.setTextColor(Color.WHITE);
        btnResume.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        btnResume.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        btnResume.setPadding(Math.round(14 * d), Math.round(9 * d), Math.round(14 * d), Math.round(9 * d));
        GradientDrawable resumeBg = new GradientDrawable();
        resumeBg.setColor(Color.argb(200, 56, 56, 74));
        resumeBg.setCornerRadius(8 * d);
        resumeBg.setStroke(Math.max(1, dp), Color.argb(80, 255, 255, 255));
        btnResume.setBackground(resumeBg);
        btnResume.setOnClickListener(v -> hideExitOverlay());
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rLp.rightMargin = Math.round(10 * d);
        btnRow.addView(btnResume, rLp);

        TextView btnExit = new TextView(this);
        btnExit.setText("Salir al launcher");
        btnExit.setTextColor(Color.WHITE);
        btnExit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        btnExit.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        btnExit.setPadding(Math.round(14 * d), Math.round(9 * d), Math.round(14 * d), Math.round(9 * d));
        GradientDrawable exitBg = new GradientDrawable();
        exitBg.setColor(Color.rgb(198, 40, 48));
        exitBg.setCornerRadius(8 * d);
        exitBg.setStroke(Math.max(1, dp), Color.rgb(229, 57, 65));
        btnExit.setBackground(exitBg);
        btnExit.setOnClickListener(v -> exitToLauncher());
        btnRow.addView(btnExit);

        card.addView(btnRow);
        FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(
                Math.round(350 * d), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        mExitOverlay.addView(card, cLp);

        RelativeLayout.LayoutParams oLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT);
        mLayout.addView(mExitOverlay, oLp);
    }

    private void exitToLauncher() {
        if (mExitingToLauncher) return;
        mExitingToLauncher = true;
        CrashReporter.markCleanExit(this);

        Intent back = new Intent(this, LauncherActivity.class);
        back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(back);
        finish();
        mUiHandler.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 180);
    }

    private String detectConnectedPadStyle() {
        for (int id : InputDevice.getDeviceIds()) {
            if (id < 0) continue;
            InputDevice dev = InputDevice.getDevice(id);
            if (dev == null) continue;
            int src = dev.getSources();
            boolean isPad = (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
            if (!isPad) continue;
            String name = dev.getName() != null ? dev.getName() : "";
            if (name.equals("DOD3 Virtual Pad")) continue;
            int vid = dev.getVendorId();
            int pid = dev.getProductId();
            String lower = name.toLowerCase();
            if (vid == 0x045E || lower.contains("xbox") || lower.contains("x-box") || lower.contains("xinput")) {
                return "xbox";
            }
            if (vid == 0x054C) {
                if (pid == 0x0CE6 || pid == 0x0DF2) return "ps5";
                if (pid == 0x05C4 || pid == 0x09CC) return "ps4";
                return "ps3";
            }
            if (vid == 0x057E || lower.contains("nintendo") || lower.contains("pro controller")) {
                return "switch";
            }
        }
        return "ps3";
    }

    private void installInsetsRehideListener() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
                boolean barsVisible = insets.isVisible(WindowInsets.Type.statusBars())
                        || insets.isVisible(WindowInsets.Type.navigationBars());
                if (barsVisible) {
                    mUiHandler.removeCallbacks(mRehideRunnable);
                    mUiHandler.postDelayed(mRehideRunnable, 550);
                }
                return v.onApplyWindowInsets(insets);
            });
        }
    }

    private void bringOverlaysToFront() {
        if (mPadView != null) mPadView.bringToFront();
        if (mFpsView != null) mFpsView.bringToFront();
        if (mExitOverlay != null && mExitOverlay.getVisibility() == View.VISIBLE) {
            mExitOverlay.bringToFront();
        }
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController ic = getWindow().getInsetsController();
            if (ic != null) {
                ic.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                ic.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }
}
