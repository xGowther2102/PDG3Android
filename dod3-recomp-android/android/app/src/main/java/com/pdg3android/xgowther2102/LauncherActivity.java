package com.pdg3android.xgowther2102;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dynamic PC-style Launcher for Drakengard 3 Recompiled (Android).
 * Reads/writes dod3.ini directly, parses ISO/PKG headers in /sdcard/Drakengard3/,
 * separates optional Mods from Required/DLC Game Files, and exposes full PC settings.
 */
public class LauncherActivity extends Activity {

    private static final int COL_BG      = Color.rgb(12, 12, 16);
    private static final int COL_CARD    = Color.argb(218, 18, 18, 25);
    private static final int COL_SIDEBAR = Color.argb(232, 14, 14, 20);
    private static final int COL_ACCENT  = Color.rgb(198, 40, 48);
    private static final int COL_ACCENT2 = Color.rgb(229, 57, 65);
    private static final int COL_TEXT    = Color.rgb(242, 240, 238);
    private static final int COL_MUTED   = Color.rgb(168, 165, 174);
    private static final int COL_BORDER  = Color.argb(46, 255, 255, 255);
    private static final int COL_OK      = Color.rgb(76, 175, 80);
    private static final int COL_WARN    = Color.rgb(255, 167, 38);

    private static final int REQ_PICK_MOD  = 1001;
    private static final int REQ_PICK_BASE = 1002;
    private static final int REQ_PICK_DLC  = 1003;

    private static final Map<String, String> KNOWN_DLC_NAMES = new LinkedHashMap<>();
    static {
        KNOWN_DLC_NAMES.put("ADDJAPANESEVOICE", "Japanese Voice Pack");
        KNOWN_DLC_NAMES.put("ADDSCENARIO00000", "Zero's Prologue");
        KNOWN_DLC_NAMES.put("ADDSCENARIO00001", "One's Prologue");
        KNOWN_DLC_NAMES.put("ADDSCENARIO00002", "Two's Prologue");
        KNOWN_DLC_NAMES.put("ADDSCENARIO00003", "Three's Prologue");
        KNOWN_DLC_NAMES.put("ADDSCENARIO00004", "Four's Prologue");
        KNOWN_DLC_NAMES.put("ADDSCENARIO00005", "Five's Prologue");
        KNOWN_DLC_NAMES.put("ADDSONG000000001", "Drakengard BGM Remix Pack");
        KNOWN_DLC_NAMES.put("ADDSONG000000002", "NieR BGM Remix Pack");
        KNOWN_DLC_NAMES.put("ADDSONG000000003", "BGM Remix Pack");
        KNOWN_DLC_NAMES.put("ADDWEAPONCLOTHE0", "Zero's Garb (Variety Pack)");
        KNOWN_DLC_NAMES.put("ADDWEAPONCLOTHE1", "Caim's Garb");
        KNOWN_DLC_NAMES.put("ADDWEAPONCLOTHE2", "Furiae's Garb");
        KNOWN_DLC_NAMES.put("ADDWEAPONCLOTHE3", "Manah's Garb");
        KNOWN_DLC_NAMES.put("ADDWEAPONCLOTHE4", "Eris's Garb");
        KNOWN_DLC_NAMES.put("ADDWEAPONCLOTHE5", "Nier's Garb");
        KNOWN_DLC_NAMES.put("ADDWEAPONCLOTHE6", "Kainé's Garb");
        KNOWN_DLC_NAMES.put("ADDWEAPONCLOTHE7", "Beautiful Child");
        KNOWN_DLC_NAMES.put("ADDWEAPONCLOTHE8", "Tokyo Tower");
        KNOWN_DLC_NAMES.put("ADDWEAPONCLOTHE9", "Experimental Weapon 7");
    }

    private File mBaseDir;
    private IniStore mIni;
    private SharedPreferences mPrefs;
    private final ExecutorService mBgExec = Executors.newSingleThreadExecutor();
    private volatile ArchiveScan mCachedScan;
    private volatile boolean mScanInFlight = false;
    private volatile Bitmap mBackdropBmp;
    private volatile boolean mBackdropLoading = false;

    private LinearLayout mContentContainer;
    private ImageView mBackdrop;
    private TextView[] mNavButtons;
    private TextView mStatusChipGame;
    private TextView mPresetDescView;
    private ControllerPreviewView mCtrlPreview;
    private TextView mCtrlPreviewTitle;
    private final Map<String, TextView> mPresetButtons = new LinkedHashMap<>();
    private int mActiveTab = 0;

    private FrameLayout mSetupOverlay;
    private LinearLayout mSetupStepperRow;
    private LinearLayout mSetupCardsContainer;
    private TextView mSetupStatusView;
    private TextView mSetupBackBtn;
    private TextView mSetupFinishBtn;
    private int mSetupStep = 0;
    private boolean mSetupManualOpen = false;
    private boolean mCrashDialogShowing = false;

    private static class GfxPreset {
        final String id, label, desc;
        final String scale, fps, vsync, aa, aniso, shadows, drawDist, treeLod;
        final boolean postfx, dof, motionBlur, distortion;

        GfxPreset(String id, String label, String desc,
                  String scale, String fps, String vsync, String aa, String aniso,
                  String shadows, String drawDist, String treeLod,
                  boolean postfx, boolean dof, boolean motionBlur, boolean distortion) {
            this.id = id;
            this.label = label;
            this.desc = desc;
            this.scale = scale;
            this.fps = fps;
            this.vsync = vsync;
            this.aa = aa;
            this.aniso = aniso;
            this.shadows = shadows;
            this.drawDist = drawDist;
            this.treeLod = treeLod;
            this.postfx = postfx;
            this.dof = dof;
            this.motionBlur = motionBlur;
            this.distortion = distortion;
        }
    }

    private static final GfxPreset[] GFX_PRESETS = new GfxPreset[] {
        new GfxPreset("recommended", "Recomendado",
                "540p · 60 FPS · Sin AA · AF 4x · Sombras Baja · Efectos y Distorsión Off (Equilibrio estable en combates)",
                "0.75", "60", "1", "", "4", "low", "", "", false, false, false, false),
        new GfxPreset("low", "Baja",
                "540p · 30 FPS · Sin AA · AF 1x · Sombras Off · Efectos y Distorsión Off (Máxima fluidez)",
                "0.75", "", "1", "", "1", "off", "", "", false, false, false, false),
        new GfxPreset("medium", "Media",
                "720p · 30 FPS · Sin AA · AF 4x · Sombras Original · Post-FX On · DoF/Distorsión Off",
                "1", "", "1", "", "4", "", "", "", true, false, false, false),
        new GfxPreset("high", "Alta",
                "900p · 60 FPS · FXAA · AF 8x · Sombras Alto · Distancia 2x · Distorsión On",
                "1.25", "60", "1", "fxaa", "8", "high", "2", "2", true, true, false, true),
        new GfxPreset("ultra", "Muy Alta",
                "1080p · 60 FPS · FXAA · AF 16x · Sombras Ultra · Distancia 3x · Todos los efectos On",
                "1.5", "60", "1", "fxaa", "16", "ultra", "3", "3", true, true, true, true),
    };

    private static class PkgMeta {
        boolean valid;
        boolean debug;
        String contentId = "";
    }

    private static class DlcEntry {
        File file;
        String id;
        String name;
    }

    private static class ModEntry {
        File file;
        String label;
        boolean isSpanish;
        boolean enabled;
    }

    private static class ArchiveScan {
        File isoFile;
        File updatePkg;
        final List<DlcEntry> dlcs = new ArrayList<>();
        final List<ModEntry> mods = new ArrayList<>();
        final List<String> misplacedNotes = new ArrayList<>();
        int activeModsCount;
        int activeSpanishCount;
        boolean hasCachedEboot;
        boolean hasLegacyUnpacked;
    }

    private boolean isMandatoryReady(ArchiveScan s) {
        if (s == null || !hasStoragePermission()) return false;
        return ((s.isoFile != null && (s.updatePkg != null || s.hasCachedEboot)) || s.hasLegacyUnpacked);
    }

    private void ensureDrakengardFolders() {
        if (!hasStoragePermission() || mBaseDir == null) return;
        if (!mBaseDir.exists()) mBaseDir.mkdirs();
        File dlcDir = new File(mBaseDir, "dlc");
        if (!dlcDir.exists()) dlcDir.mkdirs();
        File modsDir = new File(mBaseDir, "mods");
        if (!modsDir.exists()) modsDir.mkdirs();
        File logsDir = new File(mBaseDir, "logs");
        if (!logsDir.exists()) logsDir.mkdirs();
    }

    private ArchiveScan getScan() {
        ArchiveScan s = mCachedScan;
        if (s == null) {
            s = scanArchives();
            mCachedScan = s;
        }
        return s;
    }

