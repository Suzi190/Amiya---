package com.miya.shell;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 导出接收器：把网页传来的分块数据写入「下载/Miya」。
 * Android 10+ 走 MediaStore（无需存储权限）；旧系统走公共下载目录，失败则退回应用私有目录。
 */
public class MiyaDownloader {

    private ContentResolver resolver;
    private OutputStream out;
    private Uri uri;
    private File file;
    private boolean opened = false;
    private String displayName = "";

    public synchronized boolean begin(Context ctx, String rawName, String mime) {
        abort();
        Context appCtx = ctx.getApplicationContext();
        resolver = appCtx.getContentResolver();
        displayName = sanitize(rawName);
        String type = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, type);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/Miya");
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return false;
                out = resolver.openOutputStream(uri);
            } else {
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "Miya");
                if (!dir.exists() && !dir.mkdirs()) {
                    dir = new File(appCtx.getExternalFilesDir(null), "Miya");
                    if (!dir.exists() && !dir.mkdirs()) return false;
                }
                file = unique(dir, displayName);
                out = new FileOutputStream(file);
            }
            opened = out != null;
            return opened;
        } catch (Exception e) {
            abort();
            return false;
        }
    }

    public synchronized boolean write(byte[] data) {
        if (!opened || out == null || data == null) return false;
        try {
            out.write(data);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 返回 "ok:<位置>" 或 "err:<原因>"，供网页 toast 提示。 */
    public synchronized String finish() {
        if (!opened) return "err:not-open";
        try {
            if (out != null) {
                out.flush();
                out.close();
            }
        } catch (Exception ignored) {
        }
        out = null;
        opened = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (uri != null && resolver != null) {
                try {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    resolver.update(uri, cv, null, null);
                } catch (Exception ignored) {
                }
            }
            String result = "ok:下载/Miya/" + displayName;
            uri = null;
            return result;
        }
        String path = file != null ? file.getAbsolutePath() : displayName;
        file = null;
        return "ok:" + path;
    }

    public synchronized void abort() {
        try {
            if (out != null) out.close();
        } catch (Exception ignored) {
        }
        out = null;
        opened = false;

        if (uri != null && resolver != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                resolver.delete(uri, null, null);
            } catch (Exception ignored) {
            }
        }
        if (file != null && file.exists()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            } catch (Exception ignored) {
            }
        }
        uri = null;
        file = null;
    }

    private static String sanitize(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) name = "miya-" + System.currentTimeMillis();
        name = name.replace("\\", "_")
                .replace("/", "_")
                .replace(":", "_")
                .replace("*", "_")
                .replace("?", "_")
                .replace("\"", "_")
                .replace("<", "_")
                .replace(">", "_")
                .replace("|", "_");
        if (name.length() > 120) {
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0) ext = name.substring(dot);
            name = name.substring(0, 100) + ext;
        }
        return name;
    }

    private static File unique(File dir, String name) {
        File target = new File(dir, name);
        if (!target.exists()) return target;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int i = 1;
        while (target.exists()) {
            target = new File(dir, base + "(" + i + ")" + ext);
            i++;
        }
        return target;
    }
}
