package ai.pleos.coach.vehicle;

import android.car.Car;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.Context;
import android.util.Log;

/**
 * 주행 중 제한 상태.
 *
 * <p><b>함정</b>: {@code Car.getCarManager("drivingstate")} 는 signature 권한이라
 * SecurityException 이다. 반드시 {@code "uxrestriction"} 을 쓴다(실측).
 */
public final class UxRestrictions {

    private static final String TAG = "CornerNote";
    public static final String SERVICE_NAME = "uxrestriction";

    public interface Listener {
        void onRestrictions(boolean requiresDistractionOptimization);
    }

    private Car car;
    private CarUxRestrictionsManager manager;
    private Listener listener;
    private boolean requiresDo;
    private String failureReason;

    private final CarUxRestrictionsManager.OnUxRestrictionsChangedListener callback =
            new CarUxRestrictionsManager.OnUxRestrictionsChangedListener() {
                @Override
                public void onUxRestrictionsChanged(CarUxRestrictions restrictions) {
                    requiresDo = restrictions != null
                            && restrictions.isRequiresDistractionOptimization();
                    Log.i(TAG, "ux restrictions changed: DO=" + requiresDo
                            + " active=0x" + (restrictions == null ? "null"
                            : Integer.toHexString(restrictions.getActiveRestrictions())));
                    if (listener != null) {
                        listener.onRestrictions(requiresDo);
                    }
                }
            };

    public boolean start(Context context, Listener listener) {
        this.listener = listener;
        try {
            car = Car.createCar(context);
            if (car == null) {
                failureReason = "Car.createCar returned null";
                return false;
            }
            manager = (CarUxRestrictionsManager) car.getCarManager(SERVICE_NAME);
            if (manager == null) {
                failureReason = "no CarUxRestrictionsManager";
                return false;
            }
            manager.registerListener(callback);
            CarUxRestrictions current = manager.getCurrentCarUxRestrictions();
            if (current != null) {
                requiresDo = current.isRequiresDistractionOptimization();
            }
            return true;
        } catch (Throwable t) {
            failureReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "ux restriction init failed: " + failureReason);
            return false;
        }
    }

    public boolean requiresDistractionOptimization() {
        return requiresDo;
    }

    public void stop() {
        try {
            if (manager != null) {
                manager.unregisterListener();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (car != null) {
                car.disconnect();
            }
        } catch (Throwable ignored) {
        }
        manager = null;
        car = null;
    }

    public String failureReason() {
        return failureReason;
    }
}