    private void refreshArchivesAsync(final Runnable onUiDone) {
        if (mScanInFlight) return;
        mScanInFlight = true;
        mBgExec.execute(() -> {
            ArchiveScan fresh = scanArchives();
            mCachedScan = fresh;
            mScanInFlight = false;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                refreshStatusChips();
                if (onUiDone != null) onUiDone.run();
            });
        });
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            }
        }
    }

    private PkgMeta readPkgMeta(File f) {
        PkgMeta m = new PkgMeta();
        if (f == null || !f.isFile() || f.length() < 0x80) return m;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[0x60];
            if (raf.read(h) != h.length) return m;
            if (h[0] != 0x7F || h[1] != 'P' || h[2] != 'K' || h[3] != 'G') return m;
            m.valid = true;
            m.debug = (h[4] & 0x80) == 0;
            int len = 0;
            while (len < 0x24 && h[0x30 + len] != 0) len++;
            m.contentId = new String(h, 0x30, len, StandardCharsets.US_ASCII).trim();
        } catch (Exception ignored) {}
        return m;
    }

    private String knownDlcName(String contentId) {
        if (contentId == null) return null;
        String cidU = contentId.toUpperCase();
        for (Map.Entry<String, String> e : KNOWN_DLC_NAMES.entrySet()) {
            if (cidU.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    private String knownDlcId(String contentId) {
        if (contentId == null) return "";
        String cidU = contentId.toUpperCase();
        for (String k : KNOWN_DLC_NAMES.keySet()) {
            if (cidU.contains(k)) return k;
        }
        return contentId;
    }

    private String friendlyModLabel(String fname) {
        String u = fname.toUpperCase();
        if (u.equals("DRAKENGARD_ES_BLUS31197_V2.PKG")) return "Traducción ES — Parche y Textos";
        if (u.equals("DRAKENGARD_ES_BLUS31197DATA_V3.PKG")) return "Traducción ES — Menús y Datos";
        if (u.equals("DRAKENGARD_DLC_ES_BLUS31197_NPUB31251_V2.PKG")) return "Traducción ES — Capítulos DLC";
        if (u.equals("MOVIES.ZIP")) return "Traducción ES — Cinemáticas (.BIK)";
        if (u.contains("_ES_") || u.contains("ES_BLUS") || u.contains("ESP")) return "Traducción ES — Paquete";
        return fname;
    }

    private boolean isSpanishFile(String fname) {
        String u = fname.toUpperCase();
        return u.contains("_ES_") || u.contains("ES_BLUS") || u.contains("ESP") || u.equals("MOVIES.ZIP");
    }

    private boolean isModEnabled(File f) {
        String name = f.getName();
        File perModOff = new File(mBaseDir, "cache/mods_off/" + name + ".off");
        if (perModOff.exists()) return false;
        File legacyOff = new File(mBaseDir, "cache/ES-DATA.off");
        if (legacyOff.exists() && isSpanishFile(name)) return false;
        return true;
    }

    private void setModEnabled(File f, boolean enable) {
        try {
            File modsOffDir = new File(mBaseDir, "cache/mods_off");
            if (!modsOffDir.exists()) modsOffDir.mkdirs();
            File flag = new File(modsOffDir, f.getName() + ".off");
            if (enable) {
                if (flag.exists()) flag.delete();
                File legacyOff = new File(mBaseDir, "cache/ES-DATA.off");
                if (legacyOff.exists()) legacyOff.delete();
            } else {
                flag.createNewFile();
            }
            syncSpanishMenuLanguage();
        } catch (Exception ignored) {}
    }

    private void syncSpanishMenuLanguage() {
        mCachedScan = scanArchives();
        if (mCachedScan.activeSpanishCount > 0) {
            mIni.set("DOD3_MENU_LANGUAGE", "es");
        } else {
            mIni.set("DOD3_MENU_LANGUAGE", "");
        }
    }

    private ArchiveScan scanArchives() {
        ArchiveScan s = new ArchiveScan();
        s.hasCachedEboot = new File(mBaseDir, "cache/EBOOT_101.ELF").isFile();
        s.hasLegacyUnpacked = new File(mBaseDir, "elf/EBOOT_101.ELF").isFile()
                && new File(mBaseDir, "game/disc/PS3_GAME").isDirectory();
        if (!hasStoragePermission() || !mBaseDir.isDirectory()) return s;
        scanDirRecursive(mBaseDir, 0, false, false, s);
        Collections.sort(s.dlcs, Comparator.comparing(a -> a.name));
        Collections.sort(s.mods, Comparator.comparing(a -> a.label));
        return s;
    }

    private void scanDirRecursive(File dir, int depth, boolean inModsDir, boolean inDlcDir, ArchiveScan s) {
        if (depth > 2) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            String n = f.getName().toUpperCase();
            if (f.isDirectory()) {
                if (n.equals("CACHE") || n.equals("GAMEDATA") || n.equals("GAME") || n.equals("SAVES")) continue;
                scanDirRecursive(f, depth + 1, inModsDir || (depth == 0 && n.equals("MODS")), inDlcDir || (depth == 0 && n.equals("DLC")), s);
            } else if (f.isFile()) {
                if (n.endsWith(".ISO")) {
                    if (depth == 0 && s.isoFile == null) {
                        s.isoFile = f;
                    } else if (depth > 0) {
                        s.misplacedNotes.add("Mover ISO (" + f.getName() + ") a /sdcard/Drakengard3/");
                    }
                } else if (n.endsWith(".PKG")) {
                    PkgMeta meta = readPkgMeta(f);
                    if (!meta.valid) continue;
                    String cidU = meta.contentId.toUpperCase();
                    if (!meta.debug && (cidU.contains("DOD3PATCH") || cidU.contains("BLUS31197"))) {
                        if (depth == 0 && s.updatePkg == null) {
                            s.updatePkg = f;
                        } else if (depth > 0) {
                            s.misplacedNotes.add("Mover Update 1.01 (" + f.getName() + ") a /sdcard/Drakengard3/");
                        }
                    } else if (!meta.debug && cidU.contains("NPUB31251")) {
                        if (inDlcDir) {
                            DlcEntry d = new DlcEntry();
                            d.file = f;
                            d.id = knownDlcId(cidU);
                            String friendly = knownDlcName(cidU);
                            d.name = friendly != null ? friendly : f.getName();
                            s.dlcs.add(d);
                        } else {
                            s.misplacedNotes.add("Mover DLC (" + f.getName() + ") a /sdcard/Drakengard3/dlc/");
                        }
                    } else if (meta.debug || inModsDir) {
                        if (inModsDir) {
                            ModEntry m = new ModEntry();
                            m.file = f;
                            m.label = friendlyModLabel(f.getName());
                            m.isSpanish = isSpanishFile(f.getName());
                            m.enabled = isModEnabled(f);
                            s.mods.add(m);
                            if (m.enabled) {
                                s.activeModsCount++;
                                if (m.isSpanish) s.activeSpanishCount++;
                            }
                        } else {
                            s.misplacedNotes.add("Mover Mod (" + f.getName() + ") a /sdcard/Drakengard3/mods/");
                        }
                    }
                } else if (n.endsWith(".ZIP")) {
                    if (inModsDir) {
                        ModEntry m = new ModEntry();
                        m.file = f;
                        m.label = friendlyModLabel(f.getName());
                        m.isSpanish = isSpanishFile(f.getName());
                        m.enabled = isModEnabled(f);
                        s.mods.add(m);
                        if (m.enabled) {
                            s.activeModsCount++;
                            if (m.isSpanish) s.activeSpanishCount++;
                        }
                    } else if (n.contains("MOVIES")) {
                        s.misplacedNotes.add("Mover " + f.getName() + " a /sdcard/Drakengard3/mods/");
                    }
                }
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashReporter.install(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();

        mBaseDir = new File("/sdcard/Drakengard3");
        mPrefs = getSharedPreferences("dod3_android", MODE_PRIVATE);
        ensureDrakengardFolders();
        mIni = new IniStore(new File(mBaseDir, "dod3.ini"));

        if (hasStoragePermission()) {
            boolean dirty = false;
            if (mIni.get("RSX_DISPLAY", "").isEmpty()) { mIni.setInMemory("RSX_DISPLAY", "borderless"); dirty = true; }
            if (mIni.get("DOD3_LAUNCHER", "").isEmpty()) { mIni.setInMemory("DOD3_LAUNCHER", "0"); dirty = true; }
            if (dirty) mIni.save();
        }

        buildRootUI();
        selectTab(0);
        if (needsMandatorySetup(getScan())) {
            showSetupGuide(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        ensureDrakengardFolders();
        if (mIni != null && hasStoragePermission()) mIni.load();
        loadBackdrop();
        refreshArchivesAsync(() -> {
            if (isFinishing() || isDestroyed()) return;
            ArchiveScan s = getScan();
            if (needsMandatorySetup(s)) {
                showSetupGuide(false);
            } else if (mSetupOverlay != null && mSetupOverlay.getVisibility() == View.VISIBLE) {
                populateSetupOverlay();
            } else if (mContentContainer != null && (mActiveTab == 0 || mActiveTab == 3 || mActiveTab == 4)) {
                selectTab(mActiveTab);
            }
        });
        checkPendingCrashAsync();
    }

    @Override
    protected void onDestroy() {
        mBgExec.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mSetupOverlay != null && mSetupOverlay.getVisibility() == View.VISIBLE) {
            if (mSetupStep > 0) {
                mSetupStep--;
                populateSetupOverlay();
                return;
            }
            if (needsMandatorySetup(getScan())) {
                finish();
                return;
            }
            mSetupOverlay.setVisibility(View.GONE);
            mSetupManualOpen = false;
            return;
        }
        super.onBackPressed();
    }

    private boolean needsMandatorySetup(ArchiveScan s) {
        return !mPrefs.getBoolean("setup_completed", false) || !hasStoragePermission() || !isMandatoryReady(s);
    }

    private void loadBackdrop() {
        if (mBackdrop == null || !hasStoragePermission() || mBackdropBmp != null || mBackdropLoading) return;
        mBackdropLoading = true;
        mBgExec.execute(() -> {
            Bitmap bmp = null;
            try {
                File pic1 = new File(mBaseDir, "cache/PIC1.PNG");
                if (!pic1.isFile()) pic1 = new File(mBaseDir, "game/disc/PS3_GAME/PIC1.PNG");
                if (pic1.isFile()) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 2;
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    bmp = BitmapFactory.decodeFile(pic1.getAbsolutePath(), opts);
                }
            } catch (Throwable ignored) {}
            final Bitmap loaded = bmp;
            mBackdropLoading = false;
            if (loaded != null) {
                mBackdropBmp = loaded;
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && mBackdrop != null) {
                        mBackdrop.setImageBitmap(loaded);
                    }
                });
            }
        });
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildRootUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COL_BG);

        mBackdrop = new ImageView(this);
        mBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        loadBackdrop();
        root.addView(mBackdrop, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        View scrim = new View(this);
        GradientDrawable grad = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] { Color.argb(235, 10, 10, 14), Color.argb(185, 12, 10, 16), Color.argb(215, 10, 10, 14) });
        scrim.setBackground(grad);
        root.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout mainRow = new LinearLayout(this);
        mainRow.setOrientation(LinearLayout.HORIZONTAL);
        mainRow.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.addView(mainRow, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // --- LEFT SIDEBAR ---
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(12), dp(10), dp(12), dp(10));
        sidebar.setBackground(makeRounded(COL_SIDEBAR, dp(14), COL_BORDER));
        LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(dp(216), LinearLayout.LayoutParams.MATCH_PARENT);
        sideLp.rightMargin = dp(12);
        mainRow.addView(sidebar, sideLp);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = dp(4);

        ImageView ivIcon = new ImageView(this);
        ivIcon.setImageResource(R.mipmap.ic_launcher);
        ivIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        icLp.rightMargin = dp(8);
        headerRow.addView(ivIcon, icLp);

        LinearLayout hdrCol = new LinearLayout(this);
        hdrCol.setOrientation(LinearLayout.VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("PDG3Android");
        tvTitle.setTextColor(COL_TEXT);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvTitle.setTypeface(Typeface.SERIF, Typeface.BOLD);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        hdrCol.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText("DRAKENGARD 3 · v" + getAppVersion());
        tvSub.setTextColor(COL_ACCENT2);
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        tvSub.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        tvSub.setSingleLine(true);
        hdrCol.addView(tvSub);

        headerRow.addView(hdrCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        sidebar.addView(headerRow, hlp);

        mStatusChipGame = makeStatusChip("Verificando...");
        mStatusChipGame.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        mStatusChipGame.setSingleLine(true);
        mStatusChipGame.setEllipsize(TextUtils.TruncateAt.END);
        sidebar.addView(mStatusChipGame);

        View div = new View(this);
        div.setBackgroundColor(COL_BORDER);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp.topMargin = dp(4);
        divLp.bottomMargin = dp(6);
        sidebar.addView(div, divLp);

        // 6 Dedicated Nav Tabs inside ScrollView so ▶ JUGAR never overflows
        ScrollView navScroll = new ScrollView(this);
        navScroll.setVerticalScrollBarEnabled(false);
        LinearLayout navList = new LinearLayout(this);
        navList.setOrientation(LinearLayout.VERTICAL);
        navScroll.addView(navList, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        String[] tabNames = { "Inicio", "Gráficos", "Sistema y Controles", "Mods", "Archivos", "Trucos", "Información" };
        mNavButtons = new TextView[tabNames.length];
        for (int i = 0; i < tabNames.length; i++) {
            final int idx = i;
            TextView b = new TextView(this);
            b.setText(tabNames[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            b.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
            b.setSingleLine(true);
            b.setEllipsize(TextUtils.TruncateAt.END);
            b.setPadding(dp(10), dp(7), dp(10), dp(7));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.bottomMargin = dp(3);
            b.setOnClickListener(v -> selectTab(idx));
            navList.addView(b, blp);
            mNavButtons[i] = b;
        }
        sidebar.addView(navScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView btnPlay = new TextView(this);
        btnPlay.setText("▶  JUGAR");
        btnPlay.setGravity(Gravity.CENTER);
        btnPlay.setTextColor(Color.WHITE);
        btnPlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        btnPlay.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        btnPlay.setPadding(dp(10), dp(9), dp(10), dp(9));
        btnPlay.setBackground(makeRounded(COL_ACCENT, dp(10), Color.argb(90, 255, 160, 160)));
        btnPlay.setOnClickListener(v -> launchGame());
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        playLp.topMargin = dp(8);
        sidebar.addView(btnPlay, playLp);

        // --- RIGHT CONTENT PANE ---
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(makeRounded(COL_CARD, dp(14), COL_BORDER));
        mainRow.addView(scroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        mContentContainer = new LinearLayout(this);
        mContentContainer.setOrientation(LinearLayout.VERTICAL);
        mContentContainer.setPadding(dp(20), dp(16), dp(20), dp(20));
        scroll.addView(mContentContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        buildSetupOverlay(root);

        setContentView(root);
        refreshStatusChips();
    }

    private void buildSetupOverlay(FrameLayout root) {
        mSetupOverlay = new FrameLayout(this);
        mSetupOverlay.setBackgroundColor(Color.argb(248, 10, 10, 15));
        mSetupOverlay.setClickable(true);
        mSetupOverlay.setFocusable(true);
        mSetupOverlay.setVisibility(View.GONE);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(20), dp(14), dp(20), dp(12));
        mSetupOverlay.addView(shell, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Header bar
        LinearLayout topHdr = new LinearLayout(this);
        topHdr.setOrientation(LinearLayout.HORIZONTAL);
        topHdr.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams thLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        thLp.bottomMargin = dp(8);

        ImageView ic = new ImageView(this);
        ic.setImageResource(R.mipmap.ic_launcher);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        icLp.rightMargin = dp(10);
        topHdr.addView(ic, icLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView tMain = new TextView(this);
        tMain.setText("Guía de Instalación · PDG3Android");
        tMain.setTextColor(COL_TEXT);
        tMain.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tMain.setTypeface(Typeface.SERIF, Typeface.BOLD);
        titleCol.addView(tMain);

        TextView tSub = new TextView(this);
        tSub.setText("Asistente paso a paso para permiso, archivos obligatorios, DLCs y mods");
        tSub.setTextColor(COL_MUTED);
        tSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        titleCol.addView(tSub);
        topHdr.addView(titleCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        shell.addView(topHdr, thLp);

        // 4-step horizontal stepper
        mSetupStepperRow = new LinearLayout(this);
        mSetupStepperRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams stpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stpLp.bottomMargin = dp(10);
        shell.addView(mSetupStepperRow, stpLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        mSetupCardsContainer = new LinearLayout(this);
        mSetupCardsContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mSetupCardsContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        shell.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Bottom wizard bar
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        bottomBar.setBackground(makeRounded(Color.argb(230, 18, 18, 26), dp(10), COL_BORDER));
        LinearLayout.LayoutParams bbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bbLp.topMargin = dp(8);

        mSetupStatusView = new TextView(this);
        mSetupStatusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        mSetupStatusView.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        stLp.rightMargin = dp(10);
        bottomBar.addView(mSetupStatusView, stLp);

        mSetupBackBtn = makeActionButton("← Anterior");
        ((LinearLayout.LayoutParams) mSetupBackBtn.getLayoutParams()).rightMargin = dp(8);
        mSetupBackBtn.setOnClickListener(v -> {
            if (mSetupStep > 0) {
                mSetupStep--;
                populateSetupOverlay();
            }
        });
        bottomBar.addView(mSetupBackBtn);

        TextView btnVerify = makeActionButton("↻ Verificar");
        ((LinearLayout.LayoutParams) btnVerify.getLayoutParams()).rightMargin = dp(8);
        btnVerify.setOnClickListener(v -> {
            mCachedScan = null;
            refreshArchivesAsync(this::populateSetupOverlay);
        });
        bottomBar.addView(btnVerify);

        mSetupFinishBtn = makeAccentButton("Siguiente →");
        mSetupFinishBtn.setOnClickListener(v -> handleSetupPrimaryAction());
        bottomBar.addView(mSetupFinishBtn);

        shell.addView(bottomBar, bbLp);
        root.addView(mSetupOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void showSetupGuide(boolean manual) {
        if (mSetupOverlay == null) return;
        mSetupManualOpen = manual;
        ArchiveScan s = getScan();
        if (!hasStoragePermission()) {
            mSetupStep = 0;
        } else if (!manual && !isMandatoryReady(s)) {
            mSetupStep = 1;
        } else {
            mSetupStep = 0;
        }
        mSetupOverlay.setVisibility(View.VISIBLE);
        mSetupOverlay.bringToFront();
        populateSetupOverlay();
    }

    private void updateSetupStepper(boolean permOk, boolean mandatoryOk, ArchiveScan s) {
        if (mSetupStepperRow == null) return;
        mSetupStepperRow.removeAllViews();
        String[] labels = {
            "1. Permisos",
            "2. Base obligatoria",
            "3. DLCs (" + s.dlcs.size() + ")",
            "4. Mods y fin"
        };
        boolean[] done = {
            permOk,
            permOk && mandatoryOk,
            permOk && mandatoryOk && !s.dlcs.isEmpty(),
            permOk && mandatoryOk && mPrefs.getBoolean("setup_completed", false)
        };
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean active = (i == mSetupStep);
            TextView pill = new TextView(this);
            pill.setText((done[i] && !active ? "✓ " : "") + labels[i]);
            pill.setGravity(Gravity.CENTER);
            pill.setSingleLine(true);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            pill.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
            pill.setPadding(dp(8), dp(6), dp(8), dp(6));
            if (active) {
                pill.setTextColor(Color.WHITE);
                pill.setBackground(makeRounded(COL_ACCENT, dp(8), COL_ACCENT2));
            } else if (done[i]) {
                pill.setTextColor(COL_OK);
                pill.setBackground(makeRounded(Color.argb(120, 24, 52, 32), dp(8), Color.argb(110, 76, 175, 80)));
            } else {
                pill.setTextColor(COL_MUTED);
                pill.setBackground(makeRounded(Color.argb(130, 32, 32, 44), dp(8), COL_BORDER));
            }
            pill.setOnClickListener(v -> {
                if (idx == 0 || (permOk && (idx == 1 || mandatoryOk))) {
                    mSetupStep = idx;
                    populateSetupOverlay();
                } else if (!permOk) {
                    Toast.makeText(this, "Completar Paso 1 (permiso de almacenamiento) primero.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Completar Paso 2 (.iso y Update 1.01) para continuar.", Toast.LENGTH_SHORT).show();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i < labels.length - 1) lp.rightMargin = dp(6);
            mSetupStepperRow.addView(pill, lp);
        }
    }

    private void addStepMisplacedCard(List<String> notes, String filterKeyword) {
        if (notes == null || notes.isEmpty()) return;
        List<String> matching = new ArrayList<>();
        for (String n : notes) {
            if (filterKeyword == null || n.toUpperCase().contains(filterKeyword)) {
                matching.add(n);
            }
        }
        if (matching.isEmpty()) return;
        LinearLayout warnCard = makeRowCard();
        warnCard.setBackground(makeRounded(Color.argb(170, 58, 38, 12), dp(10), COL_WARN));
        TextView wt = new TextView(this);
        wt.setText("Archivos fuera de su ruta correspondiente");
        wt.setTextColor(COL_WARN);
        wt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        wt.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        warnCard.addView(wt);
        int limit = Math.min(8, matching.size());
        for (int i = 0; i < limit; i++) {
            addInfoLine(warnCard, "•", matching.get(i));
        }
        if (matching.size() > limit) {
            addInfoLine(warnCard, "•", "Y " + (matching.size() - limit) + " archivo(s) adicional(es)");
        }
        mSetupCardsContainer.addView(warnCard);
    }

    private void populateSetupOverlay() {
        if (mSetupCardsContainer == null) return;
        mSetupCardsContainer.removeAllViews();
        if (mSetupStep < 0) mSetupStep = 0;
        if (mSetupStep > 3) mSetupStep = 3;

        final boolean permOk = hasStoragePermission();
        if (permOk) ensureDrakengardFolders();
        final ArchiveScan s = getScan();
        final boolean isoOk = permOk && s.isoFile != null;
        final boolean updOk = permOk && (s.updatePkg != null || s.hasCachedEboot);
        final boolean mandatoryOk = permOk && isMandatoryReady(s);

        updateSetupStepper(permOk, mandatoryOk, s);

        if (mSetupStep == 0) {
            // STEP 1 of 4: Permission & Directory Layout
            LinearLayout card1 = makeRowCard();
            LinearLayout hdr1 = new LinearLayout(this);
            hdr1.setOrientation(LinearLayout.HORIZONTAL);
            hdr1.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout left1 = new LinearLayout(this);
            left1.setOrientation(LinearLayout.VERTICAL);
            TextView t1 = new TextView(this);
            t1.setText("Paso 1 de 4 · Permiso y estructura de carpetas (Obligatorio)");
            t1.setTextColor(COL_TEXT);
            t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t1.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
            left1.addView(t1);

            TextView s1 = new TextView(this);
            s1.setText("Autorizar acceso al almacenamiento para crear y validar las carpetas en /sdcard/Drakengard3/.");
            s1.setTextColor(COL_MUTED);
            s1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            s1.setPadding(0, dp(2), 0, dp(6));
            left1.addView(s1);
            hdr1.addView(left1, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (!permOk) {
                TextView btnPerm = makeAccentButton("Conceder permiso");
                btnPerm.setOnClickListener(v -> requestStoragePermission());
                hdr1.addView(btnPerm);
            }
            card1.addView(hdr1);

            addStatusLine(card1, permOk,
                    "Acceso a todos los archivos",
                    permOk ? "Permiso concedido y carpetas preparadas"
                           : "Pendiente — requerido para leer /sdcard/Drakengard3/");
            mSetupCardsContainer.addView(card1);

            LinearLayout mapCard = makeRowCard();
            TextView mt = new TextView(this);
            mt.setText("Distribución obligatoria de rutas");
            mt.setTextColor(COL_TEXT);
            mt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            mt.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
            mt.setPadding(0, 0, 0, dp(4));
            mapCard.addView(mt);
            addInfoLine(mapCard, "Base (.iso y Update 1.01 .pkg)", "/sdcard/Drakengard3/");
            addInfoLine(mapCard, "DLCs oficiales (.pkg)", "/sdcard/Drakengard3/dlc/");
            addInfoLine(mapCard, "Mods y Traducción ES (.pkg / .zip)", "/sdcard/Drakengard3/mods/");
            addInfoLine(mapCard, "Registros y diagnóstico (.txt)", "/sdcard/Drakengard3/logs/");
            mSetupCardsContainer.addView(mapCard);

        } else if (mSetupStep == 1) {
            // STEP 2 of 4: Mandatory Files
            LinearLayout card2 = makeRowCard();
            LinearLayout hdr2 = new LinearLayout(this);
            hdr2.setOrientation(LinearLayout.HORIZONTAL);
            hdr2.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout left2 = new LinearLayout(this);
            left2.setOrientation(LinearLayout.VERTICAL);
            TextView t2 = new TextView(this);
            t2.setText("Paso 2 de 4 · Archivos obligatorios (BLUS31197)");
            t2.setTextColor(COL_TEXT);
            t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t2.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
            left2.addView(t2);

            TextView s2 = new TextView(this);
            s2.setText("Ubicar la imagen ISO y la actualización 1.01 directamente en /sdcard/Drakengard3/ (sin descomprimir).");
            s2.setTextColor(COL_MUTED);
            s2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            s2.setPadding(0, dp(2), 0, dp(6));
            left2.addView(s2);
            hdr2.addView(left2, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView btnImportBase = makeAccentButton("＋ Importar ISO / 1.01");
            btnImportBase.setOnClickListener(v -> openFilePicker(REQ_PICK_BASE));
            hdr2.addView(btnImportBase);
            card2.addView(hdr2);

            addStatusLine(card2, isoOk,
                    "Disco USA desencriptado (.iso BLUS31197)",
                    isoOk ? (s.isoFile.getName() + " (" + formatBytes(s.isoFile.length()) + ")")
                          : "Falta — copiar el archivo .iso en /sdcard/Drakengard3/");
            addStatusLine(card2, updOk,
                    "Actualización oficial 1.01 (.pkg DOD3PATCH)",
                    s.updatePkg != null ? (s.updatePkg.getName() + " (" + formatBytes(s.updatePkg.length()) + ")")
                                        : (s.hasCachedEboot ? "EBOOT_101.ELF verificado en caché"
                                                            : "Falta — copiar el archivo .pkg 1.01 en /sdcard/Drakengard3/"));
            mSetupCardsContainer.addView(card2);

            addStepMisplacedCard(s.misplacedNotes, "DRAKENGARD3/");

        } else if (mSetupStep == 2) {
            // STEP 3 of 4: Optional Official DLCs
            LinearLayout card3 = makeRowCard();
            LinearLayout dlcHdr = new LinearLayout(this);
            dlcHdr.setOrientation(LinearLayout.HORIZONTAL);
            dlcHdr.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout dlcLeft = new LinearLayout(this);
            dlcLeft.setOrientation(LinearLayout.VERTICAL);
            TextView dt = new TextView(this);
            dt.setText("Paso 3 de 4 · DLCs oficiales (Opcional)");
            dt.setTextColor(COL_TEXT);
            dt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            dt.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
            dlcLeft.addView(dt);

            TextView ds = new TextView(this);
            ds.setText("Ruta dedicada: /sdcard/Drakengard3/dlc/ · " + s.dlcs.size() + " / 20 paquetes detectados");
            ds.setTextColor(s.dlcs.isEmpty() ? COL_MUTED : COL_OK);
            ds.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            ds.setPadding(0, dp(2), 0, dp(6));
            dlcLeft.addView(ds);
            dlcHdr.addView(dlcLeft, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView btnDlc = makeAccentButton("＋ Importar DLC (.pkg)");
            btnDlc.setOnClickListener(v -> openFilePicker(REQ_PICK_DLC));
            dlcHdr.addView(btnDlc);
            card3.addView(dlcHdr);

            addInfoLine(card3, "Nota", "Incluye prólogos de Intoners, voces japonesas, atuendos y música. Puede omitirse e instalarse después.");
            int showCount = Math.min(5, s.dlcs.size());
            for (int i = 0; i < showCount; i++) {
                DlcEntry d = s.dlcs.get(i);
                addStatusLine(card3, true, d.name, formatBytes(d.file.length()) + " · " + d.file.getName());
            }
            if (s.dlcs.size() > showCount) {
                addInfoLine(card3, "+", (s.dlcs.size() - showCount) + " paquete(s) DLC adicional(es) listos en dlc/");
            }
            mSetupCardsContainer.addView(card3);

            addStepMisplacedCard(s.misplacedNotes, "/DLC/");

        } else {
            // STEP 4 of 4: Optional Mods & Final Readiness Summary
            LinearLayout card4 = makeRowCard();
            LinearLayout modHdr = new LinearLayout(this);
            modHdr.setOrientation(LinearLayout.HORIZONTAL);
            modHdr.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout modLeft = new LinearLayout(this);
            modLeft.setOrientation(LinearLayout.VERTICAL);
            TextView mt = new TextView(this);
            mt.setText("Paso 4 de 4 · Mods opcionales y verificación final");
            mt.setTextColor(COL_TEXT);
            mt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            mt.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
            modLeft.addView(mt);

            TextView ms = new TextView(this);
            ms.setText("Ruta dedicada: /sdcard/Drakengard3/mods/ · " + s.activeModsCount + " / " + s.mods.size() + " activos");
            ms.setTextColor(s.mods.isEmpty() ? COL_MUTED : COL_OK);
            ms.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            ms.setPadding(0, dp(2), 0, dp(6));
            modLeft.addView(ms);
            modHdr.addView(modLeft, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView btnMod = makeAccentButton("＋ Importar Mod (.pkg / .zip)");
            btnMod.setOnClickListener(v -> openFilePicker(REQ_PICK_MOD));
            modHdr.addView(btnMod);
            card4.addView(modHdr);

            int modShow = Math.min(5, s.mods.size());
            if (modShow == 0) {
                addInfoLine(card4, "Estado", "Ningún mod en /sdcard/Drakengard3/mods/ (ej. paquetes de Traducción ES o MOVIES.ZIP).");
            } else {
                for (int i = 0; i < modShow; i++) {
                    ModEntry m = s.mods.get(i);
                    addStatusLine(card4, m.enabled, m.label, (m.enabled ? "Activo · " : "Desactivado · ") + m.file.getName());
                }
                if (s.mods.size() > modShow) {
                    addInfoLine(card4, "+", (s.mods.size() - modShow) + " mod(s) adicional(es) detectado(s)");
                }
            }
            mSetupCardsContainer.addView(card4);

            addStepMisplacedCard(s.misplacedNotes, "/MODS/");

            LinearLayout sumCard = makeRowCard();
            TextView st = new TextView(this);
            st.setText("Resumen de instalación");
            st.setTextColor(COL_TEXT);
            st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            st.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
            st.setPadding(0, 0, 0, dp(4));
            sumCard.addView(st);
            addStatusLine(sumCard, permOk, "Permiso de almacenamiento", permOk ? "Correcto" : "Pendiente");
            addStatusLine(sumCard, mandatoryOk, "Juego base + Update 1.01", mandatoryOk ? "Listos en /sdcard/Drakengard3/" : "Incompleto");
            addInfoLine(sumCard, "DLCs en dlc/", s.dlcs.size() + " paquetes");
            addInfoLine(sumCard, "Mods en mods/", s.activeModsCount + " activos / " + s.mods.size() + " detectados");
            mSetupCardsContainer.addView(sumCard);
        }

        if (mSetupBackBtn != null) {
            mSetupBackBtn.setVisibility(mSetupStep > 0 ? View.VISIBLE : View.GONE);
        }

        if (mSetupStatusView != null) {
            if (mSetupStep == 0) {
                mSetupStatusView.setText(permOk ? "✓ Paso 1 completo: permiso listo" : "✗ Paso 1: permiso requerido");
                mSetupStatusView.setTextColor(permOk ? COL_OK : COL_WARN);
            } else if (mSetupStep == 1) {
                mSetupStatusView.setText(mandatoryOk ? "✓ Paso 2 completo: ISO y 1.01 listos"
                        : "✗ Paso 2: faltan ISO o .pkg 1.01 en /sdcard/Drakengard3/");
                mSetupStatusView.setTextColor(mandatoryOk ? COL_OK : COL_WARN);
            } else if (mSetupStep == 2) {
                mSetupStatusView.setText("Paso 3 (opcional): " + s.dlcs.size() + " / 20 DLCs detectados");
                mSetupStatusView.setTextColor(s.dlcs.isEmpty() ? COL_MUTED : COL_OK);
            } else {
                mSetupStatusView.setText(mandatoryOk
                        ? ("✓ Listo (" + s.dlcs.size() + " DLCs · " + s.activeModsCount + " mods)")
                        : "✗ Completar archivos obligatorios");
                mSetupStatusView.setTextColor(mandatoryOk ? COL_OK : COL_WARN);
            }
        }

        if (mSetupFinishBtn != null) {
            boolean stepCanAdvance = (mSetupStep == 0 && permOk)
                    || (mSetupStep == 1 && mandatoryOk)
                    || (mSetupStep == 2)
                    || (mSetupStep == 3 && mandatoryOk);
            if (mSetupStep < 3) {
                mSetupFinishBtn.setText("Siguiente →");
            } else {
                mSetupFinishBtn.setText(mSetupManualOpen ? "CERRAR Y VOLVER" : "FINALIZAR E INICIAR");
            }
            if (stepCanAdvance) {
                mSetupFinishBtn.setTextColor(Color.WHITE);
                mSetupFinishBtn.setBackground(makeRounded(COL_ACCENT, dp(8), COL_ACCENT2));
            } else {
                mSetupFinishBtn.setTextColor(COL_MUTED);
                mSetupFinishBtn.setBackground(makeRounded(Color.argb(130, 55, 55, 68), dp(8), COL_BORDER));
            }
        }
    }

    private void handleSetupPrimaryAction() {
        if (mSetupStep == 0) {
            if (!hasStoragePermission()) {
                Toast.makeText(this, "Se requiere conceder permiso de almacenamiento primero.", Toast.LENGTH_LONG).show();
                requestStoragePermission();
                return;
            }
            ensureDrakengardFolders();
            mSetupStep = 1;
            populateSetupOverlay();
            return;
        }
        if (mSetupStep == 1) {
            mCachedScan = scanArchives();
            ArchiveScan s = mCachedScan;
            if (!isMandatoryReady(s)) {
                if (s.isoFile == null && s.updatePkg == null && !s.hasCachedEboot) {
                    Toast.makeText(this, "Colocar la .iso y el .pkg Update 1.01 dentro de /sdcard/Drakengard3/.", Toast.LENGTH_LONG).show();
                } else if (s.isoFile == null) {
                    Toast.makeText(this, "Falta el archivo .iso (BLUS31197) dentro de /sdcard/Drakengard3/.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Falta el paquete oficial Update 1.01 (.pkg) dentro de /sdcard/Drakengard3/.", Toast.LENGTH_LONG).show();
                }
                populateSetupOverlay();
                return;
            }
            mSetupStep = 2;
            populateSetupOverlay();
            return;
        }
        if (mSetupStep == 2) {
            mSetupStep = 3;
            populateSetupOverlay();
            return;
        }
        handleSetupFinish();
    }

    private void handleSetupFinish() {
        if (!hasStoragePermission()) {
            mSetupStep = 0;
            Toast.makeText(this, "Se requiere conceder permiso de almacenamiento primero.", Toast.LENGTH_LONG).show();
            requestStoragePermission();
            populateSetupOverlay();
            return;
        }
        mCachedScan = null;
        mCachedScan = scanArchives();
        ArchiveScan s = mCachedScan;
        if (!isMandatoryReady(s)) {
            mSetupStep = 1;
            Toast.makeText(this, "Faltan archivos obligatorios (.iso o .pkg 1.01) en /sdcard/Drakengard3/.", Toast.LENGTH_LONG).show();
            populateSetupOverlay();
            return;
        }
        mPrefs.edit().putBoolean("setup_completed", true).apply();
        mSetupManualOpen = false;
        if (mSetupOverlay != null) mSetupOverlay.setVisibility(View.GONE);
        refreshStatusChips();
        selectTab(mActiveTab);
    }

    private TextView makeStatusChip(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tv.setTextColor(COL_MUTED);
        tv.setPadding(0, dp(2), 0, dp(2));
        return tv;
    }

    private void refreshStatusChips() {
        if (mStatusChipGame == null) return;
        if (!hasStoragePermission()) {
            mStatusChipGame.setText("● Permiso requerido");
            mStatusChipGame.setTextColor(COL_WARN);
            return;
        }
        ArchiveScan s = getScan();
        boolean readyDirect = (s.isoFile != null) && (s.updatePkg != null || s.hasCachedEboot);
        if (readyDirect) {
            mStatusChipGame.setText("● ISO + 1.01 · " + s.dlcs.size() + " DLCs");
            mStatusChipGame.setTextColor(COL_OK);
        } else if (s.hasLegacyUnpacked) {
            mStatusChipGame.setText("● BLUS31197 1.01 listo");
            mStatusChipGame.setTextColor(COL_OK);
        } else {
            mStatusChipGame.setText("● Faltan ISO / 1.01");
            mStatusChipGame.setTextColor(COL_WARN);
        }
    }

    private void selectTab(int idx) {
        mActiveTab = idx;
        for (int i = 0; i < mNavButtons.length; i++) {
            boolean active = (i == idx);
            mNavButtons[i].setTextColor(active ? Color.WHITE : COL_MUTED);
            mNavButtons[i].setBackground(active
                    ? makeRounded(Color.argb(180, 198, 40, 48), dp(8), Color.TRANSPARENT)
                    : makeRounded(Color.TRANSPARENT, dp(8), Color.TRANSPARENT));
        }
        mContentContainer.removeAllViews();
        switch (idx) {
            case 0: buildHomeTab(); break;
            case 1: buildGraphicsTab(); break;
            case 2: buildSystemTab(); break;
            case 3: buildModsTab(); break;
            case 4: buildFilesTab(); break;
            case 5: buildCheatsTab(); break;
            case 6: buildInfoTab(); break;
        }
    }

    private String getAppVersion() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (pi != null && pi.versionName != null && !pi.versionName.isEmpty()) {
                return pi.versionName;
            }
        } catch (Exception ignored) {}
        return "0.6.0";
    }

    // ==================== TAB 0: HOME ====================
    private void buildHomeTab() {
        // 1. Hero card
        LinearLayout hero = makeRowCard();
        TextView ht = new TextView(this);
        ht.setText("PDG3Android · Drakengard 3");
        ht.setTextColor(COL_TEXT);
        ht.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        ht.setTypeface(Typeface.SERIF, Typeface.BOLD);
        hero.addView(ht);

        TextView hs = new TextView(this);
        hs.setText("Port nativo Android · Vulkan · Montaje directo ISO/PKG");
        hs.setTextColor(COL_MUTED);
        hs.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hs.setPadding(0, dp(2), 0, dp(10));
        hero.addView(hs);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        TextView btnStart = makeAccentButton("▶  INICIAR JUEGO");
        ((LinearLayout.LayoutParams) btnStart.getLayoutParams()).rightMargin = dp(8);
        btnStart.setOnClickListener(v -> launchGame());
        actions.addView(btnStart);

        TextView btnGfx = makeActionButton("Gráficos");
        btnGfx.setPadding(dp(11), dp(8), dp(11), dp(8));
        ((LinearLayout.LayoutParams) btnGfx.getLayoutParams()).rightMargin = dp(6);
        btnGfx.setOnClickListener(v -> selectTab(1));
        actions.addView(btnGfx);

        TextView btnMods = makeActionButton("Mods");
        btnMods.setPadding(dp(11), dp(8), dp(11), dp(8));
        ((LinearLayout.LayoutParams) btnMods.getLayoutParams()).rightMargin = dp(6);
        btnMods.setOnClickListener(v -> selectTab(3));
        actions.addView(btnMods);

        TextView btnFiles = makeActionButton("Archivos");
        btnFiles.setPadding(dp(11), dp(8), dp(11), dp(8));
        btnFiles.setOnClickListener(v -> selectTab(4));
        actions.addView(btnFiles);

        hero.addView(actions);
        mContentContainer.addView(hero);

        // 2. Quick Graphics Preset card
        addGraphicsPresetCard(true);

        // 3. System Status summary card
        LinearLayout statusCard = makeRowCard();
        TextView stHdr = new TextView(this);
        stHdr.setText("Estado del Sistema");
        stHdr.setTextColor(COL_TEXT);
        stHdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        stHdr.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        stHdr.setPadding(0, 0, 0, dp(4));
        statusCard.addView(stHdr);

        if (!hasStoragePermission()) {
            addStatusLine(statusCard, false, "Permiso de archivos requerido",
                    "Se requiere permiso de acceso a /sdcard/Drakengard3/ para leer ISO, DLCs y mods");
            TextView btnPerm = makeActionButton("Solicitar permiso de archivos");
            ((LinearLayout.LayoutParams) btnPerm.getLayoutParams()).topMargin = dp(6);
            btnPerm.setOnClickListener(v -> requestStoragePermission());
            statusCard.addView(btnPerm);
        } else {
            ArchiveScan s = getScan();
            boolean readyDirect = (s.isoFile != null) && (s.updatePkg != null || s.hasCachedEboot);
            boolean gameOk = readyDirect || s.hasLegacyUnpacked;
            String gameDetail = readyDirect
                    ? ("ISO + Update 1.01 listos · " + s.dlcs.size() + " / 20 DLCs")
                    : (s.hasLegacyUnpacked ? "BLUS31197 1.01 listo · " + s.dlcs.size() + " DLCs"
                                           : "Falta .iso o Update 1.01 .pkg en /sdcard/Drakengard3/");
            addStatusLine(statusCard, gameOk, "Juego base y DLCs", gameDetail);

            String modDetail = s.mods.isEmpty()
                    ? "Ninguno instalado en /sdcard/Drakengard3/mods/"
                    : (s.activeModsCount > 0
                        ? (s.activeModsCount + " / " + s.mods.size() + " activos"
                            + (s.activeSpanishCount > 0 ? " (Traducción ES)" : ""))
                        : (s.mods.size() + " instalados (pausados)"));
            addStatusLine(statusCard, s.activeModsCount > 0, "Mods opcionales", modDetail);

            File sc = new File(mBaseDir, "cache/spirv");
            int spvCount = (sc.isDirectory() && sc.list() != null) ? sc.list().length : 0;
            File vkCache = new File(mBaseDir, "cache/vk_pipeline.bin");
            String cacheDetail = vkCache.isFile()
                    ? (spvCount + " shaders SPIR-V · Pipeline Vulkan " + formatBytes(vkCache.length()))
                    : (spvCount + " shaders SPIR-V · Pipeline Vulkan se creará al iniciar");
            addStatusLine(statusCard, spvCount > 0 || vkCache.isFile(), "Caché gráfica", cacheDetail);

            String curPreset = detectGraphicsPreset();
            String scale = normalizeScale(mIni.get("RSX_SCALE", "1"));
            String fpsRaw = mIni.get("DOD3_FPS", "");
            String fpsLbl = fpsRaw.isEmpty() ? "30 FPS"
                    : (fpsRaw.equalsIgnoreCase("adaptive") ? "Adaptativo"
                    : (fpsRaw.equalsIgnoreCase("uncapped") ? "Sin límite" : fpsRaw + " FPS"));
            String aaLbl = mIni.get("RSX_AA", "").equalsIgnoreCase("fxaa") ? "FXAA" : "Sin AA";
            String aniso = mIni.get("RSX_ANISO", "4");
            addStatusLine(statusCard, true,
                    "Perfil gráfico activo: " + presetLabel(curPreset),
                    "Resolución " + scale + "x · " + fpsLbl + " · " + aaLbl + " · AF " + aniso + "x");
        }
        mContentContainer.addView(statusCard);
    }

    // ==================== QUICK GRAPHICS PRESETS ====================
    private String normalizeScale(String v) {
        if (v == null || v.isEmpty()) return "1";
        if (v.equals("1.0") || v.equals("1.00")) return "1";
        return v;
    }

    private String detectGraphicsPreset() {
        String scale      = normalizeScale(mIni.get("RSX_SCALE", "1"));
        String fps        = mIni.get("DOD3_FPS", "");
        String vsync      = mIni.getBool("RSX_VSYNC", true) ? "1" : "0";
        String aa         = mIni.get("RSX_AA", "").toLowerCase();
        String aniso      = mIni.get("RSX_ANISO", "4");
        String shadows    = mIni.get("DOD3_SHADOWS", "").toLowerCase();
        String drawDist   = mIni.get("DOD3_DRAW_DISTANCE", "");
        String treeLod    = mIni.get("DOD3_TREE_LOD", "");
        boolean postfx    = mIni.getBool("DOD3_POSTFX", true);
        boolean dof       = mIni.getBool("DOD3_DOF", true);
        boolean blur      = mIni.getBool("DOD3_MOTION_BLUR", true);
        boolean distortion = mIni.getBool("DOD3_DISTORTION", true);

        for (GfxPreset p : GFX_PRESETS) {
            if (p.scale.equals(scale)
                    && p.fps.equalsIgnoreCase(fps)
                    && p.vsync.equals(vsync)
                    && p.aa.equals(aa)
                    && p.aniso.equals(aniso)
                    && p.shadows.equals(shadows)
                    && p.drawDist.equals(drawDist)
                    && p.treeLod.equals(treeLod)
                    && p.postfx == postfx
                    && p.dof == dof
                    && p.motionBlur == blur
                    && p.distortion == distortion) {
                return p.id;
            }
        }
        return "custom";
    }

    private String presetLabel(String id) {
        for (GfxPreset p : GFX_PRESETS) {
            if (p.id.equals(id)) return p.label;
        }
        return "Personalizado";
    }

    private String presetDescription(String id) {
        for (GfxPreset p : GFX_PRESETS) {
            if (p.id.equals(id)) return p.desc;
        }
        return "Ajuste manual en la pestaña Gráficos";
    }

    private void applyGraphicsPreset(String id, boolean fromHome) {
        if ("custom".equals(id)) {
            if (fromHome) selectTab(1);
            else refreshPresetBanner();
            return;
        }
        GfxPreset target = null;
        for (GfxPreset p : GFX_PRESETS) {
            if (p.id.equals(id)) { target = p; break; }
        }
        if (target == null) return;

        mIni.setInMemory("RSX_SCALE", target.scale);
        mIni.setInMemory("DOD3_FPS", target.fps);
        mIni.setInMemory("RSX_VSYNC", target.vsync);
        mIni.setInMemory("RSX_AA", target.aa);
        mIni.setInMemory("RSX_ANISO", target.aniso);
        mIni.setInMemory("DOD3_SHADOWS", target.shadows);
        mIni.setInMemory("DOD3_DRAW_DISTANCE", target.drawDist);
        mIni.setInMemory("DOD3_TREE_LOD", target.treeLod);
        mIni.setInMemory("DOD3_POSTFX", target.postfx ? "" : "0");
        mIni.setInMemory("DOD3_DOF", target.dof ? "" : "0");
        mIni.setInMemory("DOD3_MOTION_BLUR", target.motionBlur ? "" : "0");
        mIni.setInMemory("DOD3_DISTORTION", target.distortion ? "" : "0");
        mIni.setInMemory("RSX_SKIP_OCCLUSION", "1");
        mIni.save();

        selectTab(mActiveTab);
    }

    private void refreshPresetBanner() {
        String activeId = detectGraphicsPreset();
        for (Map.Entry<String, TextView> e : mPresetButtons.entrySet()) {
            stylePill(e.getValue(), e.getKey().equals(activeId));
        }
        if (mPresetDescView != null) {
            mPresetDescView.setText(presetDescription(activeId));
        }
    }

    private void addGraphicsPresetCard(final boolean fromHome) {
        LinearLayout card = makeRowCard();
        TextView tv = new TextView(this);
        tv.setText("Perfil Gráfico Rápido");
        tv.setTextColor(COL_TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        card.addView(tv);

        TextView desc = new TextView(this);
        String activeId = detectGraphicsPreset();
        desc.setText(presetDescription(activeId));
        desc.setTextColor(COL_MUTED);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        desc.setPadding(0, dp(2), 0, dp(8));
        card.addView(desc);
        mPresetDescView = desc;

        mPresetButtons.clear();
        String[][] rows = {
            { "recommended", "Recomendado", "low", "Baja", "medium", "Media" },
            { "high", "Alta", "ultra", "Muy Alta", "custom", "Personalizado" }
        };
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            if (r == 0) {
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(6);
                row.setLayoutParams(rlp);
            }
            for (int c = 0; c < 3; c++) {
                final String id = rows[r][c * 2];
                final String lbl = rows[r][c * 2 + 1];
                TextView p = new TextView(this);
                configureTogglePill(p, lbl, id.equals(activeId));
                p.setPadding(dp(6), dp(6), dp(6), dp(6));
                p.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                p.setSingleLine(true);
                p.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                if (c < 2) plp.rightMargin = dp(6);
                p.setOnClickListener(v -> applyGraphicsPreset(id, fromHome));
                row.addView(p, plp);
                mPresetButtons.put(id, p);
            }
            card.addView(row);
        }
        mContentContainer.addView(card);
    }

    // ==================== TAB 1: GRAPHICS ====================
    private void buildGraphicsTab() {
        addSectionHeader("Gráficos y Rendimiento", "Ajustes guardados al instante en dod3.ini");
        addGraphicsPresetCard(false);

        addChoiceRow("Resolución interna", "Escala sobre 1280×720 (0.75x o 1.0x según rendimiento)",
                new String[] { "0.5x (360p)", "0.75x (540p)", "1.0x (720p)", "1.25x (900p)", "1.5x (1080p)" },
                new String[] { "0.5", "0.75", "1", "1.25", "1.5" },
                normalizeScale(mIni.get("RSX_SCALE", "1")),
                val -> { mIni.set("RSX_SCALE", val); refreshPresetBanner(); });

        addChoiceRow("Límite de FPS", "30 FPS es el modo consola; 60 FPS funciona tras la optimización SPIR-V",
                new String[] { "30 FPS", "60 FPS", "Adaptativo", "Sin límite" },
                new String[] { "", "60", "adaptive", "uncapped" },
                mIni.get("DOD3_FPS", ""),
                val -> { mIni.set("DOD3_FPS", val); refreshPresetBanner(); });

        addChoiceRow("Relación de aspecto", "Auto llena la pantalla 20:9; 16:9 mantiene el encuadre original",
                new String[] { "16:9", "Auto", "21:9" },
                new String[] { "", "auto", "21:9" },
                mIni.get("DOD3_ASPECT", ""),
                val -> mIni.set("DOD3_ASPECT", val));

        addSwitchRow("V-Sync", "Sincroniza con la pantalla para evitar tearing",
                mIni.getBool("RSX_VSYNC", true),
                on -> { mIni.set("RSX_VSYNC", on ? "1" : "0"); refreshPresetBanner(); });

        addChoiceRow("Anti-Aliasing", "FXAA suaviza bordes con costo mínimo",
                new String[] { "Off", "FXAA" },
                new String[] { "", "fxaa" },
                mIni.get("RSX_AA", ""),
                val -> { mIni.set("RSX_AA", val); refreshPresetBanner(); });

        addChoiceRow("Filtrado Anisotrópico", "Nitidez en texturas lejanas",
                new String[] { "1x", "2x", "4x", "8x", "16x" },
                new String[] { "1", "2", "4", "8", "16" },
                mIni.get("RSX_ANISO", "4"),
                val -> { mIni.set("RSX_ANISO", val); refreshPresetBanner(); });

        addChoiceRow("Campo de visión (FOV)", "Grados extra en la cámara de combate",
                new String[] { "Original", "+10°", "+15°", "+20°" },
                new String[] { "", "10", "15", "20" },
                mIni.get("DOD3_FOV", ""),
                val -> mIni.set("DOD3_FOV", val));

        addChoiceRow("Calidad de sombras", "Off o Baja aumentan el rendimiento en combates con múltiples enemigos",
                new String[] { "Off", "Baja", "Original", "Alto", "Ultra" },
                new String[] { "off", "low", "", "high", "ultra" },
                mIni.get("DOD3_SHADOWS", ""),
                val -> { mIni.set("DOD3_SHADOWS", val); refreshPresetBanner(); });

        addChoiceRow("Distancia de dibujado", "Alcance de objetos, props y detalles del escenario",
                new String[] { "Original", "2x", "3x", "5x", "10x" },
                new String[] { "", "2", "3", "5", "10" },
                mIni.get("DOD3_DRAW_DISTANCE", ""),
                val -> { mIni.set("DOD3_DRAW_DISTANCE", val); refreshPresetBanner(); });

        addChoiceRow("Detalle de vegetación", "Distancia a la que los árboles mantienen máximo detalle",
                new String[] { "Original", "2x", "3x", "5x", "10x" },
                new String[] { "", "2", "3", "5", "10" },
                mIni.get("DOD3_TREE_LOD", ""),
                val -> { mIni.set("DOD3_TREE_LOD", val); refreshPresetBanner(); });

        addSwitchRow("Post-Procesado (Bloom / Color)", "Desactivar da FPS extra en escenas pesadas",
                mIni.getBool("DOD3_POSTFX", true),
                on -> { mIni.set("DOD3_POSTFX", on ? "" : "0"); refreshPresetBanner(); });

        addSwitchRow("Profundidad de campo (DoF)", "Desenfoque de fondo en combate y cinemáticas",
                mIni.getBool("DOD3_DOF", true),
                on -> { mIni.set("DOD3_DOF", on ? "" : "0"); refreshPresetBanner(); });

        addSwitchRow("Desenfoque de movimiento", "Motion blur de cámara",
                mIni.getBool("DOD3_MOTION_BLUR", true),
                on -> { mIni.set("DOD3_MOTION_BLUR", on ? "" : "0"); refreshPresetBanner(); });

        addSwitchRow("Distorsión de partículas y combate", "Desactivar reduce carga GPU en magia, fuego y explosiones",
                mIni.getBool("DOD3_DISTORTION", true),
                on -> { mIni.set("DOD3_DISTORTION", on ? "" : "0"); refreshPresetBanner(); });

        addChoiceRow("Sangre en pantalla", "Salpicaduras de sangre durante el combate",
                new String[] { "Original", "Solo borde", "Off" },
                new String[] { "", "border", "0" },
                mIni.get("DOD3_SCREEN_BLOOD", ""),
                val -> mIni.set("DOD3_SCREEN_BLOOD", val));

        addSectionHeader("Medidor de Rendimiento en Juego",
                "Métricas en tiempo real de FPS, CPU, GPU, RAM y temperatura");

        addSwitchRow("Mostrar medidor de rendimiento",
                "Muestra la barra de métricas en pantalla durante la partida",
                mPrefs.getBoolean("fps_hud", true),
                on -> mPrefs.edit().putBoolean("fps_hud", on).apply());

        addChoiceRow("Estilo del medidor", "Nivel de detalle de la barra superior",
                new String[] { "Solo FPS", "Estándar", "Detallado" },
                new String[] { "0", "1", "2" },
                String.valueOf(Math.max(0, Math.min(2, mPrefs.getInt("perf_style", Dod3Activity.PERF_STYLE_BAR)))),
                val -> mPrefs.edit().putInt("perf_style", Integer.parseInt(val)).apply());

        addChoiceRow("Posición en pantalla", "Ubicación superior de la barra de rendimiento",
                new String[] { "Izquierda", "Centro", "Derecha" },
                new String[] { "0", "1", "2" },
                String.valueOf(Math.max(0, Math.min(2, mPrefs.getInt("perf_pos", Dod3Activity.PERF_POS_LEFT)))),
                val -> mPrefs.edit().putInt("perf_pos", Integer.parseInt(val)).apply());

        addChoiceRow("Tamaño de texto", "Escala tipográfica del medidor",
                new String[] { "Pequeño", "Normal", "Grande" },
                new String[] { "0", "1", "2" },
                String.valueOf(Math.max(0, Math.min(2, mPrefs.getInt("perf_size", 1)))),
                val -> mPrefs.edit().putInt("perf_size", Integer.parseInt(val)).apply());

        int curPerfBg = Math.max(0, Math.min(100, mPrefs.getInt("perf_bg_opacity", 65)));
        LinearLayout perfOpCard = makeRowCard();
        TextView perfOpLbl = new TextView(this);
        perfOpLbl.setText("Opacidad del fondo: " + curPerfBg + "%");
        perfOpLbl.setTextColor(COL_TEXT);
        perfOpLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        perfOpLbl.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        perfOpCard.addView(perfOpLbl);

        SeekBar perfOpBar = new SeekBar(this);
        perfOpBar.setMax(100);
        perfOpBar.setProgress(curPerfBg);
        perfOpBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int v = Math.max(0, Math.min(100, Math.round(p / 5f) * 5));
                perfOpLbl.setText("Opacidad del fondo: " + v + "%");
                mPrefs.edit().putInt("perf_bg_opacity", v).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        perfOpCard.addView(perfOpBar);
        mContentContainer.addView(perfOpCard);

        LinearLayout metricsCard = makeRowCard();
        TextView metricsTitle = new TextView(this);
        metricsTitle.setText("Métricas visibles");
        metricsTitle.setTextColor(COL_TEXT);
        metricsTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        metricsTitle.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        metricsCard.addView(metricsTitle);

        TextView metricsDesc = new TextView(this);
        metricsDesc.setText("Elementos incluidos en los modos Estándar y Detallado");
        metricsDesc.setTextColor(COL_MUTED);
        metricsDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        metricsDesc.setPadding(0, dp(1), 0, dp(6));
        metricsCard.addView(metricsDesc);

        LinearLayout metricsRow = new LinearLayout(this);
        metricsRow.setOrientation(LinearLayout.HORIZONTAL);
        metricsRow.addView(makePerfMetricCheck("ms",   "perf_show_frametime"),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        metricsRow.addView(makePerfMetricCheck("CPU",  "perf_show_cpu"),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        metricsRow.addView(makePerfMetricCheck("GPU",  "perf_show_gpu"),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        metricsRow.addView(makePerfMetricCheck("RAM",  "perf_show_ram"),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        metricsRow.addView(makePerfMetricCheck("Temp", "perf_show_temp"),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        metricsCard.addView(metricsRow);
        mContentContainer.addView(metricsCard);
    }

    private CheckBox makePerfMetricCheck(String label, final String prefKey) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextColor(COL_TEXT);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        cb.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= 21) {
            cb.setButtonTintList(ColorStateList.valueOf(COL_ACCENT2));
        }
        cb.setChecked(mPrefs.getBoolean(prefKey, true));
        cb.setOnCheckedChangeListener((b, c) -> mPrefs.edit().putBoolean(prefKey, c).apply());
        return cb;
    }

    // ==================== TAB 2: SYSTEM & CONTROLS ====================
    private String detectConnectedPadFamily() {
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

    private List<String> listConnectedControllers() {
        List<String> out = new ArrayList<>();
        for (int id : InputDevice.getDeviceIds()) {
            if (id < 0) continue;
            InputDevice dev = InputDevice.getDevice(id);
            if (dev == null) continue;
            int src = dev.getSources();
            boolean isPad = (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
            if (!isPad) continue;
            String name = dev.getName() != null ? dev.getName() : "Mando genérico";
            if (name.equals("DOD3 Virtual Pad")) continue;
            String vidPid = String.format("%04x:%04x", dev.getVendorId(), dev.getProductId());
            String lower = name.toLowerCase();
            String tag = (dev.getVendorId() == 0x045E || lower.contains("xbox"))
                    ? " · Mapeo Xbox One/Series activo" : "";
            out.add(name + " [" + vidPid + "]" + tag);
        }
        return out;
    }

    private String resolveControllerPreviewStyle() {
        String tpl = mPrefs.getString("pad_template", "auto");
        if (tpl.equals("ps3") || tpl.equals("ps4") || tpl.equals("ps5")
                || tpl.equals("xbox") || tpl.equals("switch")) {
            return tpl;
        }
        String prompts = mIni.get("DOD3_PROMPTS", "").trim().toLowerCase();
        if (prompts.equals("ps3") || prompts.equals("ps4") || prompts.equals("ps5")
                || prompts.equals("xbox") || prompts.equals("switch")) {
            return prompts;
        }
        return detectConnectedPadFamily();
    }

    private void updateControllerPreview() {
        if (mCtrlPreview == null) return;
        String style = resolveControllerPreviewStyle();
        mCtrlPreview.setStyle(style);
        if (mCtrlPreviewTitle != null) {
            mCtrlPreviewTitle.setText("Plantilla y Mapa de Controles — "
                    + ControllerPreviewView.friendlyStyleName(style));
        }
    }

    private void buildSystemTab() {
        addSectionHeader("Sistema y Controles", "Mando Bluetooth/USB, plantillas visuales, idioma y motor Vulkan");

        // Connected hardware controllers card
        LinearLayout hwCard = makeRowCard();
        TextView hwTitle = new TextView(this);
        hwTitle.setText("Mando físico (Bluetooth / USB)");
        hwTitle.setTextColor(COL_TEXT);
        hwTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hwTitle.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        hwTitle.setPadding(0, 0, 0, dp(4));
        hwCard.addView(hwTitle);

        List<String> pads = listConnectedControllers();
        if (pads.isEmpty()) {
            addStatusLine(hwCard, false, "Sin mando físico conectado",
                    "Compatible con Xbox One/Series, DualShock 4, DualSense, Switch Pro y controles táctiles");
        } else {
            for (String p : pads) {
                addStatusLine(hwCard, true, "Mando detectado (Jugador 1)", p);
            }
        }
        mContentContainer.addView(hwCard);

        addChoiceRow("Iconos de botones en pantalla", "Estilo de botones mostrados en HUD y tutoriales",
                new String[] { "Auto", "PS3", "PS4", "PS5", "Xbox", "Switch" },
                new String[] { "", "ps3", "ps4", "ps5", "xbox", "switch" },
                mIni.get("DOD3_PROMPTS", ""),
                val -> { mIni.set("DOD3_PROMPTS", val); updateControllerPreview(); });

        addChoiceRow("Plantilla de control táctil", "Silueta y disposición del overlay táctil (simétrica PS o asimétrica Xbox/Switch)",
                new String[] { "Seguir iconos", "PS3", "PS4", "PS5", "Xbox", "Switch" },
                new String[] { "auto", "ps3", "ps4", "ps5", "xbox", "switch" },
                mPrefs.getString("pad_template", "auto"),
                val -> { mPrefs.edit().putString("pad_template", val).apply(); updateControllerPreview(); });

        // Visual controller template & button mapping preview card
        LinearLayout prevCard = makeRowCard();
        String initialStyle = resolveControllerPreviewStyle();
        mCtrlPreviewTitle = new TextView(this);
        mCtrlPreviewTitle.setText("Plantilla y Mapa de Controles — "
                + ControllerPreviewView.friendlyStyleName(initialStyle));
        mCtrlPreviewTitle.setTextColor(COL_TEXT);
        mCtrlPreviewTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mCtrlPreviewTitle.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        mCtrlPreviewTitle.setPadding(0, 0, 0, dp(6));
        prevCard.addView(mCtrlPreviewTitle);

        mCtrlPreview = new ControllerPreviewView(this);
        mCtrlPreview.setStyle(initialStyle);
        prevCard.addView(mCtrlPreview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)));
        mContentContainer.addView(prevCard);

        addSwitchRow("Mostrar controles táctiles al iniciar", "Se pueden ocultar en cualquier momento con el botón PAD",
                mPrefs.getBoolean("pad_visible", true),
                on -> mPrefs.edit().putBoolean("pad_visible", on).apply());

        int curAlpha = mPrefs.getInt("pad_opacity", 75);
        LinearLayout opRow = makeRowCard();
        TextView opLbl = new TextView(this);
        opLbl.setText("Opacidad de controles táctiles: " + curAlpha + "%");
        opLbl.setTextColor(COL_TEXT);
        opLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        opLbl.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        opRow.addView(opLbl);

        SeekBar sb = new SeekBar(this);
        sb.setMax(70); // 30..100
        sb.setProgress(Math.max(0, Math.min(70, curAlpha - 30)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int v = p + 30;
                opLbl.setText("Opacidad de controles táctiles: " + v + "%");
                mPrefs.edit().putInt("pad_opacity", v).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        opRow.addView(sb);
        mContentContainer.addView(opRow);

        addChoiceRow("Velocidad de cámara", "Consola mantiene el giro consistente a 30 o 60 FPS",
                new String[] { "Consola", "Original" },
                new String[] { "", "original" },
                mIni.get("DOD3_CAMERA_SPEED", ""),
                val -> mIni.set("DOD3_CAMERA_SPEED", val));

        addChoiceRow("Idioma base del disco", "Textos y subtítulos base (la traducción opcional al Español se gestiona en Mods)",
                new String[] { "Inglés", "Francés" },
                new String[] { "", "fr" },
                mIni.get("DOD3_LANGUAGE", ""),
                val -> mIni.set("DOD3_LANGUAGE", val));

        addSwitchRow("Saltar intros y logos", "Entra directo a la pantalla de título",
                mIni.getBool("DOD3_SKIP_INTRO", true),
                on -> mIni.set("DOD3_SKIP_INTRO", on ? "1" : "0"));

        addSwitchRow("Notificaciones de trofeos", "Muestra avisos en la esquina superior derecha al ganar un trofeo",
                mIni.getBool("DOD3_TROPHY_POPUPS", true),
                on -> mIni.set("DOD3_TROPHY_POPUPS", on ? "" : "0"));

        addSwitchRow("Omitir Occlusion Queries (Adreno)", "Elimina stalls de sincronización GPU en móviles (recomendado)",
                mIni.getBool("RSX_SKIP_OCCLUSION", true),
                on -> mIni.set("RSX_SKIP_OCCLUSION", on ? "1" : "0"));

        LinearLayout cacheCard = makeRowCard();
        TextView t = new TextView(this);
        t.setText("Caché de shaders SPIR-V y pipelines Vulkan");
        t.setTextColor(COL_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        cacheCard.addView(t);

        TextView d = new TextView(this);
        d.setText("Los shaders SPIR-V optimizados y los pipelines Vulkan compilados se guardan en disco para acelerar los arranques.");
        d.setTextColor(COL_MUTED);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        d.setPadding(0, dp(2), 0, dp(8));
        cacheCard.addView(d);

        TextView btnClear = makeActionButton("Limpiar caché SPIR-V / Vulkan");
        btnClear.setOnClickListener(v -> {
            File sc = new File(mBaseDir, "cache/spirv");
            int removed = 0;
            if (sc.isDirectory() && sc.listFiles() != null) {
                for (File f : sc.listFiles()) if (f.delete()) removed++;
            }
            File vkCache = new File(mBaseDir, "cache/vk_pipeline.bin");
            if (vkCache.isFile() && vkCache.delete()) removed++;
            File vkTmp = new File(mBaseDir, "cache/vk_pipeline.bin.tmp");
            if (vkTmp.isFile()) vkTmp.delete();
            refreshStatusChips();
            Toast.makeText(this, "Caché limpiada (" + removed + " archivos)", Toast.LENGTH_SHORT).show();
        });
        cacheCard.addView(btnClear);
        mContentContainer.addView(cacheCard);
    }

    // ==================== TAB 2: MODS (OPTIONAL) ====================
    private void buildModsTab() {
        addSectionHeader("Mods Opcionales", "Gestión, activación e instalación de mods (.pkg / .zip) como la Traducción al Español");
        if (!ensurePermissionCard()) return;

        ArchiveScan s = getScan();

        // Install custom mod button card
        LinearLayout topCard = makeRowCard();
        topCard.setOrientation(LinearLayout.HORIZONTAL);
        topCard.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout topLeft = new LinearLayout(this);
        topLeft.setOrientation(LinearLayout.VERTICAL);
        TextView tt = new TextView(this);
        tt.setText("Carpeta de mods: /sdcard/Drakengard3/mods/");
        tt.setTextColor(COL_TEXT);
        tt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tt.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        topLeft.addView(tt);

        TextView td = new TextView(this);
        td.setText("Cada archivo .pkg de depuración o .zip de cinemáticas en mods/ se monta al vuelo sin descomprimir.");
        td.setTextColor(COL_MUTED);
        td.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        topLeft.addView(td);
        topCard.addView(topLeft, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnAddMod = makeAccentButton("＋ Instalar mod (.pkg / .zip)");
        btnAddMod.setOnClickListener(v -> openFilePicker(REQ_PICK_MOD));
        topCard.addView(btnAddMod);
        mContentContainer.addView(topCard);

        if (s.mods.isEmpty()) {
            LinearLayout empty = makeRowCard();
            TextView tv = new TextView(this);
            tv.setText("No hay mods instalados.\nDisponible el botón \"＋ Instalar mod\" o copiar archivos .pkg/.zip en /sdcard/Drakengard3/mods/ (como la traducción opcional al Español).");
            tv.setTextColor(COL_MUTED);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            empty.addView(tv);
            mContentContainer.addView(empty);
            return;
        }

        for (ModEntry m : s.mods) {
            mContentContainer.addView(makeModCard(m));
        }
    }

    private LinearLayout makeModCard(final ModEntry m) {
        LinearLayout card = makeRowCard();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        TextView tv = new TextView(this);
        tv.setText(m.label);
        tv.setTextColor(COL_TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        left.addView(tv);

        TextView td = new TextView(this);
        td.setText(m.file.getName() + "  ·  " + formatBytes(m.file.length()));
        td.setTextColor(COL_MUTED);
        td.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        left.addView(td);

        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        llp.rightMargin = dp(8);
        card.addView(left, llp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        final TextView bOff = new TextView(this);
        final TextView bOn  = new TextView(this);
        configureTogglePill(bOff, "Off", !m.enabled);
        configureTogglePill(bOn, "On", m.enabled);

        LinearLayout.LayoutParams pOffLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pOffLp.rightMargin = dp(4);
        LinearLayout.LayoutParams pOnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pOnLp.rightMargin = dp(8);

        bOff.setOnClickListener(v -> {
            m.enabled = false;
            setModEnabled(m.file, false);
            stylePill(bOff, true);
            stylePill(bOn, false);
            refreshStatusChips();
        });
        bOn.setOnClickListener(v -> {
            m.enabled = true;
            setModEnabled(m.file, true);
            stylePill(bOff, false);
            stylePill(bOn, true);
            refreshStatusChips();
        });
        actions.addView(bOff, pOffLp);
        actions.addView(bOn, pOnLp);

        TextView bDel = new TextView(this);
        bDel.setText("Eliminar");
        bDel.setTextColor(Color.rgb(255, 138, 128));
        bDel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        bDel.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        bDel.setPadding(dp(10), dp(6), dp(10), dp(6));
        bDel.setBackground(makeRounded(Color.argb(120, 75, 25, 30), dp(7), Color.argb(110, 229, 57, 65)));
        bDel.setOnClickListener(v -> confirmDeleteMod(m));
        actions.addView(bDel);

        card.addView(actions);
        return card;
    }

    private void confirmDeleteMod(final ModEntry m) {
        new AlertDialog.Builder(this)
                .setTitle("Eliminar mod")
                .setMessage("¿Eliminar el archivo \"" + m.file.getName() + "\" de /sdcard/Drakengard3/mods/?")
                .setPositiveButton("Eliminar", (d, w) -> {
                    File flag = new File(mBaseDir, "cache/mods_off/" + m.file.getName() + ".off");
                    if (flag.exists()) flag.delete();
                    if (m.file.delete()) {
                        syncSpanishMenuLanguage();
                        refreshStatusChips();
                        selectTab(3);
                        Toast.makeText(this, "Mod eliminado", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "No se pudo eliminar " + m.file.getName(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ==================== TAB 3: GAME FILES & DLCs ====================
    private void buildFilesTab() {
        addSectionHeader("Archivos del Juego", "Requisitos del proyecto (GitHub / Launcher PC): lectura directa sin descomprimir");
        if (!ensurePermissionCard()) return;

        ArchiveScan s = getScan();

        // Guide + Folder overview card
        LinearLayout guideCard = makeRowCard();
        guideCard.setOrientation(LinearLayout.HORIZONTAL);
        guideCard.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout gLeft = new LinearLayout(this);
        gLeft.setOrientation(LinearLayout.VERTICAL);
        TextView gt = new TextView(this);
        gt.setText("Guía de Instalación y Rutas");
        gt.setTextColor(COL_TEXT);
        gt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        gt.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        gLeft.addView(gt);
        TextView gs = new TextView(this);
        gs.setText("Asistente interactivo con las carpetas exactas para ISO, 1.01, DLCs y Mods.");
        gs.setTextColor(COL_MUTED);
        gs.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        gLeft.addView(gs);
        guideCard.addView(gLeft, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView btnGuide = makeActionButton("Guía de instalación");
        btnGuide.setOnClickListener(v -> showSetupGuide(true));
        guideCard.addView(btnGuide);
        mContentContainer.addView(guideCard);

        if (!s.misplacedNotes.isEmpty()) {
            LinearLayout warnCard = makeRowCard();
            warnCard.setBackground(makeRounded(Color.argb(170, 58, 38, 12), dp(10), COL_WARN));
            TextView wt = new TextView(this);
            wt.setText("Archivos fuera de su ruta correspondiente");
            wt.setTextColor(COL_WARN);
            wt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            wt.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
            warnCard.addView(wt);
            int limit = Math.min(6, s.misplacedNotes.size());
            for (int i = 0; i < limit; i++) {
                addInfoLine(warnCard, "•", s.misplacedNotes.get(i));
            }
            mContentContainer.addView(warnCard);
        }

        // 1. Required files section
        LinearLayout reqCard = makeRowCard();
        LinearLayout reqHdr = new LinearLayout(this);
        reqHdr.setOrientation(LinearLayout.HORIZONTAL);
        reqHdr.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout reqLeft = new LinearLayout(this);
        reqLeft.setOrientation(LinearLayout.VERTICAL);
        TextView rt = new TextView(this);
        rt.setText("Archivos Requeridos (BLUS31197)");
        rt.setTextColor(COL_TEXT);
        rt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        rt.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        reqLeft.addView(rt);

        TextView rs = new TextView(this);
        rs.setText("Obligatorios para arrancar según README de dod3-recomp (región USA).");
        rs.setTextColor(COL_MUTED);
        rs.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        reqLeft.addView(rs);
        reqHdr.addView(reqLeft, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnAddBase = makeAccentButton("＋ Añadir ISO / 1.01");
        btnAddBase.setOnClickListener(v -> openFilePicker(REQ_PICK_BASE));
        reqHdr.addView(btnAddBase);
        reqCard.addView(reqHdr);

        addStatusLine(reqCard, s.isoFile != null,
                "Disco USA (.iso desencriptada BLUS31197)",
                s.isoFile != null ? (s.isoFile.getName() + " (" + formatBytes(s.isoFile.length()) + ")")
                                  : "Falta — copiar el archivo .iso en /sdcard/Drakengard3/");

        boolean updOk = (s.updatePkg != null || s.hasCachedEboot);
        addStatusLine(reqCard, updOk,
                "Actualización oficial 1.01 (.pkg)",
                s.updatePkg != null ? (s.updatePkg.getName() + " (" + formatBytes(s.updatePkg.length()) + ")")
                                    : (s.hasCachedEboot ? "EBOOT_101.ELF en caché (recomendado tener también el .pkg 1.01)"
                                                        : "Falta UP0082-BLUS31197_00-DOD3PATCH0000000-A0101-V0101-PE.pkg"));

        addStatusLine(reqCard, s.hasCachedEboot,
                "Ejecutable descifrado (caché automática)",
                s.hasCachedEboot ? ("EBOOT_101.ELF (" + formatBytes(new File(mBaseDir, "cache/EBOOT_101.ELF").length()) + ")")
                                 : "Se extraerá automáticamente al primer inicio desde el .pkg 1.01");

        mContentContainer.addView(reqCard);

        // 2. Optional Official DLCs section
        LinearLayout dlcCard = makeRowCard();
        LinearLayout dlcHdr = new LinearLayout(this);
        dlcHdr.setOrientation(LinearLayout.HORIZONTAL);
        dlcHdr.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout dlcLeft = new LinearLayout(this);
        dlcLeft.setOrientation(LinearLayout.VERTICAL);
        TextView dt = new TextView(this);
        dt.setText("DLCs Oficiales Opcionales (UP0082-NPUB31251)");
        dt.setTextColor(COL_TEXT);
        dt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        dt.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        dlcLeft.addView(dt);

        TextView ds = new TextView(this);
        ds.setText("Detectados en /sdcard/Drakengard3/dlc/: " + s.dlcs.size() + " / 20 packs (no requieren licencias RAP/EDAT)");
        ds.setTextColor(s.dlcs.isEmpty() ? COL_MUTED : COL_OK);
        ds.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        ds.setPadding(0, dp(2), 0, dp(6));
        dlcLeft.addView(ds);
        dlcHdr.addView(dlcLeft, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnAddDlc = makeActionButton("＋ Añadir DLC (.pkg)");
        btnAddDlc.setOnClickListener(v -> openFilePicker(REQ_PICK_DLC));
        dlcHdr.addView(btnAddDlc);
        dlcCard.addView(dlcHdr);

        if (s.dlcs.isEmpty()) {
            addInfoLine(dlcCard, "Estado", "Ningún DLC añadido todavía (prólogos, voces japonesas, trajes y música son opcionales)");
        } else {
            for (DlcEntry d : s.dlcs) {
                addStatusLine(dlcCard, true, d.name, formatBytes(d.file.length()) + "  ·  " + d.file.getName());
            }
        }
        mContentContainer.addView(dlcCard);

        // 3. Local folders card
        LinearLayout dirsCard = makeRowCard();
        addInfoLine(dirsCard, "Carpeta principal", mBaseDir.getAbsolutePath());
        addInfoLine(dirsCard, "Partidas guardadas", new File(mBaseDir, "gamedata").getAbsolutePath());
        addInfoLine(dirsCard, "Caché (EBOOT / SPIR-V / PSO)", new File(mBaseDir, "cache").getAbsolutePath());
        addInfoLine(dirsCard, "Registros (.txt)", new File(mBaseDir, "logs").getAbsolutePath());
        mContentContainer.addView(dirsCard);

        // 4. Crash logs & diagnostic export card
        LinearLayout logCard = makeRowCard();
        TextView lt = new TextView(this);
        lt.setText("Registros y diagnóstico");
        lt.setTextColor(COL_TEXT);
        lt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        lt.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        logCard.addView(lt);

        String latestReport = CrashReporter.readLatestReport(this);
        boolean hasReport = latestReport != null && !latestReport.trim().isEmpty();
        TextView ls = new TextView(this);
        ls.setText(hasReport
                ? "Último informe disponible para copiar o exportar a /sdcard/Drakengard3/logs/."
                : "Sin cierres inesperados registrados. Es posible exportar un diagnóstico actual en .txt.");
        ls.setTextColor(COL_MUTED);
        ls.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        ls.setPadding(0, dp(2), 0, dp(10));
        logCard.addView(ls);

        LinearLayout logBtns = new LinearLayout(this);
        logBtns.setOrientation(LinearLayout.HORIZONTAL);
        logBtns.setGravity(Gravity.CENTER_VERTICAL);

        TextView btnViewCrash = makeActionButton("Ver último error");
        ((LinearLayout.LayoutParams) btnViewCrash.getLayoutParams()).rightMargin = dp(8);
        btnViewCrash.setOnClickListener(v -> {
            String rep = CrashReporter.readLatestReport(this);
            if (rep == null || rep.trim().isEmpty()) {
                Toast.makeText(this, "No hay cierres inesperados registrados.", Toast.LENGTH_SHORT).show();
            } else {
                showCrashReportDialog(rep, 0L, false);
            }
        });
        logBtns.addView(btnViewCrash);

        TextView btnSaveDiag = makeAccentButton("Guardar registro (.txt)");
        btnSaveDiag.setOnClickListener(v -> {
            Toast.makeText(this, "Recopilando registro de diagnóstico...", Toast.LENGTH_SHORT).show();
            mBgExec.execute(() -> {
                String snap = CrashReporter.buildDiagnosticSnapshot(this, mBaseDir);
                File saved = CrashReporter.saveReportToTxt(this, mBaseDir, snap, "diag");
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (saved != null) {
                        Toast.makeText(this, "Guardado en:\n" + saved.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "No se pudo guardar el archivo .txt.", Toast.LENGTH_LONG).show();
                    }
                });
            });
        });
        logBtns.addView(btnSaveDiag);
        logCard.addView(logBtns);
        mContentContainer.addView(logCard);
    }

    private boolean ensurePermissionCard() {
        if (hasStoragePermission()) return true;
        LinearLayout permCard = makeRowCard();
        TextView pt = new TextView(this);
        pt.setText("Permiso de almacenamiento requerido");
        pt.setTextColor(COL_WARN);
        pt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        pt.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        permCard.addView(pt);

        TextView pd = new TextView(this);
        pd.setText("Se requiere permiso de acceso a todos los archivos en /sdcard/Drakengard3/.");
        pd.setTextColor(COL_MUTED);
        pd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        pd.setPadding(0, dp(4), 0, dp(10));
        permCard.addView(pd);

        TextView btnPerm = makeActionButton("Solicitar permiso de archivos");
        btnPerm.setOnClickListener(v -> requestStoragePermission());
        permCard.addView(btnPerm);
        mContentContainer.addView(permCard);
        return false;
    }

    // ==================== TAB 4: CHEATS ====================
    private void buildCheatsTab() {
        addSectionHeader("Trucos (Cheats)", "Mismas opciones del launcher de PC (aplican al iniciar)");

        addSwitchRow("Salud infinita", "Mantiene llena la salud de Zero y del dragón",
                mIni.getBool("DOD3_CHEAT_HEALTH", false),
                on -> mIni.set("DOD3_CHEAT_HEALTH", on ? "1" : ""));

        addSwitchRow("Medidor Intoner lleno", "Llena la barra de sangre Intoner y la mantiene al máximo",
                mIni.getBool("DOD3_CHEAT_INTONER", false),
                on -> mIni.set("DOD3_CHEAT_INTONER", on ? "1" : ""));

        addSwitchRow("Objetos infinitos", "Los objetos consumibles no se gastan al usarlos",
                mIni.getBool("DOD3_CHEAT_ITEMS", false),
                on -> mIni.set("DOD3_CHEAT_ITEMS", on ? "1" : ""));

        addSwitchRow("Congelar temporizadores", "Los límites de tiempo en misiones no disminuyen",
                mIni.getBool("DOD3_CHEAT_TIME", false),
                on -> mIni.set("DOD3_CHEAT_TIME", on ? "1" : ""));

        addSwitchRow("Auto-Play canción final (Rama D)", "Pulsa cada nota a tiempo automáticamente en la batalla final",
                mIni.getBool("DOD3_SONG_AUTOPLAY", false),
                on -> mIni.set("DOD3_SONG_AUTOPLAY", on ? "1" : ""));
    }

    // ==================== TAB 6: INFORMATION & CREDITS ====================
    private void buildInfoTab() {
        String ver = getAppVersion();
        addSectionHeader("Información del Proyecto", "PDG3Android v" + ver + " · Proyect Drakengard 3 Android");

        // 1. Project Showcase Banner & General App Info Card (above Zero)
        LinearLayout projectCard = new LinearLayout(this);
        projectCard.setOrientation(LinearLayout.VERTICAL);
        projectCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable projectBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] { Color.argb(215, 36, 16, 25), Color.argb(195, 18, 18, 28) });
        projectBg.setCornerRadius(dp(12));
        projectBg.setStroke(dp(1), Color.argb(160, 229, 57, 65));
        projectCard.setBackground(projectBg);
        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pcLp.bottomMargin = dp(10);
        projectCard.setLayoutParams(pcLp);

        FrameLayout bannerBox = new FrameLayout(this);
        bannerBox.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable bannerBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { Color.argb(242, 246, 242, 244), Color.argb(232, 228, 218, 224) });
        bannerBg.setCornerRadius(dp(10));
        bannerBg.setStroke(dp(1), Color.argb(130, 229, 57, 65));
        bannerBox.setBackground(bannerBg);
        LinearLayout.LayoutParams bbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(130));
        bbLp.bottomMargin = dp(12);

        ImageView ivProjectLogo = new ImageView(this);
        ivProjectLogo.setImageResource(R.drawable.pdg3_project_logo);
        ivProjectLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bannerBox.addView(ivProjectLogo, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        projectCard.addView(bannerBox, bbLp);

        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView appTitle = new TextView(this);
        appTitle.setText("PDG3Android");
        appTitle.setTextColor(COL_TEXT);
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        appTitle.setTypeface(Typeface.SERIF, Typeface.BOLD);
        LinearLayout.LayoutParams atLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        atLp.rightMargin = dp(8);
        titleBar.addView(appTitle, atLp);

        TextView verBadge = new TextView(this);
        verBadge.setText("v" + ver);
        verBadge.setTextColor(Color.WHITE);
        verBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        verBadge.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        verBadge.setPadding(dp(8), dp(2), dp(8), dp(2));
        verBadge.setBackground(makeRounded(COL_ACCENT, dp(6), COL_ACCENT2));
        titleBar.addView(verBadge);
        projectCard.addView(titleBar);

        TextView projSub = new TextView(this);
        projSub.setText("Proyect Drakengard 3 Android");
        projSub.setTextColor(COL_ACCENT2);
        projSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        projSub.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        projSub.setPadding(0, dp(4), 0, 0);
        projectCard.addView(projSub);

        TextView creatorLine = new TextView(this);
        creatorLine.setText("xGowther2102 · Creador del Proyecto");
        creatorLine.setTextColor(Color.rgb(242, 220, 224));
        creatorLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        creatorLine.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        creatorLine.setPadding(0, dp(2), 0, dp(8));
        projectCard.addView(creatorLine);

        TextView appDesc = new TextView(this);
        appDesc.setText("Adaptación nativa de Drakengard 3 para Android mediante recompilación estática a ARM64 y renderizado Vulkan nativo. Incluye lectura directa de ISO, actualización, DLCs y mods desde almacenamiento local, perfiles gráficos configurables, controles táctiles y compatibilidad con mandos externos.");
        appDesc.setTextColor(Color.rgb(220, 218, 226));
        appDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        appDesc.setLineSpacing(dp(2), 1.0f);
        appDesc.setPadding(0, 0, 0, dp(8));
        projectCard.addView(appDesc);

        LinearLayout infoBox = new LinearLayout(this);
        infoBox.setOrientation(LinearLayout.VERTICAL);
        infoBox.setPadding(dp(10), dp(8), dp(10), dp(8));
        infoBox.setBackground(makeRounded(Color.argb(115, 22, 20, 30), dp(8), COL_BORDER));
        LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ibLp.bottomMargin = dp(10);

        TextView metaTv = new TextView(this);
        metaTv.setText("• Proyecto: Proyect Drakengard 3 Android\n"
                + "• Creador: xGowther2102\n"
                + "• Versión: v" + ver + "\n"
                + "• Paquete: " + getPackageName() + "\n"
                + "• Motor y API: Recompilación estática ARM64 · Vulkan Nativo · SDL2");
        metaTv.setTextColor(Color.rgb(208, 206, 216));
        metaTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        metaTv.setLineSpacing(dp(2), 1.0f);
        infoBox.addView(metaTv);
        projectCard.addView(infoBox, ibLp);

        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] tags = { "Vulkan Nativo", "ARM64 Nativo", "ISO / PKG Directo" };
        for (int i = 0; i < tags.length; i++) {
            TextView ch = new TextView(this);
            ch.setText(tags[i]);
            ch.setTextColor(COL_TEXT);
            ch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            ch.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
            ch.setPadding(dp(8), dp(4), dp(8), dp(4));
            ch.setBackground(makeRounded(Color.argb(160, 40, 28, 36), dp(6), Color.argb(90, 229, 57, 65)));
            LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i < tags.length - 1) chLp.rightMargin = dp(6);
            chipRow.addView(ch, chLp);
        }
        projectCard.addView(chipRow);
        mContentContainer.addView(projectCard);

        // 2. Protagonist Showcase Card: Zero (below Project card)
        LinearLayout zeroCard = new LinearLayout(this);
        zeroCard.setOrientation(LinearLayout.HORIZONTAL);
        zeroCard.setGravity(Gravity.CENTER_VERTICAL);
        zeroCard.setPadding(dp(16), dp(14), dp(12), dp(14));
        GradientDrawable zeroBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] { Color.argb(210, 36, 16, 25), Color.argb(190, 20, 20, 30) });
        zeroBg.setCornerRadius(dp(12));
        zeroBg.setStroke(dp(1), Color.argb(150, 229, 57, 65));
        zeroCard.setBackground(zeroBg);
        LinearLayout.LayoutParams zcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        zcLp.bottomMargin = dp(10);
        zeroCard.setLayoutParams(zcLp);

        LinearLayout zLeft = new LinearLayout(this);
        zLeft.setOrientation(LinearLayout.VERTICAL);

        TextView heroRole = new TextView(this);
        heroRole.setText("PROTAGONISTA · ZERO");
        heroRole.setTextColor(COL_ACCENT2);
        heroRole.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        heroRole.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        zLeft.addView(heroRole);

        TextView heroName = new TextView(this);
        heroName.setText("Zero");
        heroName.setTextColor(COL_TEXT);
        heroName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        heroName.setTypeface(Typeface.SERIF, Typeface.BOLD);
        heroName.setPadding(0, dp(2), 0, 0);
        zLeft.addView(heroName);

        TextView heroSub = new TextView(this);
        heroSub.setText("Intoner · Portadora de la Flor Blanca");
        heroSub.setTextColor(COL_MUTED);
        heroSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        heroSub.setPadding(0, 0, 0, dp(6));
        zLeft.addView(heroSub);

        TextView zeroBio = new TextView(this);
        zeroBio.setText("Zero es la protagonista central de Drakengard 3: una Intoner marcada por una flor blanca en su ojo derecho, cuya travesía y combates junto al dragón Mikhail dan origen al conflicto de toda la historia. Esta adaptación preserva su campaña principal, prólogos DLC y voces originales de forma nativa en Android.");
        zeroBio.setTextColor(Color.rgb(220, 218, 226));
        zeroBio.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        zeroBio.setLineSpacing(dp(2), 1.0f);
        zLeft.addView(zeroBio);

        LinearLayout.LayoutParams zlLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        zlLp.rightMargin = dp(10);
        zeroCard.addView(zLeft, zlLp);

        FrameLayout artWrap = new FrameLayout(this);
        View halo = new View(this);
        GradientDrawable glow = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { Color.argb(75, 229, 57, 65), Color.argb(18, 198, 40, 48), Color.TRANSPARENT });
        glow.setShape(GradientDrawable.OVAL);
        halo.setBackground(glow);
        FrameLayout.LayoutParams haloLp = new FrameLayout.LayoutParams(dp(124), dp(124), Gravity.CENTER);
        artWrap.addView(halo, haloLp);

        ImageView ivZero = new ImageView(this);
        ivZero.setImageResource(R.drawable.zero_art);
        ivZero.setScaleType(ImageView.ScaleType.FIT_CENTER);
        artWrap.addView(ivZero, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        zeroCard.addView(artWrap, new LinearLayout.LayoutParams(dp(130), dp(188)));
        mContentContainer.addView(zeroCard);

        // 2. General architecture card
        LinearLayout genCard = makeRowCard();
        TextView gt = new TextView(this);
        gt.setText("Arquitectura y componentes generales");
        gt.setTextColor(COL_TEXT);
        gt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        gt.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        gt.setPadding(0, 0, 0, dp(4));
        genCard.addView(gt);

        addStatusLine(genCard, true, "Recompilación estática PPU / SPU a ARM64",
                "Traducción de ejecutables PS3 a C++ nativo ejecutado directamente sobre la CPU sin interpretación JIT");
        addStatusLine(genCard, true, "Renderizado Vulkan Nativo + SPIR-V + PSO Cache",
                "Compilación en caché persistente, caché de Descriptor Sets por cuadro y shaders de post-procesado");
        addStatusLine(genCard, true, "Sistema de archivos virtual directo (VFS)",
                "Lectura al vuelo desde ISO desencriptada, Update 1.01, DLCs .pkg y mods .pkg/.zip en /sdcard/Drakengard3/");
        addStatusLine(genCard, true, "Entrada híbrida SDL2 + plantillas táctiles",
                "Compatibilidad con mandos Bluetooth/USB mediante gamecontrollerdb.txt y superposición táctil adaptativa");
        mContentContainer.addView(genCard);

        // 3. Main repository & dependency repositories
        LinearLayout repoCard = makeRowCard();
        TextView rt = new TextView(this);
        rt.setText("Repositorios y créditos técnicos");
        rt.setTextColor(COL_TEXT);
        rt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        rt.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        rt.setPadding(0, 0, 0, dp(2));
        repoCard.addView(rt);

        TextView rs = new TextView(this);
        rs.setText("Proyecto principal de recompilación y dependencias de código abierto (pulsar para abrir enlace)");
        rs.setTextColor(COL_MUTED);
        rs.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        rs.setPadding(0, 0, 0, dp(8));
        repoCard.addView(rs);

        addRepoRow(repoCard, "dod3-recomp (Repositorio principal)",
                "Lifting estático de Drakengard 3, parches del motor Unreal Engine 3 y launcher base",
                "https://github.com/soyjxck/dod3-recomp", true);
        addRepoRow(repoCard, "ps3recomp (Fork principal del runtime)",
                "Implementación HLE de syscalls LV2, VFS virtual, audio y backend Vulkan/SPIR-V",
                "https://github.com/soyjxck/ps3recomp", false);
        addRepoRow(repoCard, "ps3recomp (Proyecto original)",
                "Arquitectura base del recompilador estático de PlayStation 3 (sp00nznet)",
                "https://github.com/sp00nznet/ps3recomp", false);
        addRepoRow(repoCard, "SDL2 & SDL_GameControllerDB",
                "Capa multimedia Android y mapeos comunitarios de mandos Xbox, PlayStation y Switch",
                "https://github.com/libsdl-org/SDL", false);
        addRepoRow(repoCard, "SDL_GameControllerDB",
                "Base de definiciones para controles DirectInput / Bluetooth en Android",
                "https://github.com/mdqinc/SDL_GameControllerDB", false);
        addRepoRow(repoCard, "glslang · SPIRV-Tools · SPIRV-Cross",
                "Traducción de shaders RSX/HLSL a bytecode SPIR-V para Vulkan",
                "https://github.com/KhronosGroup/glslang", false);
        addRepoRow(repoCard, "ps3libraries",
                "Referencia de librerías y utilidades del ecosistema homebrew PS3",
                "https://github.com/ps3dev/ps3libraries", false);
        addRepoRow(repoCard, "zlib & minimp3",
                "Descompresión nativa EdgeZlib y decodificación de pistas de audio MP3",
                "https://github.com/lieff/minimp3", false);

        mContentContainer.addView(repoCard);
    }

    private void addRepoRow(LinearLayout parent, String title, String desc, final String url, boolean primary) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setBackground(makeRounded(
                primary ? Color.argb(150, 56, 22, 30) : Color.argb(100, 26, 26, 36),
                dp(8),
                primary ? COL_ACCENT2 : COL_BORDER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        TextView tvT = new TextView(this);
        tvT.setText((primary ? "★  " : "•  ") + title);
        tvT.setTextColor(primary ? Color.WHITE : COL_TEXT);
        tvT.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tvT.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        left.addView(tvT);

        TextView tvD = new TextView(this);
        tvD.setText(desc);
        tvD.setTextColor(COL_MUTED);
        tvD.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        left.addView(tvD);

        TextView tvU = new TextView(this);
        tvU.setText(url);
        tvU.setTextColor(primary ? Color.rgb(255, 165, 172) : Color.rgb(138, 178, 245));
        tvU.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvU.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        tvU.setPadding(0, dp(2), 0, 0);
        left.addView(tvU);

        row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(this);
        badge.setText(primary ? "PRINCIPAL" : "ABRIR ↗");
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        badge.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        badge.setPadding(dp(8), dp(4), dp(8), dp(4));
        badge.setBackground(makeRounded(primary ? COL_ACCENT : Color.argb(145, 52, 52, 72), dp(6), COL_BORDER));
        row.addView(badge);

        row.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                Toast.makeText(this, url, Toast.LENGTH_SHORT).show();
            }
        });
        parent.addView(row);
    }

    // ==================== FILE PICKER ====================
    private void openFilePicker(int reqCode) {
        if (!hasStoragePermission()) {
            requestStoragePermission();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, reqCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final File destDir;
        if (requestCode == REQ_PICK_MOD) destDir = new File(mBaseDir, "mods");
        else if (requestCode == REQ_PICK_DLC) destDir = new File(mBaseDir, "dlc");
        else if (requestCode == REQ_PICK_BASE) destDir = mBaseDir;
        else return;

        final String fname = queryDisplayName(uri);
        final String lower = fname.toLowerCase();
        if (requestCode == REQ_PICK_BASE && !lower.endsWith(".iso") && !lower.endsWith(".pkg")) {
            Toast.makeText(this, "Para la base solo se admiten archivos .iso o .pkg (Update 1.01).", Toast.LENGTH_LONG).show();
            return;
        }
        if (requestCode == REQ_PICK_DLC && !lower.endsWith(".pkg")) {
            Toast.makeText(this, "Los DLCs oficiales deben estar en formato .pkg.", Toast.LENGTH_LONG).show();
            return;
        }
        if (requestCode == REQ_PICK_MOD && !lower.endsWith(".pkg") && !lower.endsWith(".zip")) {
            Toast.makeText(this, "Los mods solo se admiten en formato .pkg o .zip.", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "Copiando " + fname + " en " + destDir.getName() + "/...", Toast.LENGTH_LONG).show();

        mBgExec.execute(() -> {
            boolean ok = false;
            String errMsg = null;
            try {
                if (!destDir.exists()) destDir.mkdirs();
                File dst = new File(destDir, fname);
                File tmp = new File(destDir, fname + ".part");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    if (in != null) {
                        byte[] buf = new byte[256 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        ok = true;
                    }
                }
                if (ok && lower.endsWith(".pkg")) {
                    PkgMeta meta = readPkgMeta(tmp);
                    if (!meta.valid) {
                        ok = false;
                        errMsg = "El archivo seleccionado no es un paquete PS3 (.pkg) válido.";
                    }
                }
                if (ok) {
                    if (dst.exists()) dst.delete();
                    ok = tmp.renameTo(dst);
                } else if (tmp.exists()) {
                    tmp.delete();
                }
            } catch (Exception ignored) {
                ok = false;
            }
            final boolean doneOk = ok;
            final String failReason = errMsg;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (doneOk) {
                    if (requestCode == REQ_PICK_MOD) syncSpanishMenuLanguage();
                    mCachedScan = null;
                    refreshArchivesAsync(() -> {
                        if (mSetupOverlay != null && mSetupOverlay.getVisibility() == View.VISIBLE) {
                            populateSetupOverlay();
                        } else {
                            selectTab(mActiveTab);
                        }
                    });
                    Toast.makeText(this, "Instalado: " + fname, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, failReason != null ? failReason : ("Error al copiar " + fname), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private String queryDisplayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {}
        if (name == null || name.isEmpty()) {
            String p = uri.getLastPathSegment();
            name = (p != null && !p.isEmpty()) ? new File(p).getName() : "archivo.pkg";
        }
        return name.replaceAll("[/\\\\]", "_");
    }

    // ==================== UI HELPERS ====================
    private void addSectionHeader(String title, String sub) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(COL_TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        mContentContainer.addView(tv);

        TextView ts = new TextView(this);
        ts.setText(sub);
        ts.setTextColor(COL_MUTED);
        ts.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        ts.setPadding(0, dp(2), 0, dp(12));
        mContentContainer.addView(ts);
    }

    private LinearLayout makeRowCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackground(makeRounded(Color.argb(130, 28, 28, 38), dp(10), COL_BORDER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);
        return card;
    }

    interface StrCallback { void onSelect(String val); }
    interface BoolCallback { void onToggle(boolean val); }

    private void addChoiceRow(String title, String desc, String[] labels, String[] values, String current, StrCallback cb) {
        LinearLayout card = makeRowCard();
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(COL_TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        card.addView(tv);

        TextView td = new TextView(this);
        td.setText(desc);
        td.setTextColor(COL_MUTED);
        td.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        td.setPadding(0, dp(1), 0, dp(8));
        card.addView(td);

        LinearLayout pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] btns = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView p = new TextView(this);
            boolean sel = values[i].equalsIgnoreCase(current);
            configureTogglePill(p, labels[i], sel);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            plp.rightMargin = dp(6);
            p.setOnClickListener(v -> {
                for (int k = 0; k < btns.length; k++) stylePill(btns[k], k == idx);
                cb.onSelect(values[idx]);
            });
            pills.addView(p, plp);
            btns[i] = p;
        }
        card.addView(pills);
        mContentContainer.addView(card);
    }

    private void configureTogglePill(TextView p, String text, boolean active) {
        p.setText(text);
        p.setGravity(Gravity.CENTER);
        p.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        p.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        p.setPadding(dp(12), dp(6), dp(12), dp(6));
        stylePill(p, active);
    }

    private void stylePill(TextView p, boolean active) {
        p.setTextColor(active ? Color.WHITE : COL_MUTED);
        p.setBackground(makeRounded(
                active ? COL_ACCENT : Color.argb(145, 44, 44, 60),
                dp(7),
                active ? COL_ACCENT2 : COL_BORDER));
    }

    private void addSwitchRow(String title, String desc, boolean initial, BoolCallback cb) {
        LinearLayout card = makeRowCard();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(COL_TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        left.addView(tv);

        TextView td = new TextView(this);
        td.setText(desc);
        td.setTextColor(COL_MUTED);
        td.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        left.addView(td);

        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        llp.rightMargin = dp(10);
        card.addView(left, llp);

        LinearLayout toggleBox = new LinearLayout(this);
        toggleBox.setOrientation(LinearLayout.HORIZONTAL);
        final TextView bOff = new TextView(this);
        final TextView bOn  = new TextView(this);
        configureTogglePill(bOff, "Off", !initial);
        configureTogglePill(bOn, "On", initial);

        LinearLayout.LayoutParams pOffLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pOffLp.rightMargin = dp(6);

        bOff.setOnClickListener(v -> {
            stylePill(bOff, true);
            stylePill(bOn, false);
            cb.onToggle(false);
        });
        bOn.setOnClickListener(v -> {
            stylePill(bOff, false);
            stylePill(bOn, true);
            cb.onToggle(true);
        });
        toggleBox.addView(bOff, pOffLp);
        toggleBox.addView(bOn);
        card.addView(toggleBox);
        mContentContainer.addView(card);
    }

    private void addStatusLine(LinearLayout parent, boolean ok, String title, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView icon = new TextView(this);
        icon.setText(ok ? "✓" : "✗");
        icon.setTextColor(ok ? COL_OK : COL_WARN);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        icon.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(20), LinearLayout.LayoutParams.WRAP_CONTENT);
        row.addView(icon, ilp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(COL_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        col.addView(t);

        TextView d = new TextView(this);
        d.setText(detail);
        d.setTextColor(COL_MUTED);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        col.addView(d);

        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row);
    }

    private void addInfoLine(LinearLayout parent, String k, String v) {
        TextView tv = new TextView(this);
        tv.setText(k + ":  " + v);
        tv.setTextColor(COL_MUTED);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setPadding(0, dp(3), 0, dp(3));
        parent.addView(tv);
    }

    private TextView makeActionButton(String label) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setBackground(makeRounded(Color.argb(180, 70, 70, 92), dp(8), COL_BORDER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView makeAccentButton(String label) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setBackground(makeRounded(COL_ACCENT, dp(8), COL_ACCENT2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable makeRounded(int fill, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (stroke != Color.TRANSPARENT) d.setStroke(dp(1), stroke);
        return d;
    }

    private String formatBytes(long b) {
        if (b >= (1024L * 1024L * 1024L)) {
            return String.format("%.2f GB", b / (1024.0 * 1024.0 * 1024.0));
        }
        if (b >= (1024L * 1024L)) {
            return String.format("%.1f MB", b / (1024.0 * 1024.0));
        }
        return String.format("%d KB", Math.max(1L, b / 1024L));
    }

    private void checkPendingCrashAsync() {
        mBgExec.execute(() -> {
            CrashReporter.PendingCrash pending = CrashReporter.checkPendingCrash(this, mBaseDir);
            if (pending == null || pending.report == null || pending.report.trim().isEmpty()) return;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || mCrashDialogShowing) return;
                showCrashReportDialog(pending.report, pending.exitTs, true);
            });
        });
    }

    private void showCrashReportDialog(final String report, final long exitTs, final boolean requireAck) {
        if (mCrashDialogShowing || report == null || report.trim().isEmpty()) return;
        mCrashDialogShowing = true;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(12));
        box.setBackground(makeRounded(Color.rgb(18, 18, 25), dp(12), COL_ACCENT2));

        TextView title = new TextView(this);
        title.setText(requireAck ? "Cierre inesperado detectado" : "Informe de error y diagnóstico");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText(requireAck
                ? "La ejecución anterior finalizó de forma repentina. Es posible copiar el informe o guardarlo en .txt."
                : "Detalle registrado en la última incidencia capturada.");
        sub.setTextColor(COL_MUTED);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setPadding(0, dp(3), 0, dp(10));
        box.addView(sub);

        ScrollView sc = new ScrollView(this);
        sc.setBackground(makeRounded(Color.rgb(10, 10, 14), dp(8), COL_BORDER));
        sc.setPadding(dp(10), dp(8), dp(10), dp(8));

        TextView logTv = new TextView(this);
        logTv.setText(report);
        logTv.setTextColor(Color.rgb(225, 223, 232));
        logTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        logTv.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        logTv.setTextIsSelectable(true);
        sc.addView(logTv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        box.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams btnBarLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnBarLp.topMargin = dp(12);

        TextView btnCopy = makeActionButton("Copiar error");
        ((LinearLayout.LayoutParams) btnCopy.getLayoutParams()).rightMargin = dp(8);
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("PDG3Android Error", report));
                Toast.makeText(this, "Error copiado al portapapeles.", Toast.LENGTH_SHORT).show();
            }
        });
        btns.addView(btnCopy);

        TextView btnSave = makeActionButton("Guardar en .txt");
        ((LinearLayout.LayoutParams) btnSave.getLayoutParams()).rightMargin = dp(8);
        btnSave.setOnClickListener(v -> mBgExec.execute(() -> {
            File saved = CrashReporter.saveReportToTxt(this, mBaseDir, report, "crash");
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (saved != null) {
                    Toast.makeText(this, "Guardado en:\n" + saved.getAbsolutePath(), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "No se pudo guardar el informe .txt.", Toast.LENGTH_LONG).show();
                }
            });
        }));
        btns.addView(btnSave);

        TextView btnClose = makeAccentButton("Cerrar");
        btns.addView(btnClose);
        box.addView(btns, btnBarLp);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(box)
                .create();
        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> {
            mCrashDialogShowing = false;
            if (requireAck) CrashReporter.acknowledgeCrash(this, exitTs);
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(dp(520), WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private void launchGame() {
        if (!hasStoragePermission()) {
            Toast.makeText(this, "Se requiere permiso de acceso a /sdcard/Drakengard3/", Toast.LENGTH_LONG).show();
            requestStoragePermission();
            return;
        }
        mCachedScan = scanArchives();
        ArchiveScan s = mCachedScan;
        if (!isMandatoryReady(s)) {
            Toast.makeText(this, "Copiar el archivo .iso de Drakengard 3 y el .pkg de Update 1.01 en:\n" + mBaseDir.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
            showSetupGuide(false);
            return;
        }
        CrashReporter.markGameSessionStart(this);
        Intent i = new Intent(this, Dod3Activity.class);
        i.putExtra("pad_template", mPrefs.getString("pad_template", "auto"));
        i.putExtra("pad_visible", mPrefs.getBoolean("pad_visible", true));
        i.putExtra("pad_opacity", mPrefs.getInt("pad_opacity", 75));
        i.putExtra("fps_hud", mPrefs.getBoolean("fps_hud", true));
        i.putExtra("perf_style", mPrefs.getInt("perf_style", Dod3Activity.PERF_STYLE_BAR));
        i.putExtra("perf_pos", mPrefs.getInt("perf_pos", Dod3Activity.PERF_POS_LEFT));
        i.putExtra("perf_size", mPrefs.getInt("perf_size", 1));
        i.putExtra("perf_bg_opacity", mPrefs.getInt("perf_bg_opacity", 65));
        i.putExtra("perf_show_frametime", mPrefs.getBoolean("perf_show_frametime", true));
        i.putExtra("perf_show_cpu", mPrefs.getBoolean("perf_show_cpu", true));
        i.putExtra("perf_show_gpu", mPrefs.getBoolean("perf_show_gpu", true));
        i.putExtra("perf_show_ram", mPrefs.getBoolean("perf_show_ram", true));
        i.putExtra("perf_show_temp", mPrefs.getBoolean("perf_show_temp", true));
        startActivity(i);
    }
}
