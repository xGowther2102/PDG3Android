package com.pdg3android.xgowther2102;

import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Environment;
import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Captura cierres inesperados de Java y del proceso nativo (:game),
 * permitiendo copiar el informe al portapapeles o guardarlo en .txt.
 */
public final class CrashReporter {

    private static final String PREFS_NAME = "dod3_android";
    private static final String PREF_LAST_ACK_TS = "last_ack_exit_ts";

    private static final String FILE_PENDING_CRASH = "pending_crash.txt";
    private static final String FILE_LATEST_REPORT = "latest_crash_report.txt";
    private static final String FILE_SESSION_MARKER = "game_session.marker";
    private static final String FILE_CLEAN_EXITS = "clean_exits.log";

    private static volatile boolean sInstalled = false;

    public static final class PendingCrash {
        public final String report;
        public final long exitTs;
        public final boolean fromJavaFile;

        PendingCrash(String report, long exitTs, boolean fromJavaFile) {
            this.report = report;
            this.exitTs = exitTs;
            this.fromJavaFile = fromJavaFile;
        }
    }

    private CrashReporter() {}

    public static void install(Context context) {
        if (sInstalled || context == null) return;
        sInstalled = true;
        final Context appCtx = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                String report = buildUncaughtJavaReport(appCtx, thread, ex);
                writeInternalFile(appCtx, FILE_PENDING_CRASH, report);
                writeInternalFile(appCtx, FILE_LATEST_REPORT, report);
            } catch (Throwable ignored) {}
            if (prev != null) {
                prev.uncaughtException(thread, ex);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }

    public static void markGameSessionStart(Context context) {
        if (context == null) return;
        String payload = System.currentTimeMillis() + ":" + Process.myPid();
        writeInternalFile(context, FILE_SESSION_MARKER, payload);
    }

    public static void markCleanExit(Context context) {
        if (context == null) return;
        File marker = new File(context.getFilesDir(), FILE_SESSION_MARKER);
        if (marker.exists()) marker.delete();

        List<String> lines = new ArrayList<>();
        File cleanFile = new File(context.getFilesDir(), FILE_CLEAN_EXITS);
        if (cleanFile.isFile()) {
            String prev = readFileUtf8(cleanFile, 4096);
            for (String line : prev.split("\\r?\\n")) {
                String t = line.trim();
                if (!t.isEmpty()) lines.add(t);
            }
        }
        lines.add(System.currentTimeMillis() + ":" + Process.myPid());
        while (lines.size() > 10) lines.remove(0);
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        writeInternalFile(context, FILE_CLEAN_EXITS, sb.toString());
    }

