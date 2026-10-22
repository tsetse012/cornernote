package ai.pleos.coach.vehicle;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import ai.pleos.playground.vehicle.Vehicle;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * 전비 재료 — SOC·배터리용량·주행거리. 요청 시 값.
 *
 * <p>{@code getInstantaneousEvEfficiency} 계열은 쓰지 않는다(에뮬레이터 미지원).
 */
public final class EnergySource {

    private static final String TAG = "CornerNote";
    private static final long POLL_MS = 2000L;

    public interface Listener {
        void onEnergy(long elapsedMillis, Integer socPercent, Float capacityRaw, Float distanceKm);
    }

    private final AtomicReference<Integer> soc = new AtomicReference<>();
    private final AtomicReference<Float> capacity = new AtomicReference<>();
    private final AtomicReference<Float> distance = new AtomicReference<>();
    private final AtomicBoolean distanceInMeters = new AtomicBoolean(false);

    private Vehicle vehicle;
    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running;
    private Listener listener;
    private String failureReason;
    private boolean lastDistanceWasLarge;
    private String lastLogged = "";

    private static final Function1<Exception, Unit> IGNORE = e -> Unit.INSTANCE;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running || vehicle == null) {
                return;
            }
            try {
                vehicle.getEvBattery().getEvBatteryStateOfCharge(
                        (Function1<Integer, Unit>) v -> {
                            soc.set(v);
                            return Unit.INSTANCE;
                        }, IGNORE);
            } catch (Throwable ignored) {
            }
            try {
                vehicle.getEvBattery().getEvBatteryCapacity(
                        (Function1<Float, Unit>) v -> {
                            capacity.set(v);
                            return Unit.INSTANCE;
                        }, IGNORE);
            } catch (Throwable ignored) {
            }
            try {
                vehicle.getOdometer().getCurrentDistanceDriven(
                        (Function1<Float, Unit>) v -> {
                            distance.set(v);
                            return Unit.INSTANCE;
                        }, IGNORE);
            } catch (Throwable ignored) {
            }
            Float d = distance.get();
            Float norm = normalizeDistance(d);
            // 값이 바뀔 때만 남긴다 — 2초마다 찍으면 logcat 이 이것만으로 넘친다
            String stamp = soc.get() + "/" + capacity.get() + "/" + d + "/" + norm;
            if (!stamp.equals(lastLogged)) {
                lastLogged = stamp;
                Log.i(TAG, "ENERGY soc=" + soc.get() + " capacityRaw=" + capacity.get()
                        + " distanceRaw=" + d + " distanceKm=" + norm);
            }
            if (listener != null) {
                listener.onEnergy(SystemClock.elapsedRealtime(), soc.get(), capacity.get(), norm);
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    /**
     * 차량이 주는 주행거리의 단위를 km 로 맞춘다.
     * 에뮬레이터 실측값이 1000 이상이면 m 로 보고 환산한다.
     */
    private Float normalizeDistance(Float raw) {
        if (raw == null) {
            return null;
        }
        if (raw > 1000.0f) {
            lastDistanceWasLarge = true;
            distanceInMeters.set(true);
        }
        return distanceInMeters.get() ? raw / 1000.0f : raw;
    }

    public boolean start(Vehicle vehicle, Listener listener) {
        this.vehicle = vehicle;
        this.listener = listener;
        if (vehicle == null) {
            failureReason = "vehicle not initialized";
            return false;
        }
        thread = new HandlerThread("energy-poll");
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
        vehicle = null;
    }

    public boolean distanceWasLarge() {
        return lastDistanceWasLarge;
    }

    public String failureReason() {
        return failureReason;
    }

    /** 진단용 원값 로그. */
    public void logRaw() {
        Log.i(TAG, "ENERGY raw soc=" + soc.get() + " capacity=" + capacity.get()
                + " distance=" + distance.get());
    }
}
