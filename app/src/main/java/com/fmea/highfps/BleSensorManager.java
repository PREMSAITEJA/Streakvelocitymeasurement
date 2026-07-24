package com.fmea.highfps;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * BleSensorManager - Handles cross-compatible Bluetooth LE scanning, connection,
 * notification subscription, real-time data streaming parsing, and dynamic file IO writing.
 */
public class BleSensorManager {
    private static final String TAG = "BleSensorManager";
    private static final String DEVICE_NAME = "SCD41-Sensor-Box";

    private static final UUID SERVICE_UUID = UUID.fromString("A34A9710-BC5B-4A59-8395-5D187515FA24");
    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("A34A9711-BC5B-4A59-8395-5D187515FA24");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final BleDataListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothDevice targetDevice;

    private boolean isScanning = false;
    private boolean isConnected = false;

    // Tracks the unique file name for the CURRENT connection session
    private String currentSessionFileName = null;

    // The recording session (Media/<activeSessionName>/...) this sensor log should be filed under.
    private volatile String activeSessionName = null;

    /** Call this as soon as a recording session starts so BLE data is filed under it. */
    public void setActiveSession(String sessionName) {
        this.activeSessionName = sessionName;
    }

    public interface BleDataListener {
        void onDataParsed(int co2, float temperature, float humidity);
        void onStatusChanged(String status);
    }

    public BleSensorManager(Context context, BleDataListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;

        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            this.bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter != null) {
                this.bleScanner = bluetoothAdapter.getBluetoothLeScanner();
            }
        }
    }

    public void start() {
        if (!hasPermissions()) {
            listener.onStatusChanged("Missing BLE Permissions");
            return;
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            listener.onStatusChanged("Bluetooth Disabled");
            return;
        }
        connectOrScan();
    }

    private void connectOrScan() {
        if (targetDevice != null) {
            connectToDevice(targetDevice);
        } else {
            startScanning();
        }
    }

    private void startScanning() {
        if (isScanning || bleScanner == null) return;
        isScanning = true;
        listener.onStatusChanged("Scanning for sensor...");

        ScanFilter filter = new ScanFilter.Builder().setDeviceName(DEVICE_NAME).build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        try {
            bleScanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied during scan start", e);
            listener.onStatusChanged("Scan Permission Denied");
            isScanning = false;
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            synchronized (BleSensorManager.this) {
                if (targetDevice == null) {
                    targetDevice = result.getDevice();
                    handler.post(() -> listener.onStatusChanged("Device found! Connecting..."));
                    stopScanning();
                    connectToDevice(targetDevice);
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "BLE Scan failed with error code: " + errorCode);
            isScanning = false;
            handler.post(() -> listener.onStatusChanged("Scan Failed (" + errorCode + ")"));
        }
    };

    private void stopScanning() {
        if (!isScanning || bleScanner == null) return;
        try {
            bleScanner.stopScan(scanCallback);
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied during scan stop", e);
        }
        isScanning = false;
    }

    private void connectToDevice(BluetoothDevice device) {
        try {
            handler.post(() -> listener.onStatusChanged("Connecting to GATT..."));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                bluetoothGatt = device.connectGatt(context, false, gattCallback);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied during connectGatt", e);
            handler.post(() -> listener.onStatusChanged("Connect Permission Denied"));
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            try {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    isConnected = true;

                    // Generate file name dynamically for this specific connection session
                    String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                    currentSessionFileName = "Sensor_Data_" + timestamp + ".csv";

                    handler.post(() -> listener.onStatusChanged("Connected. Discovering..."));
                    gatt.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    isConnected = false;

                    // Clear the current session tracker so the next connection gets a fresh file
                    currentSessionFileName = null;

                    handler.post(() -> listener.onStatusChanged("Disconnected. Reconnecting..."));
                    closeGatt();
                    handler.postDelayed(() -> connectOrScan(), 2000);
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Permission violation in callback", e);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                try {
                    if (gatt.getService(SERVICE_UUID) != null) {
                        BluetoothGattCharacteristic characteristic = gatt
                                .getService(SERVICE_UUID)
                                .getCharacteristic(CHARACTERISTIC_UUID);

                        if (characteristic != null) {
                            gatt.setCharacteristicNotification(characteristic, true);
                            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
                            if (descriptor != null) {
                                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                gatt.writeDescriptor(descriptor);
                                handler.post(() -> listener.onStatusChanged("Streaming Data"));
                            }
                        }
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "Permission violation setting notification", e);
                }
            } else {
                Log.e(TAG, "Service discovery failed with status: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                byte[] rawData = characteristic.getValue();
                if (rawData != null) {
                    String payload = new String(rawData, StandardCharsets.UTF_8).trim();
                    parseCsvPayload(payload);
                }
            }
        }
    };

    private void parseCsvPayload(String payload) {
        try {
            String[] tokens = payload.split(",");
            if (tokens.length >= 3) {
                int co2 = Integer.parseInt(tokens[0].trim());
                float temp = Float.parseFloat(tokens[1].trim());
                float humidity = Float.parseFloat(tokens[2].trim());

                // Post data points to the global real-time reactive bus pipeline instantly
                LiveBleDataBus.getInstance().postReading((float) co2, temp, humidity);

                // Export data into the current connection's storage target layout concurrently
                exportDataToCsv(co2, temp, humidity);

                handler.post(() -> listener.onDataParsed(co2, temp, humidity));
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Failed parsing payload: " + payload, e);
        }
    }

    private synchronized void exportDataToCsv(int co2, float temp, float humidity) {
        if (currentSessionFileName == null) return;

        try {
            File csvFile;
            if (activeSessionName != null) {
                File parametersDir = SessionPathManager.getParametersDirectory(context, activeSessionName);
                csvFile = new File(parametersDir, "sensor_data.csv");
            } else {
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                csvFile = new File(downloadsDir, currentSessionFileName);
            }

            boolean isNewFile = !csvFile.exists();
            FileWriter writer = new FileWriter(csvFile, true);

            if (isNewFile) {
                writer.append("Time,CO2(ppm),Temp (*C),Humidity (%RH)\n");
            }

            String currentTime = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String rowData = String.format(Locale.US, "%s,%d,%.2f,%.2f\n", currentTime, co2, temp, humidity);

            writer.append(rowData);
            writer.flush();
            writer.close();

        } catch (IOException e) {
            Log.e(TAG, "Error writing data to session CSV file", e);
        }
    }

    public void stop() {
        stopScanning();
        closeGatt();
    }

    private void closeGatt() {
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (SecurityException e) {
                Log.e(TAG, "Permission violation closing GATT", e);
            }
            bluetoothGatt = null;
        }
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }
}