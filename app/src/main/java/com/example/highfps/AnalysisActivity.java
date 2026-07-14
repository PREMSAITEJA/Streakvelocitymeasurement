package com.example.highfps;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AnalysisActivity extends AppCompatActivity {
    private static final String TAG = "SmartPIV_Analysis";

    private ImageView ivOriginal, ivEnhanced, ivThreshold, ivFinal;
    private TextView tvFrameIdx, lblThresh, lblNoise, lblArea, lblClahe, lblFrameRange;
    private SeekBar sbThresh, sbNoise, sbArea, sbClahe, sbRangeStart, sbRangeEnd;
    private CheckBox cbEnableThresh, cbEnableNoise, cbEnableArea, cbEnableClahe;
    private Button btnPrev, btnNext, btnAnalyzeAll, btnExport;

    private int paramThresh = 128;
    private int paramNoise = 2;
    private int paramMinArea = 50;
    private int paramClaheTile = 64;

    // Per-filter enable checkboxes (modification: matches the MATLAB analyzeStreaksMulti()
    // script's cb_thresh/cb_noise/cb_area/cb_clahe checkboxes exactly). When unchecked, each
    // stage falls back to the same default the MATLAB script uses instead of being skipped
    // outright, since thresholding is required to produce a binary mask at all.
    private boolean useThresh = true;
    private boolean useNoise = true;
    private boolean useArea = true;
    private boolean useClahe = true;

    // Custom frame-range selection (modification #2): out of all captured frames, only
    // [frameRangeStart, frameRangeEnd] (inclusive) are used for batch analysis / export.
    private int frameRangeStart = 0;
    private int frameRangeEnd = 0;

    private String sessionName;
    private File sessionDir;
    private final List<File> frameFiles = new ArrayList<>();
    private int currentFrameIndex = 0;
    private boolean containsAnalyzedData = false;

    private final ExecutorService pipelineExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static class StreakVector {
        double x, y, u, v, magnitude, angle;
        StreakVector(double x, double y, double u, double v, double magnitude, double angle) {
            this.x = x; this.y = y; this.u = u; this.v = v;
            this.magnitude = magnitude; this.angle = angle;
        }
    }
    // One list of vectors per analyzed frame (index-aligned to the SELECTED sub-range,
    // not the full frameFiles list).
    private final List<List<StreakVector>> globalSessionVectors = new ArrayList<>();
    private final List<File> analyzedFrameFiles = new ArrayList<>();
    private int imgWidth = 0, imgHeight = 0;

    // Calibration inputs (modification: popup shown before the threshold controls), same
    // three values the MATLAB analyzeStreaksMulti() script asks for via inputdlg().
    private double paramScalePxPerMm = 10.0;
    private double paramExposureSeconds = 0.001;
    private int paramGridPx = 32;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_analysis);

        System.loadLibrary("opencv_java4");

        initUIReferences();
        setupInputControls();
        showCalibrationDialog();

        String pathPath = getIntent().getStringExtra("SESSION_PATH");
        if (pathPath != null) {
            sessionDir = new File(pathPath);
            sessionName = sessionDir.getName();
            loadSessionFiles();
        }
    }

    /**
     * Popup shown before the threshold controls, asking for the same three calibration
     * values the MATLAB analyzeStreaksMulti() script prompts for: scale (px/mm), exposure
     * time (seconds), and the grid cell size used to bin/average vectors.
     */
    private void showCalibrationDialog() {
        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        dialogLayout.setPadding(pad, pad, pad, 0);

        TextView lblScale = new TextView(this);
        lblScale.setText("Scale (pixels per mm):");
        EditText inputScale = new EditText(this);
        inputScale.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        inputScale.setText(String.valueOf(paramScalePxPerMm));

        TextView lblExposure = new TextView(this);
        lblExposure.setText("Exposure time (seconds):");
        EditText inputExposure = new EditText(this);
        inputExposure.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        inputExposure.setText(String.valueOf(paramExposureSeconds));

        TextView lblGrid = new TextView(this);
        lblGrid.setText("Grid cell size for analysis (pixels):");
        EditText inputGrid = new EditText(this);
        inputGrid.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        inputGrid.setText(String.valueOf(paramGridPx));

        dialogLayout.addView(lblScale);
        dialogLayout.addView(inputScale);
        dialogLayout.addView(lblExposure);
        dialogLayout.addView(inputExposure);
        dialogLayout.addView(lblGrid);
        dialogLayout.addView(inputGrid);

        new android.app.AlertDialog.Builder(this)
                .setTitle("Analysis Calibration")
                .setView(dialogLayout)
                .setCancelable(false)
                .setPositiveButton("OK", (dialog, which) -> {
                    try {
                        paramScalePxPerMm = Double.parseDouble(inputScale.getText().toString().trim());
                    } catch (NumberFormatException ignored) { }
                    try {
                        paramExposureSeconds = Double.parseDouble(inputExposure.getText().toString().trim());
                    } catch (NumberFormatException ignored) { }
                    try {
                        paramGridPx = Integer.parseInt(inputGrid.getText().toString().trim());
                    } catch (NumberFormatException ignored) { }
                })
                .show();
    }

    private void initUIReferences() {
        ivOriginal = findViewById(R.id.ivStageOriginal);
        ivEnhanced = findViewById(R.id.ivStageEnhanced);
        ivThreshold = findViewById(R.id.ivStageThreshold);
        ivFinal = findViewById(R.id.ivStageFinal);
        tvFrameIdx = findViewById(R.id.tvAnalysisFrameIdx);

        lblThresh = findViewById(R.id.lblSliderThresh);
        lblNoise = findViewById(R.id.lblSliderNoise);
        lblArea = findViewById(R.id.lblSliderArea);
        lblClahe = findViewById(R.id.lblSliderClahe);
        lblFrameRange = findViewById(R.id.lblFrameRange);

        sbThresh = findViewById(R.id.sbSliderThresh);
        sbNoise = findViewById(R.id.sbSliderNoise);
        sbArea = findViewById(R.id.sbSliderArea);
        sbClahe = findViewById(R.id.sbSliderClahe);
        sbRangeStart = findViewById(R.id.sbRangeStart);
        sbRangeEnd = findViewById(R.id.sbRangeEnd);

        cbEnableThresh = findViewById(R.id.cbEnableThresh);
        cbEnableNoise = findViewById(R.id.cbEnableNoise);
        cbEnableArea = findViewById(R.id.cbEnableArea);
        cbEnableClahe = findViewById(R.id.cbEnableClahe);

        btnPrev = findViewById(R.id.btnAnalysisPrev);
        btnNext = findViewById(R.id.btnAnalysisNext);
        btnAnalyzeAll = findViewById(R.id.btnActionAnalyzeAll);
        btnExport = findViewById(R.id.btnActionExportDownloads);
    }

    private void setupInputControls() {
        sbThresh.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                paramThresh = progress;
                lblThresh.setText("Threshold Cutoff: " + paramThresh);
                renderActivePreview();
            }
        });

        sbNoise.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                paramNoise = Math.max(1, progress);
                lblNoise.setText("Noise Filter Radius (Median): " + paramNoise + "px");
                renderActivePreview();
            }
        });

        sbArea.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                paramMinArea = progress;
                lblArea.setText("Min Streak Tracking Area: " + paramMinArea + " px²");
                renderActivePreview();
            }
        });

        sbClahe.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                paramClaheTile = Math.max(2, progress);
                lblClahe.setText("CLAHE Contrast Window: " + paramClaheTile + "px");
                renderActivePreview();
            }
        });

        cbEnableThresh.setOnCheckedChangeListener((buttonView, isChecked) -> {
            useThresh = isChecked;
            renderActivePreview();
        });
        cbEnableNoise.setOnCheckedChangeListener((buttonView, isChecked) -> {
            useNoise = isChecked;
            renderActivePreview();
        });
        cbEnableArea.setOnCheckedChangeListener((buttonView, isChecked) -> {
            useArea = isChecked;
            renderActivePreview();
        });
        cbEnableClahe.setOnCheckedChangeListener((buttonView, isChecked) -> {
            useClahe = isChecked;
            renderActivePreview();
        });

        sbRangeStart.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                frameRangeStart = Math.min(progress, frameRangeEnd);
                if (fromUser && progress > frameRangeEnd) {
                    sbRangeStart.setProgress(frameRangeEnd);
                }
                updateFrameRangeLabel();
            }
        });

        sbRangeEnd.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                frameRangeEnd = Math.max(progress, frameRangeStart);
                if (fromUser && progress < frameRangeStart) {
                    sbRangeEnd.setProgress(frameRangeStart);
                }
                updateFrameRangeLabel();
            }
        });

        btnPrev.setOnClickListener(v -> {
            if (currentFrameIndex > 0) {
                currentFrameIndex--;
                renderActivePreview();
            }
        });

        btnNext.setOnClickListener(v -> {
            if (currentFrameIndex < frameFiles.size() - 1) {
                currentFrameIndex++;
                renderActivePreview();
            }
        });

        btnAnalyzeAll.setOnClickListener(v -> executeFullBatchProcessing());
        btnExport.setOnClickListener(v -> exportAnalysisPackages());
    }

    private void updateFrameRangeLabel() {
        if (lblFrameRange == null) return;
        int selectedCount = frameRangeEnd - frameRangeStart + 1;
        lblFrameRange.setText(String.format(Locale.US,
                "Custom range: frame %d to %d of %d recorded  (%d frames will be analyzed)",
                frameRangeStart + 1, frameRangeEnd + 1, frameFiles.size(), selectedCount));
    }

    private void loadSessionFiles() {
        if (sessionDir == null || !sessionDir.exists()) return;

        // Frames live at Media/<session>/frames/ (see SessionPathManager). Fall back to
        // treating SESSION_PATH itself as the frames folder for backward compatibility
        // with any older recordings made before this folder layout existed.
        File framesSubdir = new File(sessionDir, SessionPathManager.FRAMES_SUBFOLDER);
        File searchDir = framesSubdir.exists() ? framesSubdir : sessionDir;

        File[] files = searchDir.listFiles((dir, name) -> name.endsWith(".tiff") || name.endsWith(".tif"));

        if (files != null && files.length > 0) {
            Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            frameFiles.addAll(Arrays.asList(files));
            renderActivePreview();

            int maxIndex = frameFiles.size() - 1;
            sbRangeStart.setMax(maxIndex);
            sbRangeEnd.setMax(maxIndex);
            frameRangeStart = 0;
            frameRangeEnd = maxIndex;
            sbRangeStart.setProgress(0);
            sbRangeEnd.setProgress(maxIndex);
            updateFrameRangeLabel();
        }
    }

    private void renderActivePreview() {
        if (frameFiles.isEmpty() || currentFrameIndex >= frameFiles.size()) return;

        tvFrameIdx.setText("Frame: " + (currentFrameIndex + 1) + " / " + frameFiles.size());

        pipelineExecutor.execute(() -> {
            File targetedFile = frameFiles.get(currentFrameIndex);
            Mat matOriginal = Imgcodecs.imread(targetedFile.getAbsolutePath(), Imgcodecs.IMREAD_GRAYSCALE);
            if (matOriginal.empty()) return;

            Mat matEnhanced = new Mat();
            if (useNoise && paramNoise > 0) {
                int kernelSize = (paramNoise % 2 == 0) ? paramNoise + 1 : paramNoise;
                Imgproc.medianBlur(matOriginal, matEnhanced, kernelSize);
            } else {
                matOriginal.copyTo(matEnhanced);
            }

            if (useClahe && paramClaheTile > 1) {
                // createCLAHE's Size argument is a tile-grid COUNT (>=1x1), not a pixel size.
                // paramClaheTile/8.0 can truncate to 0 for small slider values, which OpenCV
                // rejects inside copyMakeBorder with the "top >= 0 && bottom >= 0 ..." crash.
                // Clamp both dimensions to at least 1 tile to keep the grid always valid.
                int tileGrid = Math.max(1, (int) Math.round(paramClaheTile / 8.0));
                org.opencv.imgproc.CLAHE claheObj = Imgproc.createCLAHE(2.0, new Size(tileGrid, tileGrid));
                claheObj.apply(matEnhanced, matEnhanced);
            }

            Mat matTopHat = new Mat();
            Mat structElement = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(15, 15));
            Imgproc.morphologyEx(matEnhanced, matTopHat, Imgproc.MORPH_TOPHAT, structElement);

            // Effective threshold cutoff - unchecking "Apply Threshold Cutoff" falls back to a
            // fixed 128 rather than skipping thresholding entirely (a binary mask is required
            // downstream), mirroring the MATLAB script's t_eff = use_thresh*t + ~use_thresh*128.
            int effectiveThresh = useThresh ? paramThresh : 128;
            Mat matThreshold = new Mat();
            Imgproc.threshold(matTopHat, matThreshold, effectiveThresh, 255, Imgproc.THRESH_BINARY);

            Mat matFinal = new Mat();
            Imgproc.cvtColor(matThreshold, matFinal, Imgproc.COLOR_GRAY2BGR);

            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(matThreshold, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            for (MatOfPoint contour : contours) {
                double computedArea = Imgproc.contourArea(contour);
                if (!useArea || computedArea >= paramMinArea) {
                    Rect boundBox = Imgproc.boundingRect(contour);
                    Imgproc.rectangle(matFinal, boundBox.tl(), boundBox.br(), new Scalar(0, 230, 118), 2);
                    Imgproc.arrowedLine(matFinal, boundBox.tl(), boundBox.br(), new Scalar(30, 144, 255), 2, 8, 0, 0.2);
                }
            }

            Bitmap bmpOrig = createBitmapFromMat(matOriginal);
            Bitmap bmpEnhance = createBitmapFromMat(matEnhanced);
            Bitmap bmpThresh = createBitmapFromMat(matThreshold);
            Bitmap bmpFinal = createBitmapFromMat(matFinal);

            mainHandler.post(() -> {
                ivOriginal.setImageBitmap(bmpOrig);
                ivEnhanced.setImageBitmap(bmpEnhance);
                ivThreshold.setImageBitmap(bmpThresh);
                ivFinal.setImageBitmap(bmpFinal);
            });
        });
    }

    private void executeFullBatchProcessing() {
        if (frameFiles.isEmpty()) return;

        int start = Math.max(0, Math.min(frameRangeStart, frameFiles.size() - 1));
        int end = Math.max(start, Math.min(frameRangeEnd, frameFiles.size() - 1));
        List<File> selectedFiles = frameFiles.subList(start, end + 1);

        Toast.makeText(this, "Analyzing frames " + (start + 1) + "-" + (end + 1)
                + " (" + selectedFiles.size() + " frames)...", Toast.LENGTH_SHORT).show();

        globalSessionVectors.clear();
        analyzedFrameFiles.clear();

        pipelineExecutor.execute(() -> {
            double targetExposure = paramExposureSeconds > 0 ? paramExposureSeconds : 0.001;
            double targetScale = paramScalePxPerMm > 0 ? paramScalePxPerMm : 10.0;

            for (File file : selectedFiles) {
                List<StreakVector> frameVectors = new ArrayList<>();
                Mat img = Imgcodecs.imread(file.getAbsolutePath(), Imgcodecs.IMREAD_GRAYSCALE);
                if (img.empty()) continue;

                if (imgWidth == 0) { imgWidth = img.cols(); imgHeight = img.rows(); }

                Mat processed = new Mat();
                if (useNoise && paramNoise > 0) {
                    int kSize = (paramNoise % 2 == 0) ? paramNoise + 1 : paramNoise;
                    Imgproc.medianBlur(img, processed, kSize);
                } else {
                    img.copyTo(processed);
                }

                if (useClahe && paramClaheTile > 1) {
                    // Mirror the live-preview tile-grid calculation so batch processing matches
                    // what the user sees, and stays safe against a zero/negative tile grid.
                    int tileGridBatch = Math.max(1, (int) Math.round(paramClaheTile / 8.0));
                    Imgproc.createCLAHE(2.0, new Size(tileGridBatch, tileGridBatch)).apply(processed, processed);
                }

                Mat tophatMat = new Mat();
                Imgproc.morphologyEx(processed, tophatMat, Imgproc.MORPH_TOPHAT, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(15, 15)));

                // Same fallback as the live preview: unchecking "Apply Threshold Cutoff" uses a
                // fixed 128 instead of skipping the threshold step outright.
                int effectiveThresh = useThresh ? paramThresh : 128;
                Mat threshMat = new Mat();
                Imgproc.threshold(tophatMat, threshMat, effectiveThresh, 255, Imgproc.THRESH_BINARY);

                List<MatOfPoint> contours = new ArrayList<>();
                Imgproc.findContours(threshMat, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

                Mat overlay = new Mat();
                Imgproc.cvtColor(img, overlay, Imgproc.COLOR_GRAY2BGR);

                for (MatOfPoint contour : contours) {
                    if (!useArea || Imgproc.contourArea(contour) >= paramMinArea) {
                        Rect r = Imgproc.boundingRect(contour);

                        double cx = r.x + (r.width / 2.0);
                        double cy = r.y + (r.height / 2.0);

                        double dxMeters = (r.width / targetScale) / 1000.0;
                        double dyMeters = (r.height / targetScale) / 1000.0;
                        double uVel = dxMeters / targetExposure;
                        double vVel = dyMeters / targetExposure;
                        double velMag = Math.sqrt(uVel * uVel + vVel * vVel);
                        double velAngle = Math.atan2(-vVel, uVel) * (180.0 / Math.PI);

                        frameVectors.add(new StreakVector(cx, cy, uVel, vVel, velMag, velAngle));

                        Imgproc.rectangle(overlay, r.tl(), r.br(), new Scalar(0, 230, 118), 2);
                        Imgproc.arrowedLine(overlay, r.tl(), r.br(), new Scalar(30, 144, 255), 2, 8, 0, 0.25);
                    }
                }

                // Save the per-frame result overlay under the same base name as the source
                // frame (modification #6: "title of the images are as same like the title
                // of the frames that uploaded").
                String baseName = stripExtension(file.getName());
                File resultsDir = SessionPathManager.getResultsDirectory(AnalysisActivity.this, sessionName);
                Imgcodecs.imwrite(new File(resultsDir, baseName + ".png").getAbsolutePath(), overlay);

                globalSessionVectors.add(frameVectors);
                analyzedFrameFiles.add(file);
            }

            containsAnalyzedData = true;
            mainHandler.post(() -> {
                Toast.makeText(AnalysisActivity.this, "Batch Analysis Complete. Ready to Export.", Toast.LENGTH_LONG).show();
                btnExport.setEnabled(true);
            });
        });
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private void exportAnalysisPackages() {
        if (!containsAnalyzedData) return;

        pipelineExecutor.execute(() -> {
            try {
                File resultsDir = SessionPathManager.getResultsDirectory(this, sessionName);
                File averagedDataDir = SessionPathManager.getAveragedDataDirectory(this, sessionName);

                // 1. Per-frame vector CSVs (results/), named after the originating frame so
                //    they line up with the overlay PNG already saved during batch processing.
                for (int fi = 0; fi < globalSessionVectors.size(); fi++) {
                    String baseName = stripExtension(analyzedFrameFiles.get(fi).getName());
                    File csvFile = new File(resultsDir, baseName + "_vector.csv");
                    try (OutputStream os = new FileOutputStream(csvFile)) {
                        StringBuilder sb = new StringBuilder("x,y,u,v,magnitude,angle\n");
                        for (StreakVector v : globalSessionVectors.get(fi)) {
                            sb.append(String.format(Locale.US, "%f,%f,%f,%f,%f,%f\n", v.x, v.y, v.u, v.v, v.magnitude, v.angle));
                        }
                        os.write(sb.toString().getBytes());
                    }
                }

                // 2. Grid-averaged vector field (Averaged_data/averaged_vectors.csv) - this is
                //    what ResultsActivity's "Vector Quiver Field" section renders.
                List<ResultsActivity.VectorData> averagedVectors = computeGridAveragedVectors(paramGridPx > 0 ? paramGridPx : 40.0);
                File averagedCsv = new File(averagedDataDir, "averaged_vectors.csv");
                try (OutputStream os = new FileOutputStream(averagedCsv)) {
                    StringBuilder sb = new StringBuilder("x,y,u,v,magnitude,angle,count\n");
                    for (ResultsActivity.VectorData v : averagedVectors) {
                        sb.append(String.format(Locale.US, "%f,%f,%f,%f,%f,%f,%d\n",
                                v.x, v.y, v.u, v.v, v.magnitude, v.angle, v.count));
                    }
                    os.write(sb.toString().getBytes());
                }

                // 3. Vector points as an Excel-readable (tab-separated .xls) file, per
                //    modification #3/#6 ("xls file of vector points data").
                File xlsFile = new File(averagedDataDir, "averaged_vectors.xls");
                try (OutputStream os = new FileOutputStream(xlsFile)) {
                    StringBuilder tsv = new StringBuilder();
                    tsv.append("X Centre\tY Centre\tU Avg (m/s)\tV Avg (m/s)\tMagnitude\tAngle\tSamples\n");
                    for (ResultsActivity.VectorData v : averagedVectors) {
                        tsv.append(v.x).append("\t").append(v.y).append("\t").append(v.u).append("\t")
                                .append(v.v).append("\t").append(v.magnitude).append("\t")
                                .append(v.angle).append("\t").append(v.count).append("\n");
                    }
                    os.write(tsv.toString().getBytes());
                }

                // 4. Averaged streak vector flow image (Averaged_data/) - a single quiver
                //    plot like the MATLAB analyzeStreaksMulti() output attached by the user.
                Mat averagedImage = renderAveragedFieldImage(averagedVectors,
                        imgWidth > 0 ? imgWidth : 1280, imgHeight > 0 ? imgHeight : 720);
                File averagedImageFile = new File(averagedDataDir, "averaged_streak_vector_flow.png");
                Imgcodecs.imwrite(averagedImageFile.getAbsolutePath(), averagedImage);

                mainHandler.post(() -> Toast.makeText(AnalysisActivity.this,
                        "Saved to Media/" + sessionName + " (Averaged_data + results)", Toast.LENGTH_LONG).show());

            } catch (IOException e) {
                Log.e(TAG, "Export failure", e);
                mainHandler.post(() -> Toast.makeText(AnalysisActivity.this, "Export failure.", Toast.LENGTH_SHORT).show());
            }
        });
    }

    /**
     * Bins every tracked streak across all analyzed frames into a spatial grid and averages
     * the velocity within each cell - the same binning approach as the MATLAB script's
     * per-cell average, just computed in Java against the on-device streak detections.
     */
    private List<ResultsActivity.VectorData> computeGridAveragedVectors(double cellSize) {
        Map<Long, double[]> bins = new LinkedHashMap<>(); // sumX, sumY, sumU, sumV, count

        for (List<StreakVector> frame : globalSessionVectors) {
            for (StreakVector v : frame) {
                long cellX = Math.round(v.x / cellSize);
                long cellY = Math.round(v.y / cellSize);
                long key = (cellX << 32) ^ (cellY & 0xffffffffL);

                double[] acc = bins.get(key);
                if (acc == null) {
                    acc = new double[5];
                    bins.put(key, acc);
                }
                acc[0] += v.x;
                acc[1] += v.y;
                acc[2] += v.u;
                acc[3] += v.v;
                acc[4] += 1;
            }
        }

        List<ResultsActivity.VectorData> result = new ArrayList<>();
        for (double[] acc : bins.values()) {
            int count = (int) acc[4];
            if (count == 0) continue;

            float avgX = (float) (acc[0] / count);
            float avgY = (float) (acc[1] / count);
            float avgU = (float) (acc[2] / count);
            float avgV = (float) (acc[3] / count);
            float magnitude = (float) Math.sqrt(avgU * avgU + avgV * avgV);
            float angle = (float) (Math.atan2(-avgV, avgU) * (180.0 / Math.PI));

            result.add(new ResultsActivity.VectorData(avgX, avgY, avgU, avgV, magnitude, angle, count));
        }
        return result;
    }

    /**
     * Renders the grid-averaged vectors as a single colour-coded quiver plot on a black
     * canvas, mirroring the MATLAB analyzeStreaksMulti() "Fig 1 - Coloured Velocity Vectors"
     * figure (jet colormap, arrow length scaled to the image diagonal).
     */
    private Mat renderAveragedFieldImage(List<ResultsActivity.VectorData> vectors, int width, int height) {
        Mat canvas = new Mat(height, width, org.opencv.core.CvType.CV_8UC3, new Scalar(0, 0, 0));

        if (vectors.isEmpty()) {
            Imgproc.putText(canvas, "No vectors detected", new Point(20, height / 2.0),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, new Scalar(255, 255, 255), 2);
            return canvas;
        }

        float minMag = Float.MAX_VALUE, maxMag = -Float.MAX_VALUE;
        for (ResultsActivity.VectorData v : vectors) {
            minMag = Math.min(minMag, v.magnitude);
            maxMag = Math.max(maxMag, v.magnitude);
        }
        if (maxMag <= 0) maxMag = 1;
        if (maxMag == minMag) maxMag = minMag + 0.001f;

        double diag = Math.sqrt((double) width * width + (double) height * height);
        double vscale = (diag * 0.07) / maxMag;

        for (ResultsActivity.VectorData v : vectors) {
            double normalized = (v.magnitude - minMag) / (maxMag - minMag);
            Scalar color = jetColorBGR(normalized);

            Point start = new Point(v.x, v.y);
            Point end = new Point(v.x + v.u * vscale, v.y + v.v * vscale);
            Imgproc.arrowedLine(canvas, start, end, color, 2, Imgproc.LINE_AA, 0, 0.3);
        }

        drawColorLegend(canvas, width, height, minMag, maxMag);
        return canvas;
    }

    private void drawColorLegend(Mat canvas, int width, int height, float minMag, float maxMag) {
        int barW = 24, barH = Math.min(240, height - 60), margin = 20;
        int x = width - barW - margin - 60;
        int yTop = margin + 20;

        for (int i = 0; i < barH; i++) {
            double normalized = 1.0 - (i / (double) barH);
            Scalar color = jetColorBGR(normalized);
            Imgproc.line(canvas, new Point(x, yTop + i), new Point(x + barW, yTop + i), color, 1);
        }
        Imgproc.rectangle(canvas, new Point(x, yTop), new Point(x + barW, yTop + barH), new Scalar(255, 255, 255), 1);
        Imgproc.putText(canvas, String.format(Locale.US, "%.3f m/s", maxMag), new Point(x + barW + 6, yTop + 10),
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.45, new Scalar(255, 255, 255), 1);
        Imgproc.putText(canvas, String.format(Locale.US, "%.3f m/s", minMag), new Point(x + barW + 6, yTop + barH),
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.45, new Scalar(255, 255, 255), 1);
    }

    /** Standard jet colormap, returned as an OpenCV BGR Scalar (0-255 per channel). */
    private Scalar jetColorBGR(double normalized) {
        normalized = Math.max(0, Math.min(1, normalized));
        double r, g, b;
        if (normalized < 0.125) { r = 0; g = 0; b = 0.5 + normalized / 0.125 * 0.5; }
        else if (normalized < 0.375) { r = 0; g = (normalized - 0.125) / 0.25; b = 1; }
        else if (normalized < 0.625) { r = (normalized - 0.375) / 0.25; g = 1; b = 1 - (normalized - 0.375) / 0.25; }
        else if (normalized < 0.875) { r = 1; g = 1 - (normalized - 0.625) / 0.25; b = 0; }
        else { r = 1 - (normalized - 0.875) / 0.125; g = 0; b = 0; }
        return new Scalar(b * 255, g * 255, r * 255);
    }

    private Bitmap createBitmapFromMat(Mat matSource) {
        Bitmap outBmp = Bitmap.createBitmap(matSource.cols(), matSource.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(matSource, outBmp);
        return outBmp;
    }

    private static abstract class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
