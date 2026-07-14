package com.example.highfps;

import com.github.mikephil.charting.data.Entry;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SensorCsvLoader {
    private static final String TAG = "SensorCsvLoader";

    public static Map<String, List<Entry>> parseSensorCsv(File file) {
        Map<String, List<Entry>> multiDataset = new HashMap<>();
        List<Entry> co2Entries = new ArrayList<>();
        List<Entry> tempEntries = new ArrayList<>();
        List<Entry> rhEntries = new ArrayList<>();

        if (!file.exists()) {
            Log.e(TAG, "Sensor file missing: " + file.getAbsolutePath());
            multiDataset.put("co2", co2Entries);
            multiDataset.put("temp", tempEntries);
            multiDataset.put("humidity", rhEntries);
            return multiDataset;
        }

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line = br.readLine(); // Skip standard header
            int stepIndex = 0;

            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                // Automatically split by either a comma OR a tab character
                String[] tokens = line.split(",|\\t");

                if (tokens.length >= 4) {
                    float co2 = Float.parseFloat(tokens[1].trim());
                    float temp = Float.parseFloat(tokens[2].trim());
                    float humidity = Float.parseFloat(tokens[3].trim());

                    co2Entries.add(new Entry(stepIndex, co2));
                    tempEntries.add(new Entry(stepIndex, temp));
                    rhEntries.add(new Entry(stepIndex, humidity));
                    stepIndex++;
                }
            }
            Log.d(TAG, "Successfully loaded " + stepIndex + " sensor points.");
        } catch (Exception e) {
            Log.e(TAG, "Error parsing sensor dataset", e);
        }

        multiDataset.put("co2", co2Entries);
        multiDataset.put("temp", tempEntries);
        multiDataset.put("humidity", rhEntries);
        return multiDataset;
    }
}