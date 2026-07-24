package com.fmea.highfps;

import android.content.Context;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * SessionPathManager: single source of truth for where everything for one recording
 * session lives on disk.
 *
 * Layout (per modification #6):
 *   Android/media/com.fmea.highfps/Media/<sessionName>/
 *       frames/         -> captured TIFF frames
 *       parameters/     -> BLE (CO2, temp, humidity) sensor_data.csv
 *       Averaged_data/  -> averaged streak vector flow PNG + vector points .xls
 *       results/        -> final BLE graphs, streak vector flow video (ready to download)
 *
 * Everything for a session lives under one folder so the Media browser (MediaActivity)
 * shows one entry per recording instead of scattered top-level folders.
 */
public class SessionPathManager {

    public static final String MEDIA_ROOT_FOLDER = "Media";
    public static final String FRAMES_SUBFOLDER = "frames";
    public static final String PARAMETERS_SUBFOLDER = "parameters";
    public static final String AVERAGED_DATA_SUBFOLDER = "Averaged_data";
    public static final String RESULTS_SUBFOLDER = "results";

    /** Root folder that contains one subfolder per session. */
    public static File getMediaRoot(Context context) {
        File mediaDir = context.getExternalMediaDirs()[0];
        File root = new File(mediaDir, MEDIA_ROOT_FOLDER);
        if (!root.exists()) root.mkdirs();
        return root;
    }

    /** Creates (if needed) and returns the root folder for one session, e.g. Media/session_20260711_101500 */
    public static File getSessionDir(Context context, String sessionName) {
        File sessionDir = new File(getMediaRoot(context), sessionName);
        if (!sessionDir.exists()) sessionDir.mkdirs();
        return sessionDir;
    }

    public static File getFramesDirectory(Context context, String sessionName) {
        return ensureSubfolder(context, sessionName, FRAMES_SUBFOLDER);
    }

    public static File getParametersDirectory(Context context, String sessionName) {
        return ensureSubfolder(context, sessionName, PARAMETERS_SUBFOLDER);
    }

    public static File getAveragedDataDirectory(Context context, String sessionName) {
        return ensureSubfolder(context, sessionName, AVERAGED_DATA_SUBFOLDER);
    }

    public static File getResultsDirectory(Context context, String sessionName) {
        return ensureSubfolder(context, sessionName, RESULTS_SUBFOLDER);
    }

    private static File ensureSubfolder(Context context, String sessionName, String subfolder) {
        File dir = new File(getSessionDir(context, sessionName), subfolder);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Lists every session folder that has captured frames, newest first. */
    public static List<File> listSessions(Context context) {
        List<File> sessions = new ArrayList<>();
        File root = getMediaRoot(context);
        File[] children = root.listFiles(File::isDirectory);
        if (children != null) sessions.addAll(Arrays.asList(children));
        sessions.sort(Comparator.comparingLong(File::lastModified).reversed());
        return sessions;
    }

    /** Given any folder under Media/<sessionName>/..., resolves the sessionName. */
    public static String sessionNameOf(Context context, File anyPathUnderSession) {
        File root = getMediaRoot(context);
        File cursor = anyPathUnderSession;
        while (cursor != null && cursor.getParentFile() != null) {
            if (cursor.getParentFile().equals(root)) return cursor.getName();
            cursor = cursor.getParentFile();
        }
        return anyPathUnderSession.getName();
    }
}