    public static PendingCrash checkPendingCrash(Context context, File baseDir) {
        if (context == null) return null;

        // 1. Check uncaught Java exception saved to internal storage
        File pendingFile = new File(context.getFilesDir(), FILE_PENDING_CRASH);
        if (pendingFile.isFile() && pendingFile.length() > 0) {
            String report = readFileUtf8(pendingFile, 64 * 1024);
            if (!report.trim().isEmpty()) {
                writeInternalFile(context, FILE_LATEST_REPORT, report);
                return new PendingCrash(report, System.currentTimeMillis(), true);
            }
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastAckTs = prefs.getLong(PREF_LAST_ACK_TS, 0L);
        List<long[]> cleanExits = loadCleanExits(context);

        // 2. Android 11+ (API 30+): inspect ApplicationExitInfo for native crashes / ANRs / abnormal kills
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 12);
                    if (exits != null) {
                        for (ApplicationExitInfo info : exits) {
                            long ts = info.getTimestamp();
                            if (ts <= lastAckTs) break;

                            int reason = info.getReason();
                            int status = info.getStatus();
                            String proc = info.getProcessName() != null ? info.getProcessName() : "";

                            if (isRecordedCleanExit(info.getPid(), ts, cleanExits)
                                    && (reason == ApplicationExitInfo.REASON_SIGNALED
                                    || reason == ApplicationExitInfo.REASON_EXIT_SELF
                                    || reason == ApplicationExitInfo.REASON_USER_REQUESTED)) {
                                if (ts > lastAckTs) {
                                    lastAckTs = ts;
                                    prefs.edit().putLong(PREF_LAST_ACK_TS, lastAckTs).apply();
                                }
                                continue;
                            }

                            boolean isCrash = reason == ApplicationExitInfo.REASON_CRASH
                                    || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                                    || reason == ApplicationExitInfo.REASON_ANR
                                    || reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                                    || reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
                                    || (reason == ApplicationExitInfo.REASON_LOW_MEMORY && proc.endsWith(":game"))
                                    || reason == ApplicationExitInfo.REASON_SIGNALED
                                    || (reason == ApplicationExitInfo.REASON_EXIT_SELF && status != 0);

                            if (!isCrash) continue;

                            String report = buildProcessExitReport(context, baseDir, info);
                            writeInternalFile(context, FILE_LATEST_REPORT, report);
                            return new PendingCrash(report, ts, false);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 3. Fallback for API < 30 or unreported native aborts: active game marker while :game is gone
        File sessionMarker = new File(context.getFilesDir(), FILE_SESSION_MARKER);
        if (sessionMarker.isFile() && !isGameProcessRunning(context)) {
            long sessionTs = parseSessionTs(readFileUtf8(sessionMarker, 256));
            boolean hadCleanAfterSession = false;
            for (long[] pair : cleanExits) {
                if (pair[0] >= sessionTs && sessionTs > 0) {
                    hadCleanAfterSession = true;
                    break;
                }
            }
            if (hadCleanAfterSession) {
                sessionMarker.delete();
                return null;
            }
            long now = System.currentTimeMillis();
            if (now - sessionTs > 1500L) {
                String report = buildUnexpectedExitFallback(context, baseDir, sessionTs);
                writeInternalFile(context, FILE_LATEST_REPORT, report);
                return new PendingCrash(report, now, false);
            }
        }
        return null;
    }

    public static void acknowledgeCrash(Context context, long exitTs) {
        if (context == null) return;
        File pending = new File(context.getFilesDir(), FILE_PENDING_CRASH);
        if (pending.exists()) pending.delete();
        File marker = new File(context.getFilesDir(), FILE_SESSION_MARKER);
        if (marker.exists()) marker.delete();

        if (exitTs > 0L) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long prev = prefs.getLong(PREF_LAST_ACK_TS, 0L);
            if (exitTs > prev) {
                prefs.edit().putLong(PREF_LAST_ACK_TS, exitTs).apply();
            }
        }
    }

    public static String readLatestReport(Context context) {
        if (context == null) return "";
        File latest = new File(context.getFilesDir(), FILE_LATEST_REPORT);
        if (!latest.isFile()) return "";
        return readFileUtf8(latest, 64 * 1024);
    }

    public static String buildDiagnosticSnapshot(Context context, File baseDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== PDG3Android · Registro de Diagnóstico ===\n");
        appendEnvironmentHeader(context, sb, getCurrentProcessName(context));
        appendIniSummary(sb, baseDir);
        appendLogTail(sb, baseDir, 80);
        appendLogcatTail(sb, 100);
        return sb.toString();
    }

    public static File saveReportToTxt(Context context, File baseDir, String report, String prefix) {
        if (context == null || report == null) return null;
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String cleanPrefix = (prefix == null || prefix.trim().isEmpty()) ? "report" : prefix.trim();
        String fileName = cleanPrefix + "_" + stamp + ".txt";

        File dir = null;
        boolean canExt = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
        if (canExt && baseDir != null) {
            File extLogs = new File(baseDir, "logs");
            if (extLogs.isDirectory() || extLogs.mkdirs()) {
                dir = extLogs;
            }
        }
        if (dir == null) {
            File appLogs = context.getExternalFilesDir("logs");
            if (appLogs != null && (appLogs.isDirectory() || appLogs.mkdirs())) {
                dir = appLogs;
            }
        }
        if (dir == null) {
            dir = new File(context.getFilesDir(), "logs");
            dir.mkdirs();
        }

        File out = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(report.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String buildUncaughtJavaReport(Context ctx, Thread thread, Throwable ex) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== PDG3Android · Cierre Inesperado (Java) ===\n");
        appendEnvironmentHeader(ctx, sb, getCurrentProcessName(ctx));
        sb.append("Hilo: ").append(thread != null ? thread.getName() : "desconocido").append('\n');

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        if (ex != null) ex.printStackTrace(pw);
        pw.flush();
        sb.append("\n--- Traza de Excepción ---\n").append(sw.toString()).append('\n');

        File baseDir = new File("/sdcard/Drakengard3");
        appendIniSummary(sb, baseDir);
        appendLogTail(sb, baseDir, 60);
        appendLogcatTail(sb, 60);
        return sb.toString();
    }

    private static String buildProcessExitReport(Context ctx, File baseDir, ApplicationExitInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== PDG3Android · Cierre Inesperado de Proceso ===\n");
        String proc = Build.VERSION.SDK_INT >= 30 && info.getProcessName() != null
                ? info.getProcessName() : ctx.getPackageName();
        appendEnvironmentHeader(ctx, sb, proc);
        if (Build.VERSION.SDK_INT >= 30) {
            sb.append("Fecha salida: ").append(formatTime(info.getTimestamp())).append('\n');
            sb.append("Motivo: ").append(reasonLabel(info.getReason())).append('\n');
            sb.append("Estado / Señal: ").append(info.getStatus()).append('\n');
            if (info.getDescription() != null && !info.getDescription().isEmpty()) {
                sb.append("Detalle: ").append(info.getDescription()).append('\n');
            }
            sb.append("PSS / RSS aprox.: ").append(info.getPss() / 1024L).append(" MB / ")
                    .append(info.getRss() / 1024L).append(" MB\n");

            try (InputStream is = info.getTraceInputStream()) {
                if (is != null) {
                    byte[] raw = readStreamBytes(is, 24 * 1024);
                    if (raw.length > 0) {
                        if (isReadableText(raw)) {
                            String trace = new String(raw, StandardCharsets.UTF_8).trim();
                            if (!trace.isEmpty()) {
                                sb.append("\n--- Traza del Sistema ---\n").append(trace).append('\n');
                            }
                        } else {
                            sb.append("\n--- Traza del Sistema ---\n")
                                    .append("(Traza binaria protobuf omitida; consultar la traza nativa en Logcat de crash abajo.)\n");
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        appendCrashLogcatTail(sb, 100);
        appendIniSummary(sb, baseDir);
        appendLogTail(sb, baseDir, 60);
        appendLogcatTail(sb, 80);
        return sb.toString();
    }

    private static String buildUnexpectedExitFallback(Context ctx, File baseDir, long sessionTs) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== PDG3Android · Cierre Inesperado en Juego ===\n");
        appendEnvironmentHeader(ctx, sb, ctx.getPackageName() + ":game");
        if (sessionTs > 0) {
            sb.append("Inicio de sesión: ").append(formatTime(sessionTs)).append('\n');
        }
        sb.append("Motivo: La sesión del juego finalizó de forma repentina sin confirmación de salida.\n");
        appendIniSummary(sb, baseDir);
        appendLogTail(sb, baseDir, 80);
        appendLogcatTail(sb, 100);
        return sb.toString();
    }

    private static void appendEnvironmentHeader(Context ctx, StringBuilder sb, String processName) {
        String ver = "?";
        long code = 0;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (pi != null) {
                if (pi.versionName != null) ver = pi.versionName;
                code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            }
        } catch (Throwable ignored) {}
        String abi = (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0)
                ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        sb.append("Fecha informe: ").append(formatTime(System.currentTimeMillis())).append('\n');
        sb.append("Aplicación: PDG3Android v").append(ver).append(" (build ").append(code).append(")\n");
        sb.append("Paquete: ").append(ctx.getPackageName()).append('\n');
        sb.append("Proceso: ").append(processName).append('\n');
        sb.append("Sistema: Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(") · ABI ").append(abi).append('\n');
    }

    private static void appendIniSummary(StringBuilder sb, File baseDir) {
        if (baseDir == null) return;
        File iniFile = new File(baseDir, "dod3.ini");
        if (!iniFile.isFile() || !iniFile.canRead()) return;
        IniStore ini = new IniStore(iniFile);
        sb.append("\n--- Configuración activa (dod3.ini) ---\n");
        String[] keys = {
                "RSX_SCALE", "DOD3_FPS", "RSX_VSYNC", "RSX_AA", "RSX_ANISO",
                "DOD3_SHADOWS", "DOD3_DRAW_DISTANCE", "DOD3_TREE_LOD",
                "DOD3_POSTFX", "DOD3_DOF", "DOD3_MOTION_BLUR", "DOD3_DISTORTION",
                "DOD3_MENU_LANGUAGE", "DOD3_PROMPTS"
        };
        for (String k : keys) {
            String v = ini.get(k, "");
            if (!v.isEmpty()) sb.append(k).append('=').append(v).append('\n');
        }
    }

    private static void appendLogTail(StringBuilder sb, File baseDir, int maxLines) {
        if (baseDir == null) return;
        File log = new File(baseDir, "dod3.log");
        if (!log.isFile() || !log.canRead()) return;
        List<String> lines = readTailLines(log, maxLines);
        if (lines.isEmpty()) return;
        sb.append("\n--- Últimas líneas de dod3.log ---\n");
        for (String l : lines) sb.append(l).append('\n');
    }

    private static void appendCrashLogcatTail(StringBuilder sb, int maxLines) {
        try {
            java.lang.Process p = new ProcessBuilder("logcat", "-d", "-b", "crash", "-v", "time", "-t", String.valueOf(maxLines))
                    .redirectErrorStream(true)
                    .start();
            String out = readStreamUtf8(p.getInputStream(), 24 * 1024);
            p.waitFor();
            if (!out.trim().isEmpty()) {
                sb.append("\n--- Registro nativo de fallos (logcat crash) ---\n").append(out.trim()).append('\n');
            }
        } catch (Throwable ignored) {}
    }

    private static void appendLogcatTail(StringBuilder sb, int maxLines) {
        try {
            java.lang.Process p = new ProcessBuilder("logcat", "-d", "-v", "time", "-t", String.valueOf(maxLines))
                    .redirectErrorStream(true)
                    .start();
            String out = readStreamUtf8(p.getInputStream(), 24 * 1024);
            p.waitFor();
            if (!out.trim().isEmpty()) {
                sb.append("\n--- Logcat reciente de la app ---\n").append(out.trim()).append('\n');
            }
        } catch (Throwable ignored) {}
    }

    private static String reasonLabel(int reason) {
        if (Build.VERSION.SDK_INT < 30) return String.valueOf(reason);
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH: return "CRASH (Excepción Java sin capturar)";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE (Fallo nativo SIGSEGV / SIGABRT)";
            case ApplicationExitInfo.REASON_ANR: return "ANR (Aplicación sin respuesta)";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY (Memoria insuficiente)";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED (Señal del sistema)";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF (Salida con código de error)";
            default: return "Código " + reason;
        }
    }

    private static boolean isGameProcessRunning(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return false;
            String target = ctx.getPackageName() + ":game";
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (target.equals(p.processName)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static List<long[]> loadCleanExits(Context ctx) {
        List<long[]> list = new ArrayList<>();
        File f = new File(ctx.getFilesDir(), FILE_CLEAN_EXITS);
        if (!f.isFile()) return list;
        String raw = readFileUtf8(f, 4096);
        for (String line : raw.split("\\r?\\n")) {
            String[] parts = line.trim().split(":");
            if (parts.length == 2) {
                try {
                    list.add(new long[] { Long.parseLong(parts[0]), Long.parseLong(parts[1]) });
                } catch (NumberFormatException ignored) {}
            }
        }
        return list;
    }

    private static boolean isRecordedCleanExit(int pid, long exitTs, List<long[]> cleanExits) {
        for (long[] pair : cleanExits) {
            long cleanTs = pair[0];
            long cleanPid = pair[1];
            if (cleanPid > 0 && cleanPid == pid && Math.abs(exitTs - cleanTs) <= 30000L) {
                return true;
            }
            if (Math.abs(exitTs - cleanTs) <= 5000L) {
                return true;
            }
        }
        return false;
    }

    private static long parseSessionTs(String s) {
        if (s == null) return 0L;
        String[] parts = s.trim().split(":");
        if (parts.length >= 1 && !parts[0].isEmpty()) {
            try { return Long.parseLong(parts[0]); } catch (NumberFormatException ignored) {}
        }
        return 0L;
    }

    private static String getCurrentProcessName(Context ctx) {
        if (Build.VERSION.SDK_INT >= 28) {
            String p = Application.getProcessName();
            if (p != null && !p.isEmpty()) return p;
        }
        return ctx.getPackageName();
    }

    private static String formatTime(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
    }

    private static void writeInternalFile(Context ctx, String name, String content) {
        try {
            File f = new File(ctx.getFilesDir(), name);
            File tmp = new File(ctx.getFilesDir(), name + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            if (f.exists()) f.delete();
            tmp.renameTo(f);
        } catch (Throwable ignored) {}
    }

    private static String readFileUtf8(File f, int maxBytes) {
        if (f == null || !f.isFile()) return "";
        try (FileInputStream fis = new FileInputStream(f)) {
            return readStreamUtf8(fis, maxBytes);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static byte[] readStreamBytes(InputStream is, int maxBytes) {
        try {
            byte[] buf = new byte[4096];
            int total = 0;
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            int n;
            while (total < maxBytes && (n = is.read(buf, 0, Math.min(buf.length, maxBytes - total))) > 0) {
                baos.write(buf, 0, n);
                total += n;
            }
            return baos.toByteArray();
        } catch (Throwable ignored) {
            return new byte[0];
        }
    }

    private static boolean isReadableText(byte[] raw) {
        if (raw == null || raw.length == 0) return false;
        int len = Math.min(raw.length, 512);
        int bad = 0;
        for (int i = 0; i < len; i++) {
            int b = raw[i] & 0xFF;
            if (b == 0) return false;
            if (b < 0x20 && b != '\n' && b != '\r' && b != '\t') bad++;
        }
        return bad * 20 <= len;
    }

    private static String readStreamUtf8(InputStream is, int maxBytes) {
        byte[] raw = readStreamBytes(is, maxBytes);
        if (raw.length == 0) return "";
        try {
            return new String(raw, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static List<String> readTailLines(File file, int maxLines) {
        List<String> ring = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                ring.add(line);
                if (ring.size() > maxLines) ring.remove(0);
            }
        } catch (Throwable ignored) {}
        return ring;
    }
}
