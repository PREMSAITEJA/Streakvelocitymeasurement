package com.example.highfps;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ResultsActivity
 * ─────────────────────────────────────────────────────────────────────────────
 * Displays and exports every result for the current session in three sections:
 *
 *  1. Vector Quiver Field  – averaged streak-vector field (pinch/pan/zoom)
 *  2. Streak Vector Flow   – frame-by-frame playback with motion trail
 *  3. BLE Sensor Live Feed – CO2 / Temperature / Humidity charts that grow in
 *                            real-time as the SCD41 sensor streams data, then
 *                            remain visible after streaming stops so the user
 *                            can inspect the complete dataset before downloading
 *
 * Download mechanism (bottom button):
 *  • Per-frame streak PNGs + averaged vector PNG  (already on disk)
 *  • Streak-flow MP4 video  (built on demand from the PNGs)
 *  • BLE chart PNGs  (rendered from the live-chart views at full resolution)
 *  • BLE raw data CSV  (extracted from LiveBleDataBus session history)
 *  All output goes to  Downloads/<sessionName>_Results/
 *
 * Architecture notes:
 *  • LiveBleDataBus is the single pipeline: BleSensorManager → Bus → LiveChartView
 *  • The bus replays its entire session history the moment this Activity registers
 *    its observer so charts are always fully populated on open, even mid-session
 *  • Observer is removed in onPause() — no background thread ever touches a
 *    destroyed View, eliminating the most common cause of ResultsActivity crashes
 */
public class ResultsActivity extends AppCompatActivity {

    private static final String TAG = "ResultsActivity";
    private static final Pattern FRAME_FILE_PATTERN    = Pattern.compile("^(.*)_vector\\.csv$");
    private static final Pattern FRAME_SEQUENCE_PATTERN = Pattern.compile("frame_(\\d+)_");

    // ── UI references ─────────────────────────────────────────────────────────
    private LinearLayout containerLayout;
    private TextView     tvNoData;
    private Button       btnDownloadAll;

    // Live BLE chart views — held as fields so we can render them to Bitmap on download
    private LiveChartView chartCo2;
    private LiveChartView chartTemp;
    private LiveChartView chartHum;
    private TextView      tvLiveStatus;   // "● LIVE  123 readings" / "● Session ended"

    // ── Streak playback ───────────────────────────────────────────────────────
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable streakPlaybackRunnable;
    private boolean  isStreakPlaying = false;

    // ── Session data ──────────────────────────────────────────────────────────
    private File               activeSessionDir;
    private List<VectorData>   lastAveragedVectors = new ArrayList<>();
    private List<List<VectorData>> lastStreakFrames = new ArrayList<>();

    // ── BLE live observer ─────────────────────────────────────────────────────
    /**
     * Registered in onResume(), removed in onPause().
     * Receives every new reading on the BT callback thread; posts chart updates
     * to the main thread so View.invalidate() is always called safely.
     */
    private final LiveBleDataBus.Observer bleObserver = new LiveBleDataBus.Observer() {
        @Override
        public void onNewReading(LiveBleDataBus.BleReading r) {
            mainHandler.post(() -> appendLivePoint(r));
        }

        @Override
        public void onSessionReset() {
            mainHandler.post(() -> {
                if (chartCo2  != null) chartCo2.clear();
                if (chartTemp != null) chartTemp.clear();
                if (chartHum  != null) chartHum.clear();
                updateLiveStatusLabel();
            });
        }
    };

    // ═════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_results);

        containerLayout = findViewById(R.id.containerVectorDisplay);
        tvNoData        = findViewById(R.id.tvNoVectorData);
        btnDownloadAll  = findViewById(R.id.btnDownloadAllResults);
        btnDownloadAll.setOnClickListener(v -> downloadAllResults());

        loadAndDisplayResults();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Register first so no readings are missed between replay and live stream.
        LiveBleDataBus.getInstance().addObserver(bleObserver);
        // Replay the full session history so charts populate immediately on open.
        replayBusHistory();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LiveBleDataBus.getInstance().removeObserver(bleObserver); // critical: prevent post-destroy callbacks
        stopStreakPlayback();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Data discovery + layout
    // ═════════════════════════════════════════════════════════════════════════

    private void loadAndDisplayResults() {
        // ── 1. Resolve session folder ──
        List<File> sessions = SessionPathManager.listSessions(this);
        activeSessionDir = findLatestAnalysisSession(sessions);

        File sensorCsvFile = null;
        if (activeSessionDir != null) {
            File candidate = new File(
                    new File(activeSessionDir, SessionPathManager.PARAMETERS_SUBFOLDER),
                    "sensor_data.csv");
            if (candidate.exists()) sensorCsvFile = candidate;
        }

        lastAveragedVectors = activeSessionDir != null
                ? readVectorFile(new File(
                        new File(activeSessionDir, SessionPathManager.AVERAGED_DATA_SUBFOLDER),
                        "averaged_vectors.csv"))
                : new ArrayList<>();

        lastStreakFrames = activeSessionDir != null
                ? readStreakFrames(new File(activeSessionDir, SessionPathManager.RESULTS_SUBFOLDER))
                : new ArrayList<>();

        // ── 2. Build layout (live charts always appear, even when there are no files yet) ──
        containerLayout.removeAllViews();
        tvNoData.setVisibility(View.GONE);
        containerLayout.setVisibility(View.VISIBLE);

        buildLiveBleSection();                         // always first
        buildQuiverSection(lastAveragedVectors);
        buildStreakFlowSection(lastStreakFrames);

        // Show static historical charts only if a pre-existing CSV was found
        // (the live charts already cover the current live session).
        if (sensorCsvFile != null) {
            buildHistoricalBleSection(readSensorFile(sensorCsvFile), sensorCsvFile);
        }

        boolean hasAnyData = !lastAveragedVectors.isEmpty()
                || !lastStreakFrames.isEmpty()
                || LiveBleDataBus.getInstance().hasData()
                || sensorCsvFile != null;

        if (!hasAnyData) {
            tvNoData.setText("Connect the BLE sensor to see live charts, or run Analysis to see vector results.");
            tvNoData.setVisibility(View.VISIBLE);
        }

        btnDownloadAll.setEnabled(true); // always enable — user can download live BLE data at any time
    }

