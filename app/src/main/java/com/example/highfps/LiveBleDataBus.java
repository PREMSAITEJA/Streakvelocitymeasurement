package com.example.highfps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * LiveBleDataBus — process-wide singleton that carries BLE sensor readings from
 * BleSensorManager to any number of observers (e.g. ResultsActivity live charts)
 * without tying activities to a service or creating memory leaks.
 *
 * Key design choices:
 *  • CopyOnWriteArrayList for observers   → safe to add/remove while iterating
 *  • synchronized history list            → safe to read from the UI thread while
 *                                           BLE callbacks write from BT thread
 *  • Unlimited session history            → full dataset always available for
 *                                           chart replay and CSV/PNG export; call
 *                                           clearSessionHistory() on each new
 *                                           recording start to reset the window
 *  • sessionStartMs                       → elapsed-seconds X-axis is relative to
 *                                           when the current session began, not
 *                                           wall-clock epoch, matching the MATLAB
 *                                           sensor viewer's time axis
 */
public class LiveBleDataBus {

    // ── Singleton ────────────────────────────────────────────────────────────
    private static volatile LiveBleDataBus instance;

    public static LiveBleDataBus getInstance() {
        if (instance == null) {
            synchronized (LiveBleDataBus.class) {
                if (instance == null) instance = new LiveBleDataBus();
            }
        }
        return instance;
    }

    private LiveBleDataBus() {}

    // ── Data model ───────────────────────────────────────────────────────────
    public static class BleReading {
        /** Absolute wall-clock time this reading arrived, used to compute elapsedSeconds. */
        public final long timestampMs;
        /** CO2 concentration in ppm (SCD41 output). */
        public final float co2;
        /** Temperature in °C. */
        public final float temperature;
        /** Relative humidity in %RH. */
        public final float humidity;
        /** Seconds since the current session started (for the chart X-axis). */
        public final float elapsedSeconds;

        BleReading(long sessionStartMs, float co2, float temperature, float humidity) {
            this.timestampMs   = System.currentTimeMillis();
            this.co2           = co2;
            this.temperature   = temperature;
            this.humidity      = humidity;
            this.elapsedSeconds = (this.timestampMs - sessionStartMs) / 1000f;
        }
    }

    // ── Observer contract ────────────────────────────────────────────────────
    public interface Observer {
        /**
         * Called on whatever thread postReading() was invoked from — usually the
         * Bluetooth GATT callback thread.  Post to the main thread if updating UI.
         */
        void onNewReading(BleReading reading);

        /** Called when the bus session is reset so observers can clear their charts. */
        default void onSessionReset() {}
    }

    // ── Internal state ───────────────────────────────────────────────────────
    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    private final List<BleReading> sessionHistory = new ArrayList<>();
    private volatile long sessionStartMs = System.currentTimeMillis();

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Called by BleSensorManager every time a valid CSV packet is parsed from the
     * SCD41 sensor box.  Thread-safe; may be called from the BT callback thread.
     */
    public void postReading(float co2, float temperature, float humidity) {
        BleReading reading = new BleReading(sessionStartMs, co2, temperature, humidity);
        synchronized (sessionHistory) {
            sessionHistory.add(reading);
        }
        // Notify all live observers (CopyOnWriteArrayList iteration is always safe)
        for (Observer o : observers) {
            try { o.onNewReading(reading); } catch (Exception ignored) {}
        }
    }

    /**
     * Clears the stored history and resets the elapsed-seconds origin.
     * Call this at the start of every new recording session so each session's
     * charts begin at t = 0 and the download contains only the current dataset.
     */
    public void clearSessionHistory() {
        synchronized (sessionHistory) {
            sessionHistory.clear();
        }
        sessionStartMs = System.currentTimeMillis();
        for (Observer o : observers) {
            try { o.onSessionReset(); } catch (Exception ignored) {}
        }
    }

    /**
     * Snapshot of every reading in the current session, oldest first.
     * Safe to call from the UI thread.
     */
    public List<BleReading> getSessionHistory() {
        synchronized (sessionHistory) {
            return new ArrayList<>(sessionHistory);
        }
    }

    /** True if at least one reading has been received in this session. */
    public boolean hasData() {
        synchronized (sessionHistory) { return !sessionHistory.isEmpty(); }
    }

    /** Number of readings stored so far this session. */
    public int getReadingCount() {
        synchronized (sessionHistory) { return sessionHistory.size(); }
    }

    public void addObserver(Observer o) {
        if (!observers.contains(o)) observers.add(o);
    }

    public void removeObserver(Observer o) {
        observers.remove(o);
    }
}
