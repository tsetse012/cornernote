package ai.pleos.coach.vehicle;

import android.car.Car;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

/**
 * AAOS 속도 폴링. 0x11600207 = PERF_VEHICLE_SPEED.
 *
 * <p>실측 45.6Hz. 값은 1 km/h 로 양자화되어 있다. Pleos SDK 가 아니라
 * {@code CarPropertyManager} 를 직접 쓰는 유일한 신호다.
 */
public final class SpeedSource {

    private static final String TAG = "CornerNote";
    public static final int PROP_PERF_VEHICLE_SPEED = 0x11600207;
    private static final long POLL_MS = 22L; // ≈45.6Hz

    public interface Listener {
        void onSpeedSample(long elapsedMillis, float speedMps);
    }

    private Car car;
    private CarPropertyManager manager;
    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running;
    private int consecutiveFailures;
    private String failureReason;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            try {
                float v = manager.getFloatProperty(PROP_PERF_VEHICLE_SPEED, 0);
                consecutiveFailures = 0;
                long t = SystemClock.elapsedRealtime();
                if (listener != null) {
                    listener.onSpeedSample(t, v);
                }
            } catch (Throwable t) {
                consecutiveFailures++;
                if (consecutiveFailures == 1) {
                    failureReason = t.getClass().getSimpleName() + ": " + t.getMessage();
                    Log.w(TAG, "speed read failed: " + failureReason);
                }
                if (consecutiveFailures > 50) {
                    Log.w(TAG, "speed source stopped after 50 consecutive failures");
                    stop();
                    return;
                }
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    private Listener listener;

    /** @return 초기화 성공 여부. 실패해도 앱은 계속 돈다. */
    public boolean start(Context context, Listener listener) {
        this.listener = listener;
        try {
            car = Car.createCar(context);
            if (car == null) {
                failureReason = "Car.createCar returned null";
                return false;
            }
            manager = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
            if (manager == null) {
                failureReason = "no CarPropertyManager";
                return false;
            }
        } catch (Throwable t) {
            failureReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "speed source init failed: " + failureReason);
            return false;
        }
        thread = new HandlerThread("speed-poll");
        thread.start();
        handler = new Handler(thread.getLooper());
        running = true;
        handler.post(poll);
        return true;
    }

    public void stop() {
        running = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        if (car != null) {
            try {
                car.disconnect();
            } catch (Throwable ignored) {
            }
            car = null;
        }
        manager = null;
    }

    public String failureReason() {
        return failureReason;
    }
}
