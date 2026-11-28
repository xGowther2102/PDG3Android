package com.pdg3android.xgowther2102;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consulta las versiones publicadas en GitHub Releases para PDG3Android.
 */
public final class UpdateChecker {

    public static final String REPO_URL = "https://github.com/xGowther2102/PDG3Android";
    public static final String RELEASES_URL = REPO_URL + "/releases";
    private static final String API_LATEST_URL = "https://api.github.com/repos/xGowther2102/PDG3Android/releases/latest";

    public static final class Result {
        public boolean ok;
        public boolean updateAvailable;
        public boolean hasApkAsset;
        public String latestTag = "";
        public String releaseTitle = "";
        public String notes = "";
        public String apkFileName = "";
        public String downloadUrl = "";
        public String pageUrl = RELEASES_URL;
        public String errorMsg = "";
    }

    public interface DownloadListener {
        void onProgress(long downloadedBytes, long totalBytes);
        boolean isCancelled();
    }

    private UpdateChecker() {}

    public static Result checkLatest(String currentVersion) {
        Result res = new Result();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(API_LATEST_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "PDG3Android/" + (currentVersion != null ? currentVersion : "0.6"));

            int code = conn.getResponseCode();
            if (code == 404) {
                res.ok = false;
                res.errorMsg = "Sin lanzamientos publicados aún en GitHub Releases.";
                return res;
            }
            if (code < 200 || code >= 300) {
                res.ok = false;
                res.errorMsg = "Respuesta HTTP " + code + " al consultar GitHub.";
                return res;
            }

            String jsonText;
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                int total = 0;
                while (total < 256 * 1024 && (n = is.read(buf)) > 0) {
                    baos.write(buf, 0, n);
                    total += n;
                }
                jsonText = baos.toString(StandardCharsets.UTF_8.name());
            }

            JSONObject obj = new JSONObject(jsonText);
            String tag = obj.optString("tag_name", "").trim();
            String name = obj.optString("name", "").trim();
            String body = obj.optString("body", "").trim();
            String htmlUrl = obj.optString("html_url", RELEASES_URL).trim();

