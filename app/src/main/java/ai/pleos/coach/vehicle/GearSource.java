package ai.pleos.coach.vehicle;

import android.util.Log;

import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.constant.control.VehicleGear;
import ai.pleos.playground.vehicle.listener.GearSelectionListener;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * Pleos Vehicle SDK 래퍼 — 기어. P/R/N/D.
 * 리포트 화면 진입 조건(정차 = P)에 쓴다.
 */
public final class GearSource {

    private static final String TAG = "CornerNote";

    public static final int GEAR_PARK = 0;
    public static final int GEAR_REVERSE = 1;
    public static final int GEAR_NEUTRAL = 2;
    public static final int GEAR_DRIVE = 3;

    public interface Listener {
        void onGear(int gearCode);
    }

    private Vehicle vehicle;
    private Listener listener;
    private String failureReason;
    private int lastGear = -1;

    private final GearSelectionListener callback = new GearSelectionListener() {
        @Override
        public void onSelected(VehicleGear gear) {
            publish(map(gear));
        }

        @Override
        public void onFailed(Exception e) {
            Log.w(TAG, "gear callback failed: " + e);
        }
    };

    private static final Function1<Exception, Unit> IGNORE = e -> Unit.INSTANCE;

    public boolean start(Vehicle vehicle, Listener listener) {
        this.vehicle = vehicle;
        this.listener = listener;
        if (vehicle == null) {
            failureReason = "vehicle not initialized";
            return false;
        }
        try {
            vehicle.getGear().registerGearSelection(callback);
            // 콜백은 값이 변할 때만 오므로 현재 값을 한 번 당겨온다
            vehicle.getGear().getCurrentGearSelectionState(
                    (Function1<VehicleGear, Unit>) gear -> {
                        publish(map(gear));
                        return Unit.INSTANCE;
                    }, IGNORE);
            return true;
        } catch (Throwable t) {
            failureReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "gear source init failed: " + failureReason);
            return false;
        }
    }

    private void publish(int gear) {
        lastGear = gear;
        if (listener != null) {
            listener.onGear(gear);
        }
    }

    private static int map(VehicleGear gear) {
        if (gear == null) {
            return -1;
        }
        switch (gear) {
            case GEAR_PARK:
                return GEAR_PARK;
            case GEAR_REVERSE:
                return GEAR_REVERSE;
            case GEAR_NEUTRAL:
                return GEAR_NEUTRAL;
            case GEAR_DRIVE:
                return GEAR_DRIVE;
            default:
                return GEAR_DRIVE;
        }
    }

    public int lastGear() {
        return lastGear;
    }

    public void stop() {
        try {
            if (vehicle != null) {
                vehicle.getGear().unregisterGearSelection(callback);
            }
        } catch (Throwable ignored) {
        }
        vehicle = null;
    }

    public String failureReason() {
        return failureReason;
    }
}
