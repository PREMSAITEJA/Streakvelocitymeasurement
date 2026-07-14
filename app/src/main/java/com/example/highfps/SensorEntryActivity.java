package com.example.highfps;

import android.content.Context;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class SensorEntryActivity extends AppCompatActivity {
    private static final String TAG = "SensorEntryActivity";

    private RecyclerView rvSensorDataFiles;
    private SensorDataAdapter adapter;
    private final List<File> sensorDataFiles = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sensor_data_viewer);

        rvSensorDataFiles = findViewById(R.id.rvSensorDataFiles);
        rvSensorDataFiles.setLayoutManager(new LinearLayoutManager(this));

        loadSensorDataFiles();
    }

    private void loadSensorDataFiles() {
        sensorDataFiles.clear();
        
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File[] children = downloads.listFiles();
        
        if (children != null) {
            for (File f : children) {
                if (f.isDirectory()) {
                    File sensorFile = new File(f, "sensor_data.csv");
                    if (sensorFile.exists()) {
                        sensorDataFiles.add(sensorFile);
                    }
                }
            }
        }

        adapter = new SensorDataAdapter(this, sensorDataFiles, sensorFile -> dumpSensorData(sensorFile));
        rvSensorDataFiles.setAdapter(adapter);
    }

    private void dumpSensorData(File sensorFile) {
        if (!sensorFile.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show();
            return;
        }

        String folderName = "SmartPIV_SensorExport_" + sensorFile.getParentFile().getName();

        new Thread(() -> {
            try {
                String csvFileName = "sensor_data.csv";
                try (OutputStream os = ExportStorageHelper.getDownloadsOutputStream(this, folderName, csvFileName)) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new FileReader(sensorFile))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line).append("\n");
                        }
                    }
                    os.write(sb.toString().getBytes());
                }

                runOnUiThread(() -> Toast.makeText(SensorEntryActivity.this, "Sensor data exported to Downloads/" + folderName, Toast.LENGTH_LONG).show());

            } catch (IOException e) {
                Log.e(TAG, "Sensor export failure", e);
                runOnUiThread(() -> Toast.makeText(SensorEntryActivity.this, "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private static class SensorDataAdapter extends RecyclerView.Adapter<SensorDataAdapter.ViewHolder> {
        private final Context context;
        private final List<File> files;
        private final OnDumpClickListener listener;

        public interface OnDumpClickListener {
            void onDump(File sensorFile);
        }

        public SensorDataAdapter(Context context, List<File> files, OnDumpClickListener listener) {
            this.context = context;
            this.files = files;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_sensor_data, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            File file = files.get(position);
            holder.tvSensorDataName.setText(file.getParentFile().getName());

            int lineCount = 0;
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                while (br.readLine() != null) {
                    lineCount++;
                }
            } catch (IOException e) {
                Log.e(TAG, "Error reading sensor file", e);
            }

            holder.tvSensorDataMeta.setText(lineCount + " sensor readings");
            holder.btnDump.setOnClickListener(v -> listener.onDump(file));
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        public static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvSensorDataName, tvSensorDataMeta;
            Button btnDump;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvSensorDataName = itemView.findViewById(R.id.tvSensorDataName);
                tvSensorDataMeta = itemView.findViewById(R.id.tvSensorDataMeta);
                btnDump = itemView.findViewById(R.id.btnDumpSensorData);
            }
        }
    }
}
