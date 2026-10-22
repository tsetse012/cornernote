package ai.pleos.coach.vehicle;

import android.os.SystemClock;
import android.util.Log;

import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener;

/**
 * Pleos Vehicle SDK 래퍼 — 브레이크 페달 boolean. 압력은 없다.
 * 지표 3(브레이크-조향 전환 지연)의 시작점을 만든다.
 */
public final class BrakeSource {

    private static final String TAG = "CornerNote";

    public interface Listener {
        void onBrakeSample(long elapsedMillis, boolean pressed);
    }

    private Vehicle vehicle;
    private Listener listener;
    private String failureReason;
    private boolean lastState;
    private boolean hasState;

    private final BrakePedalStatusListener callback = new BrakePedalStatusListener() {
        @Override
        public void onBrakePedalStatusUpdated(Boolean pressed) {
            if (pressed == null || listener == null) {
                return;
            }
            lastState = pressed;
            hasState = true;
            listener.onBrakeSample(SystemClock.elapsedRealtime(), pressed);
        }

        @Override
        public void onFailed(Exception e) {
            Log.w(TAG, "brake callback failed: " + e);
        }
    };

    public boolean start(Vehicle vehicle, Listener listener) {
        this.vehicle = vehicle;
        this.listener = listener;
        if (vehicle == null) {
            failureReason = "vehicle not initialized";
            return false;
        }
        try {
            vehicle.getBrake().registerBrakePedalStatus(callback);
            return true;
        } catch (Throwable t) {
            failureReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "brake source init failed: " + failureReason);
            return false;
        }
    }

    public void stop() {
        try {
            if (vehicle != null) {
                vehicle.getBrake().unregisterBrakePedalStatus(callback);
            }
        } catch (Throwable ignored) {
        }
        vehicle = null;
    }

    public String failureReason() {
        return failureReason;
    }

    public boolean hasState() {
        return hasState;
    }

    public boolean lastState() {
        return lastState;
    }
}