    private File findLatestAnalysisSession(List<File> sessions) {
        File best = null;
        for (File f : sessions) {
            if (f == null || !f.isDirectory()) continue;
            boolean hasAveraged = new File(
                    new File(f, SessionPathManager.AVERAGED_DATA_SUBFOLDER),
                    "averaged_vectors.csv").exists();
            File resultsSub = new File(f, SessionPathManager.RESULTS_SUBFOLDER);
            File[] frameFiles = resultsSub.listFiles(
                    (d, name) -> FRAME_FILE_PATTERN.matcher(name).matches());
            boolean hasFrames = frameFiles != null && frameFiles.length > 0;
            if (hasAveraged || hasFrames) {
                if (best == null || f.lastModified() > best.lastModified()) best = f;
            }
        }
        return best;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Section ①  Live BLE Charts
    // ═════════════════════════════════════════════════════════════════════════

    private void buildLiveBleSection() {
        addSectionTitle("BLE Sensor — Live Feed");

        // Status pill
        tvLiveStatus = new TextView(this);
        tvLiveStatus.setTextSize(13f);
        tvLiveStatus.setPadding(16, 0, 16, 12);
        containerLayout.addView(tvLiveStatus);
        updateLiveStatusLabel();

        // Three live charts
        chartCo2  = new LiveChartView(this, "CO2 Concentration", "ppm",
                Color.rgb(217, 84, 26));
        chartTemp = new LiveChartView(this, "Temperature", "°C",
                Color.rgb(0, 115, 189));
        chartHum  = new LiveChartView(this, "Humidity", "%RH",
                Color.rgb(120, 171, 48));

        containerLayout.addView(chartCo2);
        containerLayout.addView(chartTemp);
        containerLayout.addView(chartHum);

        // Help text
        addInfoText(
            "Charts update in real-time as the SCD41 sensor streams data.\n" +
            "They stay visible after the sensor disconnects — tap  ↓ Download All Results  " +
            "to save the charts as PNG images and the raw readings as a CSV.");
    }

    /** Appends one reading to all three live charts. Always called on the main thread. */
    private void appendLivePoint(LiveBleDataBus.BleReading r) {
        if (chartCo2  != null) chartCo2 .addPoint(r.elapsedSeconds, r.co2);
        if (chartTemp != null) chartTemp.addPoint(r.elapsedSeconds, r.temperature);
        if (chartHum  != null) chartHum .addPoint(r.elapsedSeconds, r.humidity);
        updateLiveStatusLabel();
    }

    /** Feeds the entire bus history into the charts without re-registering the observer. */
    private void replayBusHistory() {
        List<LiveBleDataBus.BleReading> history = LiveBleDataBus.getInstance().getSessionHistory();
        if (history.isEmpty()) { updateLiveStatusLabel(); return; }
        // Batch-add silently then do a single invalidate to avoid flooding the draw thread.
        for (LiveBleDataBus.BleReading r : history) {
            if (chartCo2  != null) chartCo2 .addPointSilent(r.elapsedSeconds, r.co2);
            if (chartTemp != null) chartTemp.addPointSilent(r.elapsedSeconds, r.temperature);
            if (chartHum  != null) chartHum .addPointSilent(r.elapsedSeconds, r.humidity);
        }
        if (chartCo2  != null) chartCo2 .invalidate();
        if (chartTemp != null) chartTemp.invalidate();
        if (chartHum  != null) chartHum .invalidate();
        updateLiveStatusLabel();
    }

    private void updateLiveStatusLabel() {
        if (tvLiveStatus == null) return;
        int count = LiveBleDataBus.getInstance().getReadingCount();
        if (count == 0) {
            tvLiveStatus.setText("○  Waiting for SCD41 sensor…");
            tvLiveStatus.setTextColor(Color.rgb(160, 160, 160));
        } else {
            // The observer is always re-added in onResume, so if we're receiving readings
            // the sensor is live; if the observer was removed (onPause) we won't be here.
            tvLiveStatus.setText("●  " + count + " readings recorded this session");
            tvLiveStatus.setTextColor(Color.rgb(76, 175, 80));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Section ②  Vector Quiver Field
    // ═════════════════════════════════════════════════════════════════════════

    private void buildQuiverSection(List<VectorData> vectors) {
        addSectionTitle("Averaged Vector Quiver Field");
        if (vectors.isEmpty()) {
            addInfoText("No averaged vector field found. Run batch analysis in the Analysis screen.");
            return;
        }
        VectorVisualizationView vv = new VectorVisualizationView(this, vectors);
        containerLayout.addView(vv);
        TextView tv = new TextView(this);
        tv.setText(String.format(Locale.US,
                "Grid Analysis: %d vectors  |  Velocity %.4f – %.4f m/s",
                vectors.size(), getMinMag(vectors), getMaxMag(vectors)));
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13);
        tv.setPadding(16, 12, 16, 24);
        containerLayout.addView(tv);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Section ③  Streak Vector Flow
    // ═════════════════════════════════════════════════════════════════════════

    private void buildStreakFlowSection(List<List<VectorData>> frames) {
        addSectionTitle("Streak Vector Flow");
        if (frames.isEmpty()) {
            addInfoText("No per-frame streak data found. Run batch analysis in the Analysis screen.");
            return;
        }
        StreakFlowView sv = new StreakFlowView(this, frames);
        containerLayout.addView(sv);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(16, 12, 16, 4);

        Button btnPlay = new Button(this);
        btnPlay.setText("▶  Play");

        TextView tvLbl = new TextView(this);
        tvLbl.setTextColor(Color.WHITE);
        tvLbl.setTextSize(13);
        tvLbl.setPadding(16, 0, 0, 0);
        tvLbl.setText("Frame 1 / " + frames.size());

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(Math.max(0, frames.size() - 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) { sv.setFrameIndex(p); tvLbl.setText("Frame "+(p+1)+" / "+frames.size()); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {
                stopStreakPlayback(); btnPlay.setText("▶  Play");
            }
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        btnPlay.setOnClickListener(v -> {
            if (isStreakPlaying) { stopStreakPlayback(); btnPlay.setText("▶  Play"); }
            else { startStreakPlayback(sv, seekBar, tvLbl, frames.size()); btnPlay.setText("⏸  Pause"); }
        });

        controls.addView(btnPlay);
        controls.addView(seekBar, lp);
        controls.addView(tvLbl);
        containerLayout.addView(controls);

        int total = 0; for (List<VectorData> f : frames) total += f.size();
        TextView tvInfo = new TextView(this);
        tvInfo.setText(String.format(Locale.US, "%d frames  |  %d streak vectors total", frames.size(), total));
        tvInfo.setTextColor(Color.argb(200, 200, 200, 200));
        tvInfo.setTextSize(13);
        tvInfo.setPadding(16, 4, 16, 24);
        containerLayout.addView(tvInfo);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Section ④  Historical BLE charts (from saved CSV, if present)
    // ═════════════════════════════════════════════════════════════════════════

    private void buildHistoricalBleSection(List<SensorReading> readings, File sourceFile) {
        if (readings.isEmpty()) return;
        addSectionTitle("BLE Sensor — Historical Log");
        TextView tvSrc = new TextView(this);
        tvSrc.setText("Loaded from: " + sourceFile.getName() + "  (" + readings.size() + " readings)");
        tvSrc.setTextColor(Color.argb(180, 160, 160, 160));
        tvSrc.setTextSize(11);
        tvSrc.setPadding(16, 0, 16, 8);
        containerLayout.addView(tvSrc);

        List<Float> elapsed = new ArrayList<>(), co2 = new ArrayList<>(),
                    temp = new ArrayList<>(), hum = new ArrayList<>();
        for (SensorReading r : readings) {
            elapsed.add(r.elapsedSeconds); co2.add((float)r.co2);
            temp.add(r.temperature); hum.add(r.humidity);
        }
        containerLayout.addView(new SensorChartView(this, elapsed, co2,
                "CO2 (historical)", "ppm", Color.rgb(217, 84, 26)));
        containerLayout.addView(new SensorChartView(this, elapsed, temp,
                "Temperature (historical)", "°C", Color.rgb(0, 115, 189)));
        containerLayout.addView(new SensorChartView(this, elapsed, hum,
                "Humidity (historical)", "%RH", Color.rgb(120, 171, 48)));

        TextView tvSum = new TextView(this);
        tvSum.setText(String.format(Locale.US,
                "CO2 %.0f–%.0f ppm  |  Temp %.1f–%.1f °C  |  Humidity %.1f–%.1f %%RH",
                minOf(co2), maxOf(co2), minOf(temp), maxOf(temp), minOf(hum), maxOf(hum)));
        tvSum.setTextColor(Color.WHITE);
        tvSum.setTextSize(13);
        tvSum.setPadding(16, 8, 16, 24);
        containerLayout.addView(tvSum);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Download All Results
    // ═════════════════════════════════════════════════════════════════════════

    private void downloadAllResults() {
        LiveBleDataBus bus = LiveBleDataBus.getInstance();
        boolean hasLiveData = bus.hasData();
        boolean hasSessionDir = activeSessionDir != null;

        if (!hasLiveData && !hasSessionDir) {
            Toast.makeText(this, "Nothing to download yet. Connect the sensor or run Analysis first.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        btnDownloadAll.setEnabled(false);
        Toast.makeText(this, "Preparing download…", Toast.LENGTH_SHORT).show();

        // Snapshot the live data NOW (before the background thread runs) so we capture
        // exactly what is on screen at the moment the user taps the button.
        List<LiveBleDataBus.BleReading> liveSnapshot = bus.getSessionHistory();

        // Session folder name for the download directory
        String sessionLabel = hasSessionDir ? activeSessionDir.getName()
                : "BLE_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String downloadFolder = sessionLabel + "_Results";

        // Render chart bitmaps on the main thread BEFORE handing off to background thread,
        // because View.draw() must always run on the thread that owns the view hierarchy.
        Bitmap bmpCo2  = renderViewToBitmap(chartCo2,  1080, 400);
        Bitmap bmpTemp = renderViewToBitmap(chartTemp, 1080, 400);
        Bitmap bmpHum  = renderViewToBitmap(chartHum,  1080, 400);

        new Thread(() -> {
            int fileCount = 0;
            boolean videoOk = false;
            StringBuilder report = new StringBuilder();
            try {
                // ── A. BLE Live Chart PNGs ───────────────────────────────────────
                if (bmpCo2  != null) { saveBitmapToDownloads(bmpCo2,  downloadFolder, "BLE_CO2_live.png");  fileCount++; }
                if (bmpTemp != null) { saveBitmapToDownloads(bmpTemp, downloadFolder, "BLE_Temperature_live.png"); fileCount++; }
                if (bmpHum  != null) { saveBitmapToDownloads(bmpHum,  downloadFolder, "BLE_Humidity_live.png"); fileCount++; }
                report.append(fileCount).append(" BLE chart image(s)");

                // ── B. BLE Raw CSV (built from the in-memory snapshot) ────────────
                if (!liveSnapshot.isEmpty()) {
                    StringBuilder csv = new StringBuilder("Elapsed(s),CO2(ppm),Temp(°C),Humidity(%RH)\n");
                    for (LiveBleDataBus.BleReading r : liveSnapshot) {
                        csv.append(String.format(Locale.US, "%.3f,%d,%.2f,%.2f\n",
                                r.elapsedSeconds, (int) r.co2, r.temperature, r.humidity));
                    }
                    try (OutputStream os = ExportStorageHelper.getDownloadsOutputStream(
                            this, downloadFolder, "BLE_sensor_data_live.csv")) {
                        os.write(csv.toString().getBytes());
                        fileCount++;
                        report.append(" + CSV (").append(liveSnapshot.size()).append(" readings)");
                    }
                }

                // ── C. Streak frame PNGs + averaged PNG (already on disk) ─────────
                if (hasSessionDir) {
                    File resultsDir      = new File(activeSessionDir, SessionPathManager.RESULTS_SUBFOLDER);
                    File averagedDataDir = new File(activeSessionDir, SessionPathManager.AVERAGED_DATA_SUBFOLDER);

                    int streakImages = 0;
                    streakImages += copyMatchingFiles(resultsDir,      n -> n.endsWith(".png"), downloadFolder);
                    streakImages += copyMatchingFiles(averagedDataDir, n -> n.endsWith(".png"), downloadFolder);
                    copyMatchingFiles(averagedDataDir, n -> n.endsWith(".xls") || n.endsWith(".csv"), downloadFolder);
                    copyMatchingFiles(resultsDir,      n -> n.endsWith(".csv"), downloadFolder);
                    fileCount += streakImages;
                    if (streakImages > 0) report.append(" + ").append(streakImages).append(" streak image(s)");

                    // ── D. Streak video (built from PNGs on disk) ─────────────────
                    File[] frameImages = resultsDir.listFiles((d, n) -> n.endsWith(".png"));
                    if (frameImages != null && frameImages.length > 1) {
                        Arrays.sort(frameImages, (a, b) -> a.getName().compareTo(b.getName()));
                        List<Bitmap> bitmaps = new ArrayList<>();
                        for (File f : frameImages) {
                            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
                            if (bmp != null) bitmaps.add(bmp);
                        }
                        if (bitmaps.size() > 1) {
                            try {
                                File tmpMp4 = new File(getCacheDir(),
                                        "streak_" + System.currentTimeMillis() + ".mp4");
                                StreakVideoExporter.export(bitmaps, tmpMp4);
                                try (OutputStream os = ExportStorageHelper.getDownloadsOutputStream(
                                             this, downloadFolder, "streak_vector_flow.mp4", "video/mp4");
                                     java.io.FileInputStream fis = new java.io.FileInputStream(tmpMp4)) {
                                    byte[] buf = new byte[8192]; int n;
                                    while ((n = fis.read(buf)) > 0) os.write(buf, 0, n);
                                }
                                //noinspection ResultOfMethodCallIgnored
                                tmpMp4.delete();
                                videoOk = true;
                                report.append(" + video");
                            } catch (Exception e) { Log.e(TAG, "Video export failed", e); }
                        }
                    }
                }

                final String msg = "Downloaded: " + report + "\n→ Downloads/" + downloadFolder;
                final boolean vid = videoOk;
                runOnUiThread(() -> {
                    btnDownloadAll.setEnabled(true);
                    Toast.makeText(ResultsActivity.this, msg, Toast.LENGTH_LONG).show();
                });

            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                runOnUiThread(() -> {
                    btnDownloadAll.setEnabled(true);
                    Toast.makeText(ResultsActivity.this, "Download error: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** Renders a View to an off-screen Bitmap. Must be called on the main thread. */
    private Bitmap renderViewToBitmap(View view, int width, int height) {
        if (view == null) return null;
        int w = view.getWidth()  > 0 ? view.getWidth()  : width;
        int h = view.getHeight() > 0 ? view.getHeight() : height;
        try {
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(bmp));
            return bmp;
        } catch (Exception e) { Log.e(TAG, "renderViewToBitmap failed", e); return null; }
    }

    private void saveBitmapToDownloads(Bitmap bmp, String folder, String filename) throws IOException {
        try (OutputStream os = ExportStorageHelper.getDownloadsOutputStream(this, folder, filename, "image/png")) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
    }

    private interface NameFilter { boolean matches(String name); }

    private int copyMatchingFiles(File sourceDir, NameFilter filter, String downloadFolder) {
        int count = 0;
        if (sourceDir == null || !sourceDir.exists()) return 0;
        File[] files = sourceDir.listFiles((d, name) -> filter.matches(name.toLowerCase(Locale.US)));
        if (files == null) return 0;
        for (File f : files) {
            try (OutputStream os = ExportStorageHelper.getDownloadsOutputStream(
                         this, downloadFolder, f.getName());
                 java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = fis.read(buf)) > 0) os.write(buf, 0, n);
                count++;
            } catch (IOException e) { Log.w(TAG, "Copy failed: " + f.getName(), e); }
        }
        return count;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Streak playback helpers
    // ═════════════════════════════════════════════════════════════════════════

    private void startStreakPlayback(StreakFlowView v, SeekBar sb, TextView lbl, int count) {
        if (count <= 1) return;
        isStreakPlaying = true;
        streakPlaybackRunnable = new Runnable() {
            @Override public void run() {
                if (!isStreakPlaying) return;
                int next = (sb.getProgress() + 1) % count;
                sb.setProgress(next);
                v.setFrameIndex(next);
                lbl.setText("Frame " + (next + 1) + " / " + count);
                mainHandler.postDelayed(this, 150);
            }
        };
        mainHandler.postDelayed(streakPlaybackRunnable, 150);
    }

    private void stopStreakPlayback() {
        isStreakPlaying = false;
        if (streakPlaybackRunnable != null) {
            mainHandler.removeCallbacks(streakPlaybackRunnable);
            streakPlaybackRunnable = null;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Layout helpers
    // ═════════════════════════════════════════════════════════════════════════

    private void addSectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextColor(Color.WHITE); tv.setTextSize(18);
        tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        tv.setPadding(16, 32, 16, 8);
        containerLayout.addView(tv);
    }

    private void addInfoText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextColor(Color.argb(200, 190, 190, 190));
        tv.setTextSize(13); tv.setPadding(16, 0, 16, 24);
        containerLayout.addView(tv);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // File parsing
    // ═════════════════════════════════════════════════════════════════════════

    private List<VectorData> readVectorFile(File file) {
        List<VectorData> out = new ArrayList<>();
        if (file == null || !file.exists()) return out;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            br.readLine(); // header
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] p = line.split(",");
                if (p.length < 6) continue;
                try {
                    int count = p.length >= 7 ? Integer.parseInt(p[6].trim()) : 1;
                    out.add(new VectorData(
                            Float.parseFloat(p[0].trim()), Float.parseFloat(p[1].trim()),
                            Float.parseFloat(p[2].trim()), Float.parseFloat(p[3].trim()),
                            Float.parseFloat(p[4].trim()), Float.parseFloat(p[5].trim()), count));
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException e) { Log.e(TAG, "readVectorFile", e); }
        return out;
    }

    private List<List<VectorData>> readStreakFrames(File dir) {
        List<List<VectorData>> out = new ArrayList<>();
        if (dir == null || !dir.exists()) return out;
        File[] files = dir.listFiles((d, n) -> FRAME_FILE_PATTERN.matcher(n).matches());
        if (files == null || files.length == 0) return out;
        Arrays.sort(files, (a, b) -> Integer.compare(extractFrameNum(a.getName()), extractFrameNum(b.getName())));
        for (File f : files) {
            List<VectorData> v = readVectorFile(f);
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    private int extractFrameNum(String name) {
        Matcher m = FRAME_SEQUENCE_PATTERN.matcher(name);
        if (m.find()) { try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {} }
        return 0;
    }

    private List<SensorReading> readSensorFile(File file) {
        List<SensorReading> out = new ArrayList<>();
        if (file == null || !file.exists()) return out;
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
        long firstMs = -1, prevRaw = -1, dayOff = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] p = line.split(",");
                if (p.length < 4) continue;
                try {
                    String lbl = p[0].trim();
                    int    co2 = Integer.parseInt(p[1].trim());
                    float  tmp = Float.parseFloat(p[2].trim());
                    float  hum = Float.parseFloat(p[3].trim());
                    Date   d   = fmt.parse(lbl);
                    if (d == null) continue;
                    long raw = d.getTime();
                    if (prevRaw >= 0 && raw < prevRaw) dayOff += 86_400_000L;
                    long adj = raw + dayOff;
                    if (firstMs < 0) firstMs = adj;
                    prevRaw = raw;
                    out.add(new SensorReading(lbl, (adj - firstMs) / 1000f, co2, tmp, hum));
                } catch (NumberFormatException | ParseException ignored) {}
            }
        } catch (IOException e) { Log.e(TAG, "readSensorFile", e); }
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Math helpers
    // ═════════════════════════════════════════════════════════════════════════

    private float getMinMag(List<VectorData> v) { float m = Float.MAX_VALUE; for (VectorData d : v) m = Math.min(m, d.magnitude); return m == Float.MAX_VALUE ? 0 : m; }
    private float getMaxMag(List<VectorData> v) { float m = -Float.MAX_VALUE; for (VectorData d : v) m = Math.max(m, d.magnitude); return m == -Float.MAX_VALUE ? 1 : m; }
    private float minOf(List<Float> l) { float m = Float.MAX_VALUE; for (float v : l) m = Math.min(m, v); return m; }
    private float maxOf(List<Float> l) { float m = -Float.MAX_VALUE; for (float v : l) m = Math.max(m, v); return m; }

    // ═════════════════════════════════════════════════════════════════════════
    // Data models
    // ═════════════════════════════════════════════════════════════════════════

    public static class VectorData {
        float x, y, u, v, magnitude, angle; int count;
        public VectorData(float x, float y, float u, float v, float mag, float ang, int cnt) {
            this.x=x; this.y=y; this.u=u; this.v=v; magnitude=mag; angle=ang; count=cnt;
        }
    }

    public static class SensorReading {
        String timeLabel; float elapsedSeconds; int co2; float temperature, humidity;
        public SensorReading(String l, float e, int c, float t, float h) {
            timeLabel=l; elapsedSeconds=e; co2=c; temperature=t; humidity=h;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // LiveChartView  ── real-time scrolling line chart
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Lightweight custom View for real-time BLE data.  Unlike SensorChartView
     * (which takes a fixed snapshot list), LiveChartView maintains its own growing
     * point list so it can be updated incrementally without rebuilding the chart.
     *
     * Key behaviours:
     *  • addPoint()       – appends a point AND calls invalidate() (call from main thread)
     *  • addPointSilent() – appends WITHOUT invalidating (use during bulk replay; caller
     *                       calls invalidate() once at the end)
     *  • X-axis           – auto-scales to the latest elapsed time so the line always fills
     *                       the full width (scrolling window effect)
     *  • Y-axis           – auto-scales to [min-5%, max+5%] of all visible data
     *  • "Last value" dot + label always visible at the right edge
     *  • "● LIVE" badge pulsing green while data is flowing; switches to grey "⏹ Ended"
     *    the moment the observer removes itself (activity paused or sensor gone)
     */
    public static class LiveChartView extends View {

        private static final int MAX_VISIBLE_POINTS = 300; // rolling window

        private final String title;
        private final String unit;

        // Parallel lists — index-matched, always appended together
        private final List<Float> xData = new ArrayList<>(); // elapsed seconds
        private final List<Float> yData = new ArrayList<>(); // measurement value

        private float yMin = Float.MAX_VALUE, yMax = -Float.MAX_VALUE;
        private float xMax = 0f;

        private final Paint paintLine, paintGrid, paintText, paintAxis, paintDot,
                            paintBg, paintBadge, paintBadgeText;

        public LiveChartView(Context ctx, String title, String unit, int lineColor) {
            super(ctx);
            this.title = title;
            this.unit  = unit;
            setWillNotDraw(false);

            paintBg = new Paint();
            paintBg.setColor(Color.argb(255, 20, 20, 20));

            paintLine = new Paint(Paint.ANTI_ALIAS_FLAG);
            paintLine.setColor(lineColor);
            paintLine.setStrokeWidth(3.5f);
            paintLine.setStyle(Paint.Style.STROKE);
            paintLine.setStrokeJoin(Paint.Join.ROUND);
            paintLine.setStrokeCap(Paint.Cap.ROUND);

            paintGrid = new Paint();
            paintGrid.setColor(Color.argb(35, 255, 255, 255));
            paintGrid.setStrokeWidth(1f);

            paintText = new Paint(Paint.ANTI_ALIAS_FLAG);
            paintText.setColor(Color.WHITE);
            paintText.setTextSize(11f);

            paintAxis = new Paint();
            paintAxis.setColor(Color.argb(140, 255, 255, 255));
            paintAxis.setStrokeWidth(2f);

            paintDot = new Paint(Paint.ANTI_ALIAS_FLAG);
            paintDot.setColor(lineColor);
            paintDot.setStyle(Paint.Style.FILL);

            paintBadge = new Paint(Paint.ANTI_ALIAS_FLAG);
            paintBadge.setStyle(Paint.Style.FILL);

            paintBadgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
            paintBadgeText.setColor(Color.WHITE);
            paintBadgeText.setTextSize(10f);
            paintBadgeText.setFakeBoldText(true);
        }

        /** Add a point and redraw. Call only from the main thread. */
        public void addPoint(float xSeconds, float yValue) {
            addPointSilent(xSeconds, yValue);
            invalidate();
        }

        /** Add a point WITHOUT redrawing. For bulk history replay. */
        public void addPointSilent(float xSeconds, float yValue) {
            // Rolling window: drop oldest point when over the limit
            if (xData.size() >= MAX_VISIBLE_POINTS) {
                xData.remove(0);
                yData.remove(0);
            }
            xData.add(xSeconds);
            yData.add(yValue);
            if (yValue < yMin) yMin = yValue;
            if (yValue > yMax) yMax = yValue;
            if (xSeconds > xMax) xMax = xSeconds;
        }

        /** Wipes all data (called on session reset). Call from main thread. */
        public void clear() {
            xData.clear(); yData.clear();
            yMin = Float.MAX_VALUE; yMax = -Float.MAX_VALUE; xMax = 0f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int W = getWidth(), H = getHeight();
            canvas.drawRect(0, 0, W, H, paintBg);

            final float LM = 62f, RM = 16f, TM = 44f, BM = 32f;
            final float PW = W - LM - RM, PH = H - TM - BM;

            // ── Title ──────────────────────────────────────────────────────
            paintText.setTextSize(14f);
            paintText.setFakeBoldText(true);
            canvas.drawText(title + "  (" + unit + ")", LM, 24f, paintText);
            paintText.setFakeBoldText(false);

            int n = xData.size();

            // ── Empty state ────────────────────────────────────────────────
            if (n == 0) {
                paintText.setTextSize(12f);
                paintText.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("Waiting for sensor data…", LM + PW / 2f, TM + PH / 2f, paintText);
                paintText.setTextAlign(Paint.Align.LEFT);
                drawStatusBadge(canvas, W, TM, false, 0);
                return;
            }

            // ── Y range ────────────────────────────────────────────────────
            float lo = yMin, hi = yMax;
            if (hi == lo) { hi = lo + 1f; }
            float pad = (hi - lo) * 0.08f;
            float displayYMin = lo - pad, displayYMax = hi + pad;

            // ── Grid + Y-axis labels ───────────────────────────────────────
            for (int i = 0; i <= 4; i++) {
                float gy = TM + PH * i / 4f;
                canvas.drawLine(LM, gy, LM + PW, gy, paintGrid);
                float val = displayYMax - (displayYMax - displayYMin) * i / 4f;
                paintText.setTextSize(10f);
                canvas.drawText(String.format(Locale.US, "%.1f", val), 2f, gy + 4f, paintText);
            }

            // ── Axes ───────────────────────────────────────────────────────
            canvas.drawLine(LM, TM, LM, TM + PH, paintAxis);
            canvas.drawLine(LM, TM + PH, LM + PW, TM + PH, paintAxis);

            // ── Line path ─────────────────────────────────────────────────
            float xRange = xMax > 0 ? xMax : 1f;
            Path path = new Path();
            for (int i = 0; i < n; i++) {
                float px = LM + PW * (xData.get(i) / xRange);
                float py = TM + PH * (1f - (yData.get(i) - displayYMin) / (displayYMax - displayYMin));
                if (i == 0) path.moveTo(px, py);
                else        path.lineTo(px, py);
            }
            canvas.drawPath(path, paintLine);

            // ── Latest-value dot + label ───────────────────────────────────
            float lastX = LM + PW;
            float lastY = TM + PH * (1f - (yData.get(n-1) - displayYMin) / (displayYMax - displayYMin));
            canvas.drawCircle(lastX, lastY, 5.5f, paintDot);
            paintText.setTextSize(12f);
            paintText.setTextAlign(Paint.Align.RIGHT);
            paintText.setFakeBoldText(true);
            canvas.drawText(String.format(Locale.US, "%.1f %s", yData.get(n-1), unit),
                    lastX - 8f, lastY - 8f, paintText);
            paintText.setFakeBoldText(false);
            paintText.setTextAlign(Paint.Align.LEFT);

            // ── X-axis time labels ────────────────────────────────────────
            paintText.setTextSize(10f);
            canvas.drawText("0 s", LM, TM + PH + 20f, paintText);
            canvas.drawText(String.format(Locale.US, "%.0f s", xMax), LM + PW - 36f, TM + PH + 20f, paintText);
            canvas.drawText(n + " readings", LM + PW / 2f - 20f, TM + PH + 20f, paintText);

            // ── LIVE badge (top-right) ─────────────────────────────────────
            drawStatusBadge(canvas, W, TM, true, n);
        }

        /** Draws a small "● LIVE  N pts" or "⏹ N pts" badge in the top-right corner. */
        private void drawStatusBadge(Canvas canvas, int W, float TM, boolean hasData, int n) {
            String badgeText = hasData ? ("● LIVE  " + n + " pts") : "○ No data";
            int badgeColor   = hasData ? Color.argb(200, 30, 120, 30) : Color.argb(150, 60, 60, 60);
            paintBadgeText.setTextSize(10f);
            float textW = paintBadgeText.measureText(badgeText);
            float bx = W - textW - 20f, by = 6f, bw = textW + 14f, bh = 20f;
            paintBadge.setColor(badgeColor);
            canvas.drawRoundRect(new RectF(bx, by, bx + bw, by + bh), 6f, 6f, paintBadge);
            canvas.drawText(badgeText, bx + 7f, by + 14f, paintBadgeText);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(MeasureSpec.getSize(wSpec), 300);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // VectorVisualizationView  (averaged quiver field, pinch/pan)
    // ═════════════════════════════════════════════════════════════════════════

    public static class VectorVisualizationView extends View {
        private final List<VectorData> vectors;
        private final Paint arrowPaint, gridPaint, textPaint;
        private final float minMag, maxMag, scale = 2f;
        private float zoomScale = 1f, translateX = 0f, translateY = 0f;
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector panDetector;

        public VectorVisualizationView(ResultsActivity ctx, List<VectorData> v) {
            super(ctx);
            vectors = v;
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (VectorData d : v) { lo = Math.min(lo,d.magnitude); hi = Math.max(hi,d.magnitude); }
            minMag = lo > hi ? 0 : lo; maxMag = hi == lo ? lo + 0.001f : hi;

            arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG); arrowPaint.setStrokeWidth(3f); arrowPaint.setStrokeCap(Paint.Cap.ROUND);
            gridPaint  = new Paint(); gridPaint.setColor(Color.argb(50,255,255,255)); gridPaint.setStrokeWidth(1f);
            textPaint  = new Paint(Paint.ANTI_ALIAS_FLAG); textPaint.setColor(Color.WHITE); textPaint.setTextSize(12f);

            scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    zoomScale = Math.max(0.5f, Math.min(4f, zoomScale * d.getScaleFactor())); invalidate(); return true;
                }
            });
            panDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                    if (!scaleDetector.isInProgress()) { translateX -= dx; translateY -= dy; invalidate(); } return true;
                }
            });
        }

        @Override public boolean onTouchEvent(MotionEvent e) { scaleDetector.onTouchEvent(e); panDetector.onTouchEvent(e); return true; }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.argb(255, 30, 30, 30));
            int W = getWidth(), H = getHeight();
            canvas.save(); canvas.translate(translateX, translateY); canvas.scale(zoomScale, zoomScale, W/2f, H/2f);
            for (int i=0;i<W;i+=50) canvas.drawLine(i,0,i,H,gridPaint);
            for (int i=0;i<H;i+=50) canvas.drawLine(0,i,W,i,gridPaint);
            if (vectors.isEmpty()) { canvas.drawText("No vectors",20,50,textPaint); canvas.restore(); return; }
            for (VectorData vec : vectors) {
                float sx=vec.x*scale, sy=vec.y*scale;
                int col = jetColor(vec.magnitude, minMag, maxMag); arrowPaint.setColor(col);
                float as = Math.max(2f, 30*(vec.magnitude-minMag)/(maxMag-minMag+0.001f));
                float ex = sx+(float)(Math.cos(Math.toRadians(vec.angle))*as);
                float ey = sy-(float)(Math.sin(Math.toRadians(vec.angle))*as);
                canvas.drawLine(sx,sy,ex,ey,arrowPaint);
                drawHead(canvas,sx,sy,ex,ey,col);
            }
            drawColorBar(canvas,W-40,20,20,150); canvas.restore();
        }
        private void drawHead(Canvas c,float x1,float y1,float x2,float y2,int col) {
            float hl=8f,a=(float)Math.atan2(y2-y1,x2-x1); Paint p=new Paint(); p.setColor(col);
            c.drawLine(x2,y2,(float)(x2-hl*Math.cos(a-Math.PI/6)),(float)(y2-hl*Math.sin(a-Math.PI/6)),p);
            c.drawLine(x2,y2,(float)(x2-hl*Math.cos(a+Math.PI/6)),(float)(y2-hl*Math.sin(a+Math.PI/6)),p);
        }
        private void drawColorBar(Canvas c,float x,float y,float w,float h) {
            for (int i=0;i<100;i++) { Paint p=new Paint(); p.setColor(jetColor(minMag+i/100f*(maxMag-minMag),minMag,maxMag)); c.drawRect(x,y+i*h/100,x+w,y+(i+1)*h/100,p); }
            textPaint.setTextSize(10f);
            c.drawText(String.format(Locale.US,"%.3f",maxMag),x-30,y-5,textPaint);
            c.drawText(String.format(Locale.US,"%.3f",minMag),x-30,y+h+15,textPaint);
        }
        private int jetColor(float mag,float lo,float hi) {
            float n=Math.max(0,Math.min(1,(mag-lo)/(hi-lo+0.0001f))); int r,g,b;
            if(n<0.125f){r=0;g=0;b=(int)(255*(n/0.125f*0.5f+0.5f));}
            else if(n<0.375f){r=0;g=(int)(255*(n-0.125f)/0.25f);b=255;}
            else if(n<0.625f){r=(int)(255*(n-0.375f)/0.25f);g=255;b=(int)(255*(1-(n-0.375f)/0.25f));}
            else if(n<0.875f){r=255;g=(int)(255*(1-(n-0.625f)/0.25f));b=0;}
            else{r=(int)(255*(1-(n-0.875f)/0.125f));g=0;b=0;}
            return Color.rgb(Math.max(0,Math.min(255,r)),Math.max(0,Math.min(255,g)),Math.max(0,Math.min(255,b)));
        }
        @Override protected void onMeasure(int ws,int hs){ setMeasuredDimension(MeasureSpec.getSize(ws),600); }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // StreakFlowView  (frame-by-frame playback, fading trail)
    // ═════════════════════════════════════════════════════════════════════════

    public static class StreakFlowView extends View {
        private static final int TRAIL = 5;
        private final List<List<VectorData>> frames;
        private int frameIndex = 0;
        private final Paint arrowPaint, gridPaint, textPaint;
        private final float minMag, maxMag, scale = 2f;
        private float zoomScale=1f, tx=0f, ty=0f;
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector panDetector;

        public StreakFlowView(ResultsActivity ctx, List<List<VectorData>> frames) {
            super(ctx); this.frames = frames;
            float lo=Float.MAX_VALUE,hi=-Float.MAX_VALUE;
            for (List<VectorData> fr:frames) for (VectorData v:fr) { lo=Math.min(lo,v.magnitude); hi=Math.max(hi,v.magnitude); }
            if (lo>hi){lo=0;hi=1;} if (hi==lo) hi=lo+0.001f; minMag=lo; maxMag=hi;
            arrowPaint=new Paint(Paint.ANTI_ALIAS_FLAG); arrowPaint.setStrokeWidth(3f); arrowPaint.setStrokeCap(Paint.Cap.ROUND);
            gridPaint=new Paint(); gridPaint.setColor(Color.argb(50,255,255,255)); gridPaint.setStrokeWidth(1f);
            textPaint=new Paint(Paint.ANTI_ALIAS_FLAG); textPaint.setColor(Color.WHITE); textPaint.setTextSize(12f);
            scaleDetector=new ScaleGestureDetector(ctx,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                @Override public boolean onScale(ScaleGestureDetector d){zoomScale=Math.max(0.5f,Math.min(4f,zoomScale*d.getScaleFactor()));invalidate();return true;}
            });
            panDetector=new GestureDetector(ctx,new GestureDetector.SimpleOnGestureListener(){
                @Override public boolean onScroll(MotionEvent e1,MotionEvent e2,float dx,float dy){if(!scaleDetector.isInProgress()){tx-=dx;ty-=dy;invalidate();}return true;}
            });
        }
        @Override public boolean onTouchEvent(MotionEvent e){scaleDetector.onTouchEvent(e);panDetector.onTouchEvent(e);return true;}
        public void setFrameIndex(int i){if(i>=0&&i<frames.size()){frameIndex=i;invalidate();}}
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.argb(255,30,30,30));
            int W=getWidth(),H=getHeight();
            canvas.save(); canvas.translate(tx,ty); canvas.scale(zoomScale,zoomScale,W/2f,H/2f);
            for(int i=0;i<W;i+=50) canvas.drawLine(i,0,i,H,gridPaint);
            for(int i=0;i<H;i+=50) canvas.drawLine(0,i,W,i,gridPaint);
            if(!frames.isEmpty()){
                int sf=Math.max(0,frameIndex-TRAIL+1),span=Math.max(1,frameIndex-sf);
                for(int fi=sf;fi<=frameIndex;fi++){
                    float tp=(fi==frameIndex)?1f:(float)(fi-sf)/span;
                    drawFrameVectors(canvas,frames.get(fi),(int)(80+tp*175));
                }
            }
            textPaint.setTextSize(13f); canvas.drawText("Frame "+(frameIndex+1)+" / "+frames.size(),16,H-16,textPaint);
            canvas.restore();
        }
        private void drawFrameVectors(Canvas c,List<VectorData> vecs,int alpha){
            for(VectorData v:vecs){
                float sx=v.x*scale,sy=v.y*scale;
                int base=jetColor(v.magnitude),col=Color.argb(alpha,Color.red(base),Color.green(base),Color.blue(base));
                arrowPaint.setColor(col);
                float as=Math.max(2f,30*(v.magnitude-minMag)/(maxMag-minMag+0.001f));
                float ex=sx+(float)(Math.cos(Math.toRadians(v.angle))*as);
                float ey=sy-(float)(Math.sin(Math.toRadians(v.angle))*as);
                c.drawLine(sx,sy,ex,ey,arrowPaint);
                Paint p=new Paint(); p.setColor(col); float hl=8f,a=(float)Math.atan2(ey-sy,ex-sx);
                c.drawLine(ex,ey,(float)(ex-hl*Math.cos(a-Math.PI/6)),(float)(ey-hl*Math.sin(a-Math.PI/6)),p);
                c.drawLine(ex,ey,(float)(ex-hl*Math.cos(a+Math.PI/6)),(float)(ey-hl*Math.sin(a+Math.PI/6)),p);
            }
        }
        private int jetColor(float mag){
            float n=Math.max(0,Math.min(1,(mag-minMag)/(maxMag-minMag+0.0001f))); int r,g,b;
            if(n<0.125f){r=0;g=0;b=(int)(255*(n/0.125f*0.5f+0.5f));}
            else if(n<0.375f){r=0;g=(int)(255*(n-0.125f)/0.25f);b=255;}
            else if(n<0.625f){r=(int)(255*(n-0.375f)/0.25f);g=255;b=(int)(255*(1-(n-0.375f)/0.25f));}
            else if(n<0.875f){r=255;g=(int)(255*(1-(n-0.625f)/0.25f));b=0;}
            else{r=(int)(255*(1-(n-0.875f)/0.125f));g=0;b=0;}
            return Color.rgb(Math.max(0,Math.min(255,r)),Math.max(0,Math.min(255,g)),Math.max(0,Math.min(255,b)));
        }
        @Override protected void onMeasure(int ws,int hs){setMeasuredDimension(MeasureSpec.getSize(ws),600);}
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SensorChartView  (static historical chart, pinch/pan)
    // ═════════════════════════════════════════════════════════════════════════

    public static class SensorChartView extends View {
        private final List<Float> xV,yV; private final String title,unit;
        private final Paint linePaint,gridPaint,textPaint,axisPaint;
        private final float minY,maxY,maxX;
        private float zoomScale=1f,tx=0f,ty=0f;
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector panDetector;

        public SensorChartView(ResultsActivity ctx,List<Float> xVals,List<Float> yVals,String title,String unit,int lineColor){
            super(ctx); xV=xVals; yV=yVals; this.title=title; this.unit=unit;
            float lo=Float.MAX_VALUE,hi=-Float.MAX_VALUE; for(float v:yVals){lo=Math.min(lo,v);hi=Math.max(hi,v);}
            if(lo>hi){lo=0;hi=1;} if(hi==lo){hi=lo+1;} float pad=(hi-lo)*0.1f; minY=lo-pad; maxY=hi+pad;
            float mx=1f; for(float v:xVals) mx=Math.max(mx,v); maxX=mx;
            linePaint=new Paint(Paint.ANTI_ALIAS_FLAG); linePaint.setColor(lineColor); linePaint.setStrokeWidth(4f); linePaint.setStyle(Paint.Style.STROKE);
            gridPaint=new Paint(); gridPaint.setColor(Color.argb(60,255,255,255)); gridPaint.setStrokeWidth(1f);
            textPaint=new Paint(Paint.ANTI_ALIAS_FLAG); textPaint.setColor(Color.WHITE); textPaint.setTextSize(13f);
            axisPaint=new Paint(); axisPaint.setColor(Color.argb(160,255,255,255)); axisPaint.setStrokeWidth(2f);
            scaleDetector=new ScaleGestureDetector(ctx,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                @Override public boolean onScale(ScaleGestureDetector d){zoomScale=Math.max(0.5f,Math.min(4f,zoomScale*d.getScaleFactor()));invalidate();return true;}
            });
            panDetector=new GestureDetector(ctx,new GestureDetector.SimpleOnGestureListener(){
                @Override public boolean onScroll(MotionEvent e1,MotionEvent e2,float dx,float dy){if(!scaleDetector.isInProgress()){tx-=dx;ty-=dy;invalidate();}return true;}
            });
        }
        @Override public boolean onTouchEvent(MotionEvent e){scaleDetector.onTouchEvent(e);panDetector.onTouchEvent(e);return true;}
        @Override protected void onDraw(Canvas canvas){
            canvas.drawColor(Color.argb(255,24,24,24));
            int W=getWidth(),H=getHeight();
            canvas.save(); canvas.translate(tx,ty); canvas.scale(zoomScale,zoomScale,W/2f,H/2f);
            float LM=60f,RM=20f,TM=40f,BM=36f,PW=W-LM-RM,PH=H-TM-BM;
            textPaint.setTextSize(15f); textPaint.setFakeBoldText(true);
            canvas.drawText(title+" ("+unit+")",LM,22,textPaint); textPaint.setFakeBoldText(false);
            for(int i=0;i<=4;i++){float gy=TM+PH*i/4f; canvas.drawLine(LM,gy,LM+PW,gy,gridPaint); textPaint.setTextSize(11f); canvas.drawText(String.format(Locale.US,"%.1f",maxY-(maxY-minY)*i/4f),4,gy+4,textPaint);}
            canvas.drawLine(LM,TM,LM,TM+PH,axisPaint); canvas.drawLine(LM,TM+PH,LM+PW,TM+PH,axisPaint);
            if(yV.size()>=2){Path path=new Path(); for(int i=0;i<yV.size();i++){float px=LM+PW*(xV.get(i)/maxX),py=TM+PH*(1f-(yV.get(i)-minY)/(maxY-minY)); if(i==0)path.moveTo(px,py); else path.lineTo(px,py);} canvas.drawPath(path,linePaint);}
            textPaint.setTextSize(11f); canvas.drawText("0s",LM,TM+PH+20,textPaint); canvas.drawText(String.format(Locale.US,"%.0fs",maxX),LM+PW-40,TM+PH+20,textPaint);
            canvas.restore();
        }
        @Override protected void onMeasure(int ws,int hs){setMeasuredDimension(MeasureSpec.getSize(ws),380);}
    }
}
