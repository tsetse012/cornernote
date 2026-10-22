package ai.pleos.coach.vehicle;

import android.os.SystemClock;
import android.util.Log;

import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener;

/**
 * Pleos Vehicle SDK 래퍼 — 조향각. 실측 7.8Hz, 값이 변할 때만 발화.
 */
public final class SteerSource {

    private static final String TAG = "CornerNote";

    public interface Listener {
        void onSteerSample(long elapsedMillis, float steerDeg);
    }

    private Vehicle vehicle;
    private Listener listener;
    private String failureReason;

    private final SteeringWheelAngleListener callback = new SteeringWheelAngleListener() {
        @Override
        public void onSteeringWheelAngleUpdated(Float angle) {
            if (angle == null || listener == null) {
                return;
            }
            listener.onSteerSample(SystemClock.elapsedRealtime(), angle);
        }

        @Override
        public void onFailed(Exception e) {
            Log.w(TAG, "steering callback failed: " + e);
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
            vehicle.getSteeringWheel().registerSteeringWheelAngle(callback);
            return true;
        } catch (Throwable t) {
            failureReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "steer source init failed: " + failureReason);
            return false;
        }
    }

    public void stop() {
        try {
            if (vehicle != null) {
                vehicle.getSteeringWheel().unregisterSteeringWheelAngle(callback);
            }
        } catch (Throwable ignored) {
        }
        vehicle = null;
    }

    public String failureReason() {
        return failureReason;
    }
}