            String apkUrl = "";
            String apkName = "";
            JSONArray assets = obj.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String rawName = a.optString("name", "").trim();
                    String aName = rawName.toLowerCase(Locale.ROOT);
                    String dl = a.optString("browser_download_url", "");
                    if (aName.endsWith(".apk") && !dl.isEmpty()) {
                        apkUrl = dl;
                        apkName = rawName;
                        if (aName.contains("release")) break;
                    }
                }
            }

            res.ok = true;
            res.latestTag = tag.isEmpty() ? name : tag;
            res.releaseTitle = name.isEmpty() ? res.latestTag : name;
            res.notes = body;
            res.pageUrl = htmlUrl.isEmpty() ? RELEASES_URL : htmlUrl;
            res.hasApkAsset = !apkUrl.isEmpty();
            res.apkFileName = apkName;
            res.downloadUrl = apkUrl.isEmpty() ? res.pageUrl : apkUrl;
            res.updateAvailable = !res.latestTag.isEmpty() && compareVersions(res.latestTag, currentVersion) > 0;
            return res;
        } catch (Exception e) {
            res.ok = false;
            res.errorMsg = "No se pudo conectar con GitHub para comprobar actualizaciones.";
            return res;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static File downloadApk(String urlStr, File destFile, DownloadListener listener) throws Exception {
        if (urlStr == null || urlStr.isEmpty() || destFile == null) {
            throw new IllegalArgumentException("URL o destino inválido.");
        }
        File dir = destFile.getParentFile();
        if (dir != null) {
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("No se pudo crear el directorio temporal.");
            }
            File[] existing = dir.listFiles();
            if (existing != null) {
                for (File f : existing) {
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".apk") || n.endsWith(".part")) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
        }

        File partFile = new File(dir, destFile.getName() + ".part");
        HttpURLConnection conn = null;
        try {
            URL currentUrl = new URL(urlStr);
            for (int hop = 0; hop <= 6; hop++) {
                HttpURLConnection nextConn = (HttpURLConnection) currentUrl.openConnection();
                nextConn.setInstanceFollowRedirects(false);
                nextConn.setConnectTimeout(15000);
                nextConn.setReadTimeout(30000);
                nextConn.setRequestMethod("GET");
                nextConn.setRequestProperty("Accept", "application/octet-stream");
                nextConn.setRequestProperty("User-Agent", "PDG3Android-Updater");
                int code = nextConn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = nextConn.getHeaderField("Location");
                    nextConn.disconnect();
                    if (loc == null || loc.isEmpty()) {
                        throw new IOException("Redirección vacía al descargar actualización.");
                    }
                    currentUrl = new URL(currentUrl, loc);
                    if (hop == 6) {
                        throw new IOException("Demasiadas redirecciones al descargar el APK.");
                    }
                    continue;
                }
                conn = nextConn;
                break;
            }
            if (conn == null) {
                throw new IOException("No se pudo abrir la conexión de descarga.");
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Error HTTP " + status + " al descargar el APK.");
            }

            long totalBytes = conn.getContentLengthLong();
            long downloaded = 0L;
            long lastNotifyMs = 0L;

            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(partFile)) {
                byte[] buf = new byte[32 * 1024];
                int read;
                while ((read = is.read(buf)) != -1) {
                    if (listener != null && listener.isCancelled()) {
                        //noinspection ResultOfMethodCallIgnored
                        partFile.delete();
                        return null;
                    }
                    fos.write(buf, 0, read);
                    downloaded += read;
                    if (listener != null) {
                        long now = System.currentTimeMillis();
                        if (now - lastNotifyMs >= 150L || (totalBytes > 0 && downloaded >= totalBytes)) {
                            lastNotifyMs = now;
                            listener.onProgress(downloaded, totalBytes);
                        }
                    }
                }
                fos.flush();
            }

            if (partFile.length() <= 1024L) {
                throw new IOException("El archivo descargado está vacío o incompleto.");
            }
            byte[] magic = new byte[4];
            try (FileInputStream fis = new FileInputStream(partFile)) {
                if (fis.read(magic) < 4 || magic[0] != 0x50 || magic[1] != 0x4B || magic[2] != 0x03 || magic[3] != 0x04) {
                    throw new IOException("El archivo descargado no es un paquete APK válido.");
                }
            }

            if (destFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                destFile.delete();
            }
            if (!partFile.renameTo(destFile)) {
                throw new IOException("No se pudo finalizar el archivo APK descargado.");
            }
            return destFile;
        } finally {
            if (conn != null) conn.disconnect();
            if (!destFile.exists() && partFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                partFile.delete();
            }
        }
    }

    public static int compareVersions(String remote, String local) {
        List<Integer> a = parseNumericParts(remote);
        List<Integer> b = parseNumericParts(local);
        int max = Math.max(a.size(), b.size());
        for (int i = 0; i < max; i++) {
            int va = i < a.size() ? a.get(i) : 0;
            int vb = i < b.size() ? b.get(i) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static List<Integer> parseNumericParts(String v) {
        List<Integer> out = new ArrayList<>();
        if (v == null) return out;
        Matcher m = Pattern.compile("(?:^|[^0-9])v?(\\d+(?:\\.\\d+)+)", Pattern.CASE_INSENSITIVE).matcher(v);
        if (m.find()) {
            String ver = m.group(1);
            if (ver != null) {
                for (String p : ver.split("\\.")) {
                    try {
                        out.add(Integer.parseInt(p));
                    } catch (NumberFormatException ignored) {}
                }
                return out;
            }
        }
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(0, dash);
        int plus = s.indexOf('+');
        if (plus >= 0) s = s.substring(0, plus);
        for (String p : s.split("\\.")) {
            String digits = p.replaceAll("[^0-9]", "");
            if (digits.isEmpty()) continue;
            try {
                out.add(Integer.parseInt(digits));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }
}
