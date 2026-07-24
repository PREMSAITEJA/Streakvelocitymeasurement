package com.fmea.highfps;

import android.content.Context;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Small convenience helpers for writing into a session's Averaged_data / parameters
 * folders (see SessionPathManager for the full layout). Not required by the main
 * pipeline (AnalysisActivity / BleSensorManager write directly via SessionPathManager)
 * but kept for simple one-off writes.
 */
public class SessionResultManager {

    public static void saveAveragedVectors(Context context, String sessionName, String csvContent) {
        try {
            File dir = SessionPathManager.getAveragedDataDirectory(context, sessionName);
            File file = new File(dir, "averaged_vectors.csv");

            FileWriter writer = new FileWriter(file);
            writer.write(csvContent);
            writer.flush();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void appendSensorData(Context context, String sessionName, String csvRowData) {
        try {
            File dir = SessionPathManager.getParametersDirectory(context, sessionName);
            File file = new File(dir, "sensor_data.csv");

            boolean append = file.exists();
            FileWriter writer = new FileWriter(file, true);

            if (!append) {
                writer.write("Timestamp,CO2,Temp,Humidity\n");
            }
            writer.write(csvRowData);
            writer.flush();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}