package com.pdg3android.xgowther2102;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Clean key-value reader/writer for dod3.ini that preserves comments and formatting.
 */
public class IniStore {
    private final File mFile;
    private final List<String> mLines = new ArrayList<>();
    private final Map<String, String> mValues = new LinkedHashMap<>();

    public IniStore(File file) {
        mFile = file;
        load();
    }

    public void load() {
        mLines.clear();
        mValues.clear();
        if (!mFile.isFile()) return;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(mFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                mLines.add(line);
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith(";")) continue;
                int eq = t.indexOf('=');
                if (eq > 0) {
                    String k = t.substring(0, eq).trim();
                    String v = t.substring(eq + 1).trim();
                    mValues.put(k, v);
                }
            }
        } catch (Exception ignored) {}
    }

    public String get(String key, String defVal) {
        String v = mValues.get(key);
        return (v != null && !v.isEmpty()) ? v : defVal;
    }

    public boolean getBool(String key, boolean defVal) {
        String v = mValues.get(key);
        if (v == null || v.isEmpty()) return defVal;
        return !v.equals("0") && !v.equalsIgnoreCase("false") && !v.equalsIgnoreCase("off");
    }

    public void setInMemory(String key, String value) {
        if (value == null || value.isEmpty()) {
            mValues.remove(key);
        } else {
            mValues.put(key, value);
        }
        // Update existing active or commented line in-place if found
        boolean updated = false;
        for (int i = 0; i < mLines.size(); i++) {
            String t = mLines.get(i).trim();
            boolean commented = t.startsWith("#");
            String body = commented ? t.substring(1).trim() : t;
            int eq = body.indexOf('=');
            if (eq > 0 && body.substring(0, eq).trim().equals(key)) {
                if (value == null || value.isEmpty()) {
                    mLines.set(i, "#" + key + " =");
                } else {
                    mLines.set(i, key + " = " + value);
                }
                updated = true;
                // Comment out any duplicate active entries later in file
                for (int j = i + 1; j < mLines.size(); j++) {
                    String tj = mLines.get(j).trim();
                    if (!tj.startsWith("#")) {
                        int eqj = tj.indexOf('=');
                        if (eqj > 0 && tj.substring(0, eqj).trim().equals(key)) {
                            mLines.set(j, "#" + tj);
                        }
                    }
                }
                break;
            }
        }
        if (!updated && value != null && !value.isEmpty()) {
            mLines.add(key + " = " + value);
        }
    }

    public void set(String key, String value) {
        setInMemory(key, value);
        save();
    }

    public void save() {
        try {
            File parent = mFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File tmp = new File(mFile.getParentFile(), mFile.getName() + ".tmp");
            try (BufferedWriter bw = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
                for (String l : mLines) {
                    bw.write(l);
                    bw.newLine();
                }
            }
            if (mFile.exists()) mFile.delete();
            if (!tmp.renameTo(mFile)) {
                try (BufferedWriter bw = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(mFile), StandardCharsets.UTF_8))) {
                    for (String l : mLines) {
                        bw.write(l);
                        bw.newLine();
                    }
                }
                tmp.delete();
            }
        } catch (Exception ignored) {}
    }
}
