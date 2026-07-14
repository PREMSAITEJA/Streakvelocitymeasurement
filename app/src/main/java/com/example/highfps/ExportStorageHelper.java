package com.example.highfps;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class ExportStorageHelper {

    public static OutputStream getDownloadsOutputStream(Context context, String folderName, String fileName) throws IOException {
        return getDownloadsOutputStream(context, folderName, fileName, inferMimeType(fileName));
    }

    public static OutputStream getDownloadsOutputStream(Context context, String folderName, String fileName, String mimeType) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + folderName);

            Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Failed to create entry in Downloads: " + fileName);
            return context.getContentResolver().openOutputStream(uri);
        } else {
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File subFolder = new File(downloadsDir, folderName);
            if (!subFolder.exists() && !subFolder.mkdirs()) {
                throw new IOException("Directory creation failed: " + subFolder.getAbsolutePath());
            }
            File targetOutputFile = new File(subFolder, fileName);
            return new FileOutputStream(targetOutputFile);
        }
    }

    private static String inferMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".mp4")) return "video/mp4";
        return "text/csv";
    }
}