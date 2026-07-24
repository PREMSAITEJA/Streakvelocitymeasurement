package com.fmea.highfps;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.view.Surface;
import android.view.TextureView;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements FrameProcessor.StorageSafetyCallback {
    private static final String TAG = "HighFPSRecorder";
    private static final int REQUEST_CODE_PERMISSIONS = 200;
    private static final int CAPTURE_WIDTH = 1920;
    private static final int CAPTURE_HEIGHT = 1080;
    private static final int IMAGE_READER_BUFFER = 5;  // Reduced from 10 to prevent OutOfMemoryError

    private Button btnRecord, btnStop;
    private TextureView textureView;
    private TextView tvFrameCount, tvBrightnessLabel, tvFocusLabel, tvExposureTimeLabel;
    private SeekBar seekBarBrightness, seekBarFocus, seekBarExposureTime;
    private ImageButton btnFpsSelector;

    // Collapsible Layout Component Elements
    private LinearLayout headerBle, headerBrightness, headerFocus;
    private LinearLayout containerBleBody, containerBrightnessBody, containerFocusBody;
    private TextView tvBleHeaderLabel, tvBrightnessHeaderLabel, tvFocusHeaderLabel;

    // Environmental Telemetry UI Displays
    private TextView tvBleStatus, tvCo2, tvTemp, tvHumidity;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private FrameSaver frameSaver;
    private FrameProcessor frameProcessor; // copies frames off camera buffers and writes them async

    private HandlerThread cameraThread, frameThread;
    private Handler cameraHandler, frameHandler, uiHandler;

    private String activeCameraId = "0"; // Dynamically tracks native active sensor matrix ID
    private volatile boolean isRecording;
    private boolean pendingStartRecording;

    private CaptureRequest.Builder activeRequestBuilder;
    private int selectedFps = 60; // default FPS

    // BLE Manager Field Instance
    private BleSensorManager bleSensorManager;

    // Live recording HUD: saved frames | dropped frames | disk queue depth
    private final Runnable frameCountUpdater = new Runnable() {
        @Override
        public void run() {
            if (isRecording && frameSaver != null && frameProcessor != null) {
                int saved = frameSaver.getFrameCount();
                long dropped = frameProcessor.getDroppedFrameCount();
                int queue = frameProcessor.getQueueSize();
                tvFrameCount.setText(String.format(Locale.US,
                        "Frames: %d | Dropped: %d | Queue: %d", saved, dropped, queue));
                uiHandler.postDelayed(this, 100);
            }
        }
    };

    /**
     * Fired by FrameProcessor when free space is down to the 1 MB safety floor.
     * Recording runs right up to that limit, then auto-stops cleanly here.
     */
    @Override
    public void onStorageExhausted() {
        uiHandler.post(() -> {
            Toast.makeText(MainActivity.this,
                    "CRITICAL: Storage full (1 MB floor reached). Stopping recording automatically.",
                    Toast.LENGTH_LONG).show();
            stopRecording();
        });
    }

    // Dynamic Broadcast Receiver to listen for Hardware Bluetooth adapter status updates
    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                switch (state) {
                    case BluetoothAdapter.STATE_ON:
                        Log.i(TAG, "Bluetooth hardware adapted switched ON dynamically.");
                        if (tvBleStatus != null) tvBleStatus.setText("BLE Status: Bluetooth Enabled");
                        if (hasRequiredPermissions() && bleSensorManager != null) {
                            bleSensorManager.start();
                        }
                        break;
                    case BluetoothAdapter.STATE_TURNING_OFF:
                    case BluetoothAdapter.STATE_OFF:
                        Log.i(TAG, "Bluetooth hardware adapter switched OFF.");
                        if (tvBleStatus != null) tvBleStatus.setText("BLE Status: Bluetooth Disabled");
                        if (bleSensorManager != null) {
                            bleSensorManager.stop();
                        }
                        break;
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnRecord = findViewById(R.id.btnRecord);
        btnStop = findViewById(R.id.btnStop);
        textureView = findViewById(R.id.textureView);
        tvFrameCount = findViewById(R.id.tvFrameCount);
        seekBarBrightness = findViewById(R.id.seekBarBrightness);
        seekBarFocus = findViewById(R.id.seekBarFocus);
        tvBrightnessLabel = findViewById(R.id.tvBrightnessLabel);
        tvFocusLabel = findViewById(R.id.tvFocusLabel);
        seekBarExposureTime = findViewById(R.id.seekBarExposureTime);
        tvExposureTimeLabel = findViewById(R.id.tvExposureTimeLabel);
        btnFpsSelector = findViewById(R.id.btnFpsSelector);
        // Bottom navigation: open MediaActivity when user taps Media
        BottomNavigationView bottomNav = findViewById(R.id.bottomNavigationView);
        if (bottomNav != null) {
            bottomNav.setOnItemSelectedListener(item -> {
                if (item.getItemId() == R.id.nav_media) {
                    startActivity(new Intent(MainActivity.this, MediaActivity.class));
                    return true;
                } else if (item.getItemId() == R.id.nav_results) {
                    startActivity(new Intent(MainActivity.this, ResultsActivity.class));
                    return true;
                }
                return true;
            });
        }

        // Map HUD components
        tvBleStatus = findViewById(R.id.tvBleStatus);
        tvCo2 = findViewById(R.id.tvCo2);
        tvTemp = findViewById(R.id.tvTemp);
        tvHumidity = findViewById(R.id.tvHumidity);

        // Bind Collapsible Layout Elements
        headerBle = findViewById(R.id.headerBle);
        headerBrightness = findViewById(R.id.headerBrightness);
        headerFocus = findViewById(R.id.headerFocus);

        containerBleBody = findViewById(R.id.containerBleBody);
        containerBrightnessBody = findViewById(R.id.containerBrightnessBody);
        containerFocusBody = findViewById(R.id.containerFocusBody);

        tvBleHeaderLabel = findViewById(R.id.tvBleHeaderLabel);
        tvBrightnessHeaderLabel = findViewById(R.id.tvBrightnessHeaderLabel);
        tvFocusHeaderLabel = findViewById(R.id.tvFocusHeaderLabel);

        btnRecord.setOnClickListener(v -> startRecording());
        btnStop.setOnClickListener(v -> stopRecording());
        btnStop.setEnabled(false);

        btnFpsSelector.setOnClickListener(v -> showFpsMenu());

        uiHandler = new Handler(getMainLooper());

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                startCameraPreviewIfReady();
            }
            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}
            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                closeCameraObjects();
                stopCameraThread();
                stopFrameThread();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
        });

        if (textureView.isAvailable()) {
            startCameraPreviewIfReady();
        }

        setupSliders();
        setupBleSensor();
        setupCollapsiblePanels();

        // Register the dynamic hardware receiver
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(bluetoothStateReceiver, filter);
    }

    private void setupCollapsiblePanels() {
        headerBle.setOnClickListener(v -> {
            if (containerBleBody.getVisibility() == View.VISIBLE) {
                containerBleBody.setVisibility(View.GONE);
                tvBleHeaderLabel.setText("? WIRELESS BLE ENVIRONMENTAL SENSORS");
            } else {
                containerBleBody.setVisibility(View.VISIBLE);
                tvBleHeaderLabel.setText("? WIRELESS BLE ENVIRONMENTAL SENSORS");
            }
        });

        headerBrightness.setOnClickListener(v -> {
            if (containerBrightnessBody.getVisibility() == View.VISIBLE) {
                containerBrightnessBody.setVisibility(View.GONE);
                tvBrightnessHeaderLabel.setText("? SENSOR EXPOSURE / BRIGHTNESS");
            } else {
                containerBrightnessBody.setVisibility(View.VISIBLE);
                tvBrightnessHeaderLabel.setText("? SENSOR EXPOSURE / BRIGHTNESS");
            }
        });

        headerFocus.setOnClickListener(v -> {
            if (containerFocusBody.getVisibility() == View.VISIBLE) {
                containerFocusBody.setVisibility(View.GONE);
                tvFocusHeaderLabel.setText("? BLACKMAGIC OPTICAL LENS FOCUS");
            } else {
                containerFocusBody.setVisibility(View.VISIBLE);
                tvFocusHeaderLabel.setText("? BLACKMAGIC OPTICAL LENS FOCUS");
            }
        });
    }

    private void setupBleSensor() {
        bleSensorManager = new BleSensorManager(this, new BleSensorManager.BleDataListener() {
            @Override
            public void onDataParsed(int co2, float temperature, float humidity) {
                uiHandler.post(() -> {
                    if (tvCo2 != null) tvCo2.setText("CO2: " + co2 + " ppm");
                    if (tvTemp != null) tvTemp.setText(String.format("Temp: %.2f *C", temperature));
                    if (tvHumidity != null) tvHumidity.setText(String.format("Humidity: %.2f %%RH", humidity));
                });
            }

            @Override
            public void onStatusChanged(String status) {
                uiHandler.post(() -> {
                    if (tvBleStatus != null) tvBleStatus.setText("BLE Status: " + status);
                });
            }
        });

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (hasRequiredPermissions() && adapter != null && adapter.isEnabled()) {
            bleSensorManager.start();
        } else if (tvBleStatus != null) {
            tvBleStatus.setText("BLE Status: Bluetooth Disabled");
        }
    }

    private void showFpsMenu() {
        PopupMenu popup = new PopupMenu(this, btnFpsSelector);
        popup.getMenu().add("15 FPS");
        popup.getMenu().add("30 FPS");
        popup.getMenu().add("60 FPS");
        popup.getMenu().add("240 FPS (Burst)");

        popup.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            activeCameraId = "0";

            if (title.contains("15")) {
                selectedFps = 15;
            } else if (title.contains("30")) {
                selectedFps = 30;
            } else if (title.contains("60")) {
                selectedFps = 60;
            } else if (title.contains("240")) {
                selectedFps = 240;
            }
            Toast.makeText(this, "FPS set to " + selectedFps, Toast.LENGTH_SHORT).show();

            if (cameraDevice != null) {
                if (selectedFps == 240) {
                    configureHighSpeedSession();
                } else {
                    startPreviewSession();
                }
            }
            return true;
        });
        popup.show();
    }

    private void setupSliders() {
        seekBarBrightness.setMax(10);
        seekBarBrightness.setProgress(5);
        tvBrightnessLabel.setText("Brightness: 5");

        // Focus setup: Progress 5 matches "Auto Focus" view mode
        seekBarFocus.setMax(10);
        seekBarFocus.setProgress(5);
        tvFocusLabel.setText("Focus: Auto");

        seekBarBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                tvBrightnessLabel.setText("Brightness: " + value);
                applyManualCameraControls();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekBarFocus.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (value == 5) {
                    tvFocusLabel.setText("Focus: Auto");
                } else {
                    tvFocusLabel.setText("Focus: Manual (" + value + ")");
                }
                applyManualCameraControls();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Exposure Time (shutter speed) threshold, matching the manual shutter-speed slider
        // in Samsung Camera's Pro mode. Progress 10 is the centre "Auto" position (leaves
        // CONTROL_AE_MODE_ON); moving away from centre switches to a manual SENSOR_EXPOSURE_TIME
        // mapped log-scale across the sensor's supported range (see applyManualCameraControls()).
        seekBarExposureTime.setMax(20);
        seekBarExposureTime.setProgress(10);
        tvExposureTimeLabel.setText("Exposure Time: Auto");

        seekBarExposureTime.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (value == 10) {
                    tvExposureTimeLabel.setText("Exposure Time: Auto");
                } else {
                    tvExposureTimeLabel.setText("Exposure Time: " + formatExposureTimeLabel(value));
                }
                applyManualCameraControls();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    /**
     * Human-readable preview of what a given exposure slider position will map to, shown
     * next to the slider before the capture request is actually built (so the label updates
     * immediately even if applyManualCameraControls() hasn't run yet, e.g. camera not open).
     */
    private String formatExposureTimeLabel(int progress) {
        long exposureNs = mapExposureProgressToNanos(progress, 100_000L, 1_000_000_000L);
        return formatExposureNanos(exposureNs);
    }

    /** Maps a 0-20 slider progress (10 = Auto, unused here) log-scale onto [minNs, maxNs]. */
    private long mapExposureProgressToNanos(int progress, long minNs, long maxNs) {
        double fraction = progress / 20.0;
        double logMin = Math.log(minNs);
        double logMax = Math.log(maxNs);
        return (long) Math.exp(logMin + fraction * (logMax - logMin));
    }

    /** Formats a nanosecond exposure time as a Samsung Pro-mode-style shutter speed string. */
    private String formatExposureNanos(long ns) {
        double seconds = ns / 1_000_000_000.0;
        if (seconds >= 1.0) {
            return String.format(Locale.US, "%.1fs", seconds);
        }
        long denominator = Math.round(1.0 / seconds);
        return "1/" + denominator + "s";
    }

    /**
     * Handles updating lens parameters.
     * Evaluates focus changes dynamically via the step threshold index of 5.
     */
    private void applyManualCameraControls() {
        if (activeRequestBuilder == null || captureSession == null) return;
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(activeCameraId);

            // 1. Exposure / Brightness control
            Range<Integer> compensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if (compensationRange != null) {
                int min = compensationRange.getLower();
                int max = compensationRange.getUpper();
                if (max > min) {
                    int mappedExposure = min + (int) ((max - min) * (seekBarBrightness.getProgress() / 10.0f));
                    activeRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, mappedExposure);
                }
            }

            // 2. Focus logic with threshold level tracking
            int focusValue = seekBarFocus.getProgress();
            if (focusValue == 5) {
                // Default threshold profile locks lens configuration back into Continuous Auto Focus
                activeRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            } else {
                // Step away from 5 unlinks automatic modes and switches completely to Manual configuration loop
                activeRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);

                Float minimumFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                if (minimumFocusDistance != null) {
                    // Normalize tracking points around the missing threshold baseline matrix
                    float targetFocusDistance = (focusValue / 10.0f) * minimumFocusDistance;
                    activeRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, targetFocusDistance);
                }
            }

            // 3. Exposure Time (shutter speed) threshold - mirrors Samsung Camera's Pro mode
            // manual shutter control. Progress 10 (centre) is Auto; any other position switches
            // CONTROL_AE_MODE to OFF and drives SENSOR_EXPOSURE_TIME directly.
            int exposureValue = seekBarExposureTime.getProgress();
            if (exposureValue == 10) {
                activeRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            } else {
                Range<Long> exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                if (exposureRange != null) {
                    activeRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);

                    long minExposureNs = exposureRange.getLower();
                    long maxExposureNs = exposureRange.getUpper();
                    long targetExposureNs = mapExposureProgressToNanos(exposureValue, minExposureNs, maxExposureNs);
                    activeRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetExposureNs);

                    // CONTROL_AE_MODE_OFF requires manual sensitivity too on most devices, or the
                    // capture request is rejected outright - pick the sensor's default/midpoint ISO.
                    Range<Integer> isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                    if (isoRange != null) {
                        int midIso = (isoRange.getLower() + isoRange.getUpper()) / 2;
                        activeRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, midIso);
                    }
                }
            }

            // Apply updates dynamically to the pipeline stream
            if (selectedFps != 240) {
                captureSession.setRepeatingRequest(activeRequestBuilder.build(), null, cameraHandler);
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Dynamic capture crash bypassed. Verified invalid camera target identity: " + activeCameraId, e);
        } catch (Exception e) {
            Log.e(TAG, "Failed updating manual sensor options safely", e);
        }
    }

    // ---------------- Recording Pipeline Methods ----------------
    private void startRecording() {
        if (isRecording) return;

        if (!hasRequiredPermissions()) {
            pendingStartRecording = true;
            ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQUEST_CODE_PERMISSIONS);
            return;
        }

        if (cameraDevice == null) {
            pendingStartRecording = true;
            startCameraPreviewIfReady();
            return;
        }

        if (selectedFps == 240) {
            configureHighSpeedSession();
        } else {
            configureRecordingSession();
        }
    }

    private void stopRecording() {
        if (!isRecording) return;

        isRecording = false;
        pendingStartRecording = false;
        if (frameProcessor != null) {
            frameProcessor.stopRecording();
        }
        stopFrameCountUpdates();
        uiHandler.post(() -> setRecordingUiState(false));

        int total = frameSaver != null ? frameSaver.getFrameCount() : 0;
        String message = "Recording stopped. Total frames dumped: " + total;
        Log.i(TAG, message);
        showToastOnUiThread(message);

        frameSaver = null;
        frameProcessor = null;
        closeImageReader();

        if (cameraDevice != null) {
            if (selectedFps == 240) {
                configureHighSpeedSession();
            } else {
                startPreviewSession();
            }
        }
    }

    private void startPreviewSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(CAPTURE_WIDTH, CAPTURE_HEIGHT);
            Surface previewSurface = new Surface(texture);

            activeRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            activeRequestBuilder.addTarget(previewSurface);
            activeRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(selectedFps, selectedFps));

            // Set continuous autofocus as the initial default mode
            activeRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            cameraDevice.createCaptureSession(Arrays.asList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            applyManualCameraControls();
                        }
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            showToastOnUiThread("Preview configuration failed");
                        }
                    }, cameraHandler);
        } catch (Exception e) {
            Log.e(TAG, "startPreviewSession failed", e);
        }
    }

    private void configureRecordingSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(CAPTURE_WIDTH, CAPTURE_HEIGHT);
            Surface previewSurface = new Surface(texture);

            imageReader = ImageReader.newInstance(CAPTURE_WIDTH, CAPTURE_HEIGHT, ImageFormat.YUV_420_888, IMAGE_READER_BUFFER);
            Surface recordSurface = imageReader.getSurface();

            activeRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            activeRequestBuilder.addTarget(previewSurface);
            activeRequestBuilder.addTarget(recordSurface);
            activeRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(selectedFps, selectedFps));

            // Set continuous autofocus as the initial default mode
            activeRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, recordSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                isRecording = true;
                                uiHandler.post(() -> setRecordingUiState(true));

                                try {
                                    frameSaver = new FrameSaver(MainActivity.this);
                                    frameProcessor = new FrameProcessor(frameSaver, MainActivity.this);
                                    frameProcessor.startRecording();
                                    if (bleSensorManager != null) {
                                        bleSensorManager.setActiveSession(frameSaver.getSessionName());
                                    }
                                    // Kick off looping HUD updates for the frame counter
                                    uiHandler.post(frameCountUpdater);
                                } catch (Exception e) {
                                    Log.e(TAG, "Failed to initialize processing subsystem", e);
                                    showToastOnUiThread("Failed to initialize storage for frames");
                                    isRecording = false;
                                    uiHandler.post(() -> setRecordingUiState(false));
                                    return;
                                }

                                // FrameProcessor copies the Y plane and closes each Image
                                // immediately - fixes the maxImages(5) IllegalStateException.
                                imageReader.setOnImageAvailableListener(frameProcessor, frameHandler);

                                applyManualCameraControls();
                                showToastOnUiThread("Recording started at " + selectedFps + " fps");
                            } catch (Exception e) {
                                Log.e(TAG, "Recording configuration workflow initialization failed", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            showToastOnUiThread("Recording configuration failed");
                        }
                    }, cameraHandler);
        } catch (Exception e) {
            Log.e(TAG, "configureRecordingSession failed", e);
        }
    }

    private void configureHighSpeedSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(CAPTURE_WIDTH, CAPTURE_HEIGHT);
            Surface previewSurface = new Surface(texture);

            imageReader = ImageReader.newInstance(CAPTURE_WIDTH, CAPTURE_HEIGHT, ImageFormat.YUV_420_888, IMAGE_READER_BUFFER);
            Surface recordSurface = imageReader.getSurface();

            activeRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            activeRequestBuilder.addTarget(previewSurface);
            activeRequestBuilder.addTarget(recordSurface);

            // Initial focus setup matching state callbacks before unhooking loops
            activeRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cameraDevice.createConstrainedHighSpeedCaptureSession(
                        Arrays.asList(previewSurface, recordSurface),
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession session) {
                                captureSession = session;
                                try {
                                    isRecording = true;
                                    uiHandler.post(() -> setRecordingUiState(true));

                                    try {
                                        frameSaver = new FrameSaver(MainActivity.this);
                                        frameProcessor = new FrameProcessor(frameSaver, MainActivity.this);
                                        frameProcessor.startRecording();
                                        if (bleSensorManager != null) {
                                            bleSensorManager.setActiveSession(frameSaver.getSessionName());
                                        }
                                        // Kick off looping HUD updates for the frame counter
                                        uiHandler.post(frameCountUpdater);
                                    } catch (Exception e) {
                                        Log.e(TAG, "Failed to initialize high speed processing", e);
                                        showToastOnUiThread("Failed to initialize storage for frames");
                                        isRecording = false;
                                        uiHandler.post(() -> setRecordingUiState(false));
                                        return;
                                    }

                                    // FrameProcessor copies the Y plane and closes each Image
                                    // immediately - fixes the maxImages(5) IllegalStateException.
                                    imageReader.setOnImageAvailableListener(frameProcessor, frameHandler);

                                    applyManualCameraControls();

                                    List<CaptureRequest> burstRequests = ((CameraConstrainedHighSpeedCaptureSession) session)
                                            .createHighSpeedRequestList(activeRequestBuilder.build());
                                    session.setRepeatingBurst(burstRequests, null, cameraHandler);

                                    showToastOnUiThread("240fps burst recording started");
                                } catch (Exception e) {
                                    Log.e(TAG, "High-speed recording start failed", e);
                                }
                            }

                            @Override
                            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                showToastOnUiThread("High-speed session failed");
                            }
                        }, cameraHandler);
            }
        } catch (Exception e) {
            Log.e(TAG, "configureHighSpeedSession failed", e);
        }
    }

    // ---------------- System Architecture Helper Methods ----------------
    private boolean hasRequiredPermissions() {
        boolean cameraGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean scanGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            boolean connectGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            return cameraGranted && scanGranted && connectGranted;
        } else {
            boolean locationGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            return cameraGranted && locationGranted;
        }
    }

    private String[] getRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return permissions.toArray(new String[0]);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (hasRequiredPermissions()) {
                if (bleSensorManager != null) {
                    bleSensorManager.start();
                }
                startCameraPreviewIfReady();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showToastOnUiThread(String message) {
        uiHandler.post(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
    }

    private void stopFrameCountUpdates() {
        uiHandler.removeCallbacks(frameCountUpdater);
    }

    private void setRecordingUiState(boolean recording) {
        btnRecord.setEnabled(!recording);
        btnStop.setEnabled(recording);
    }

    private void closeCameraObjects() {
        if (captureSession != null) { captureSession.close(); captureSession = null; }
        if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
    }

    private void closeImageReader() {
        if (imageReader != null) { imageReader.close(); imageReader = null; }
    }

    private void startCameraPreviewIfReady() {
        if (!textureView.isAvailable()) return;

        if (!hasRequiredPermissions()) {
            ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQUEST_CODE_PERMISSIONS);
            return;
        }

        if (cameraDevice != null) {
            if (pendingStartRecording) {
                if (selectedFps == 240) {
                    configureHighSpeedSession();
                } else {
                    configureRecordingSession();
                }
            } else if (captureSession == null || !isRecording) {
                if (selectedFps == 240) {
                    configureHighSpeedSession();
                } else {
                    startPreviewSession();
                }
            }
            return;
        }

        startCameraThread();
        startFrameThread();

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            manager.openCamera(activeCameraId, cameraStateCallback, cameraHandler);
        } catch (Exception e) {
            Log.e(TAG, "Unable to open camera preview", e);
            closeCameraObjects();
            stopCameraThread();
            stopFrameThread();
        }
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try { cameraThread.join(); } catch (InterruptedException e) { Log.e(TAG, "Camera thread stop interrupted", e); }
            cameraThread = null; cameraHandler = null;
        }
    }

    private void startFrameThread() {
        frameThread = new HandlerThread("FrameThread");
        frameThread.start();
        frameHandler = new Handler(frameThread.getLooper());
    }

    private void stopFrameThread() {
        if (frameThread != null) {
            frameThread.quitSafely();
            try { frameThread.join(); } catch (InterruptedException e) { Log.e(TAG, "Frame thread stop interrupted", e); }
            frameThread = null; frameHandler = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(bluetoothStateReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Receiver already unregistered or missing", e);
        }
        if (bleSensorManager != null) {
            bleSensorManager.stop();
        }
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            if (pendingStartRecording) {
                if (selectedFps == 240) {
                    configureHighSpeedSession();
                } else {
                    configureRecordingSession();
                }
            } else {
                if (selectedFps == 240) {
                    configureHighSpeedSession();
                } else {
                    startPreviewSession();
                }
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            showToastOnUiThread("Camera error: " + error);
        }
    };
}