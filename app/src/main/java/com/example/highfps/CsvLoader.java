package com.example.highfps;

import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class CsvLoader {
    private static final String TAG = "CsvLoader";

    public static List<VectorData> loadVectorsFromCsv(File file) {
        List<VectorData> vectors = new ArrayList<>();
        if (!file.exists()) {
            Log.e(TAG, "File path missing: " + file.getAbsolutePath());
            return vectors;
        }

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line = br.readLine(); // Skip Header
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                // Automatically split by either a comma OR a tab character
                String[] tokens = line.split(",|\\t");

                if (tokens.length >= 6) {
                    float x = Float.parseFloat(tokens[0].trim());
                    float y = Float.parseFloat(tokens[1].trim());
                    float u = Float.parseFloat(tokens[2].trim());
                    float v = Float.parseFloat(tokens[3].trim());
                    float mag = Float.parseFloat(tokens[4].trim());
                    float ang = Float.parseFloat(tokens[5].trim());
                    vectors.add(new VectorData(x, y, u, v, mag, ang));
                }
            }
            Log.d(TAG, "Successfully loaded " + vectors.size() + " vectors from " + file.getName());
        } catch (Exception e) {
            Log.e(TAG, "Error processing streak vector dataset structure", e);
        }
        return vectors;
    }
}