package com.pdg3android.xgowther2102;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * ContentProvider de solo lectura para entregar APKs descargados al instalador de Android
 * sin depender de AndroidX FileProvider.
 */
public final class ApkFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.pdg3android.xgowther2102.apkprovider";
    private static final String APK_MIME = "application/vnd.android.package-archive";

    public static File getUpdatesDir(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "updates");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static Uri getUriForFile(File file) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(file.getName()));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return APK_MIME;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = resolveSafeFile(uri);
        if (file == null) {
            return new MatrixCursor(new String[]{ OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE }, 0);
        }
        String[] cols = (projection != null && projection.length > 0)
                ? projection
                : new String[]{ OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                row[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(cols[i])) {
                row[i] = file.length();
            } else {
                row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.contains("w")) {
            throw new SecurityException("Solo se permite lectura");
        }
        File file = resolveSafeFile(uri);
        if (file == null) {
            throw new FileNotFoundException("APK no encontrado para URI: " + uri);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("No soportado");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("No soportado");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("No soportado");
    }

    private File resolveSafeFile(Uri uri) {
        Context ctx = getContext();
        if (ctx == null || uri == null) return null;
        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.size() != 1) return null;
        String seg = segments.get(0);
        if (seg == null || seg.isEmpty() || seg.contains("/") || seg.contains("\\") || seg.contains("..")) {
            return null;
        }
        if (!seg.toLowerCase(Locale.ROOT).endsWith(".apk")) return null;
        try {
            File updatesDir = getUpdatesDir(ctx);
            String baseCanonical = updatesDir.getCanonicalPath() + File.separator;
            File candidate = new File(updatesDir, seg);
            String candidateCanonical = candidate.getCanonicalPath();
            if (!candidateCanonical.startsWith(baseCanonical)) return null;
            return candidate.isFile() ? candidate : null;
        } catch (IOException e) {
            return null;
        }
    }
}
